package io.jcordis.loader.include;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.jcordis.core.context.Context;
import io.jcordis.core.event.EventOptions;
import io.jcordis.core.util.Disposable;
import io.jcordis.loader.EntryOptions;
import io.jcordis.loader.Loader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Hot reload service (container-rebuild approach), mirroring the config-reload
 * subset of Cordis's {@code Hmr}.
 *
 * <p>Java cannot recompile changed classes, so the equivalent is watching the
 * loader config file: on change, the YAML is re-parsed and the loader tree is
 * diff-updated ({@code loader.root.update}), disposing removed/disabled
 * plugins and initializing new ones. Parse failures keep the previous tree
 * (rollback), and a {@code hmr/reload} event fires after each successful
 * reload.
 */
public final class Hmr implements Runnable {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private final Context ctx;
    private final Loader loader;
    private final Path configPath;
    private final long interval;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private long lastModified;
    private List<EntryOptions> data;

    public Hmr(Context ctx, Loader loader, Map<String, Object> config) {
        this.ctx = ctx;
        this.loader = loader;
        this.configPath = Paths.get((String) config.getOrDefault("path", "jcordis.yml"));
        this.interval = config.get("interval") instanceof Number n ? n.longValue() : 200;
    }

    /** Starts the polling loop. */
    public Disposable start() {
        if (!running.compareAndSet(false, true)) {
            return Disposable.noop();
        }
        try {
            data = readConfig();
            loader.root.update(data);
            lastModified = Files.getLastModifiedTime(configPath).toMillis();
        } catch (IOException e) {
            ctx.logger().error("cannot read initial config: " + configPath, e);
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

    private void check() {
        if (!Files.exists(configPath)) return;
        long mtime;
        try {
            mtime = Files.getLastModifiedTime(configPath).toMillis();
        } catch (IOException e) {
            return;
        }
        if (mtime == lastModified) return;
        lastModified = mtime;

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

    private List<EntryOptions> readConfig() throws IOException {
        String content = Files.readString(configPath, StandardCharsets.UTF_8);
        return YAML.readValue(content, new TypeReference<List<EntryOptions>>() {});
    }

    /** Registers an {@code hmr/reload} listener (for tests). */
    public Disposable onReload(EventHandler handler) {
        return ctx.events().on(ctx, "hmr/reload", (thisArg, args) -> {
            handler.accept(args);
            return null;
        }, EventOptions.of());
    }

    @FunctionalInterface
    public interface EventHandler {
        void accept(Object[] args);
    }
}