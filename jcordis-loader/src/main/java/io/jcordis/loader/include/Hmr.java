package io.jcordis.loader.include;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.jcordis.core.context.Context;
import io.jcordis.core.event.EventOptions;
import io.jcordis.core.fiber.EffectResult;
import io.jcordis.core.util.Disposable;
import io.jcordis.loader.EntryOptions;
import io.jcordis.loader.Loader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Hot reload service (container-rebuild approach), mirroring Cordis's
 * {@code Hmr}.
 *
 * <p>Two capabilities:
 * <ul>
 *   <li><b>generic file watching</b> — {@link #watch(Path, Runnable)} registers
 *       a callback for any path; the polling loop compares exact
 *       {@link FileTime}s and runs the registered callbacks on change
 *       (mirrors Cordis's {@code hmr.watch()} from commit {@code caab04e});</li>
 *   <li><b>config-file reload (convenience)</b> — when constructed with a
 *       {@code path} option, that config file is watched and re-applied to the
 *       loader tree on change: the YAML is re-parsed and diff-updated
 *       ({@code loader.root.update}), disposing removed/disabled plugins and
 *       initializing new ones. Parse failures keep the previous tree
 *       (rollback), and a {@code hmr/reload} event fires after each successful
 *       reload.</li>
 * </ul>
 *
 * <p>The service registers itself as {@code hmr} on its context, so plugins
 * (e.g. {@code Include}) can discover it and register their own watches —
 * registrations for the same path are de-duplicated, and a registered watch is
 * removed automatically when its {@code ctx.effect} owner is torn down.
 */
public final class Hmr implements Runnable {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private final Context ctx;
    private final Loader loader;
    /** Convenience config file; {@code null} when only {@link #watch} is used. */
    private final Path configPath;

    private final long interval;

    private final AtomicBoolean running = new AtomicBoolean(false);
    /** Watched target path → callbacks (a path may have several). */
    private final Map<Path, Set<Runnable>> watchers = new ConcurrentHashMap<>();
    /**
     * Last observed fingerprint per watched path (absent = not seen yet). A file
     * fingerprint is its modification time; a directory's covers its whole tree
     * (relative name + modification time + size), so adding, removing or editing
     * any contained file counts as a change.
     */
    private final Map<Path, Object> lastSeen = new ConcurrentHashMap<>();

    /** Written by the polling thread, read by include listeners on app threads. */
    private volatile List<EntryOptions> data;

    public Hmr(Context ctx, Loader loader, Map<String, Object> config) {
        this.ctx = ctx;
        this.loader = loader;
        Object path = config.get("path");
        this.configPath = path == null
                ? null
                : Paths.get(String.valueOf(path)).toAbsolutePath().normalize();
        this.interval = config.get("interval") instanceof Number n ? n.longValue() : 200;
        // expose the service so plugins can register their own watches
        ctx.provide("hmr", this);
    }

    /**
     * Watches one path and runs {@code callback} whenever it changes. A file is
     * compared by modification time; a directory by the fingerprint of its whole
     * tree (so adding, removing or editing any contained file counts).
     * Registrations are not exclusive: several callbacks may watch the same
     * path, and the registration is removed when its {@code ctx.effect} owner is
     * torn down.
     *
     * @return a disposable that unregisters this callback
     */
    public Disposable watch(Path path, Runnable callback) {
        Path target = path.toAbsolutePath().normalize();
        return ctx.effect(
                runner -> {
                    watchers.computeIfAbsent(target, key -> ConcurrentHashMap.newKeySet())
                            .add(callback);
                    Object current = fingerprint(target);
                    if (current != null) {
                        lastSeen.putIfAbsent(target, current);
                    }
                    return EffectResult.of(() -> {
                        Set<Runnable> callbacks = watchers.get(target);
                        if (callbacks == null) return;
                        callbacks.remove(callback);
                        if (callbacks.isEmpty()) {
                            watchers.remove(target);
                            lastSeen.remove(target);
                        }
                    });
                },
                "ctx.hmr.watch()");
    }

    /** Whether {@code path} currently has at least one registered watch. */
    public boolean isWatching(Path path) {
        return watchers.containsKey(path.toAbsolutePath().normalize());
    }

    /** Starts the polling loop (and the convenience config watch, if configured). */
    public Disposable start() {
        if (!running.compareAndSet(false, true)) {
            return Disposable.noop();
        }
        if (configPath != null) {
            try {
                data = readConfig();
                loader.root.update(data);
            } catch (IOException e) {
                ctx.logger().error("cannot read initial config: " + configPath, e);
            }
            // de-duplicate: an Include plugin may already watch this file
            if (!isWatching(configPath)) {
                watch(configPath, this::reloadConfig);
            }
        }
        Thread thread = new Thread(this, "jcordis-hmr");
        thread.setDaemon(true);
        thread.start();
        return () -> running.set(false);
    }

    /** Stops the polling loop. */
    public void stop() {
        running.set(false);
    }

    @Override
    public void run() {
        while (running.get()) {
            try {
                Thread.sleep(interval);
                check();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Polls every watched path and runs its callbacks on change. */
    private void check() {
        for (Map.Entry<Path, Set<Runnable>> entry : watchers.entrySet()) {
            Path target = entry.getKey();
            Object current = fingerprint(target);
            if (current == null) continue;
            Object previous = lastSeen.put(target, current);
            if (current.equals(previous)) continue;
            for (Runnable callback : entry.getValue()) {
                try {
                    callback.run();
                } catch (Throwable error) {
                    ctx.logger().warn("hmr callback failed for " + target + ": " + error.getMessage());
                }
            }
        }
    }

    /** Re-reads the configured config file and diff-updates the loader tree. */
    private void reloadConfig() {
        List<EntryOptions> next;
        try {
            next = readConfig();
        } catch (IOException e) {
            ctx.logger().error("config parse failed, keeping previous tree: " + e.getMessage());
            return;
        }
        data = next;
        loader.root.update(data);
        ctx.events().emit((Object) null, "hmr/reload", data.size());
    }

    /** The current config data (last successfully parsed list). */
    public List<EntryOptions> data() {
        return data;
    }

    private List<EntryOptions> readConfig() throws IOException {
        String content = Files.readString(configPath, StandardCharsets.UTF_8);
        return YAML.readValue(content, new TypeReference<List<EntryOptions>>() {});
    }

    private static FileTime modificationTime(Path path) {
        try {
            return Files.exists(path) ? Files.getLastModifiedTime(path) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * The change fingerprint of a watched path: the modification time for a
     * file, the sorted {@code relative-path@mtime@size} list of every contained
     * file for a directory. Returns {@code null} when the path does not exist
     * (so a missing path is simply skipped, not reported as a change).
     */
    private static Object fingerprint(Path path) {
        try {
            if (!Files.exists(path)) return null;
            if (!Files.isDirectory(path)) return modificationTime(path);
            try (var stream = Files.walk(path)) {
                return stream.filter(Files::isRegularFile)
                        .map(file -> {
                            try {
                                return path.relativize(file) + "@" + Files.getLastModifiedTime(file) + "@"
                                        + Files.size(file);
                            } catch (IOException e) {
                                return path.relativize(file).toString();
                            }
                        })
                        .sorted()
                        .toList();
            }
        } catch (IOException e) {
            return null;
        }
    }

    /** Registers an {@code hmr/reload} listener (for tests). */
    public Disposable onReload(EventHandler handler) {
        return ctx.events()
                .on(
                        ctx,
                        "hmr/reload",
                        (thisArg, args) -> {
                            handler.accept(args);
                            return null;
                        },
                        EventOptions.of());
    }

    @FunctionalInterface
    public interface EventHandler {
        void accept(Object[] args);
    }
}
