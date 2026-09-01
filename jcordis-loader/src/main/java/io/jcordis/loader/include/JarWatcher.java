package io.jcordis.loader.include;

import io.jcordis.core.util.Disposable;
import io.jcordis.loader.Loader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Plugin jar hot-reload watcher, mirroring the module-watching subset of
 * Cordis's {@code Hmr}.
 *
 * <p>Watches a directory for {@code .jar} changes (SHA-256 fingerprint, so
 * rewrite-detection is exact). A jar named {@code <name>.jar} is loaded when it
 * first appears; on change it is hot-replaced via {@link Loader#replaceJar} —
 * the new jar is validated first and the old plugin is kept on failure (the
 * rollback semantics of Cordis's partial reload).
 */
public final class JarWatcher implements Runnable {

    private final Loader loader;
    private final Path dir;
    private final Map<String, String> fingerprints = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** Created on the start thread, closed on stop — visible across threads. */
    private volatile WatchService watchService;
    /** The polling thread, joined on stop so no in-flight handle runs after teardown. */
    private volatile Thread worker;
    /** Retry scheduler: delayed re-checks for write races (single daemon thread). */
    private final java.util.concurrent.ScheduledExecutorService retries =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "jcordis-jar-retry");
                thread.setDaemon(true);
                return thread;
            });
    /** In-flight or queued retries, tracked so stop() can wait for them. */
    private final AtomicInteger pendingRetries = new AtomicInteger();

    public JarWatcher(Loader loader, Path dir) {
        this.loader = loader;
        this.dir = dir;
    }

    /** Scans the directory and starts watching it. */
    public Disposable start() {
        if (!running.compareAndSet(false, true)) {
            return Disposable.noop();
        }
        try {
            Files.createDirectories(dir);
            watchService = java.nio.file.FileSystems.getDefault().newWatchService();
            dir.register(
                    watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
            try (Stream<Path> jars = Files.list(dir)) {
                jars.filter(p -> p.getFileName().toString().endsWith(".jar")).forEach(this::loadInitial);
            }
        } catch (IOException e) {
            running.set(false);
            throw new IllegalStateException("cannot watch plugin directory " + dir, e);
        }
        Thread thread = new Thread(this, "jcordis-jar-watcher");
        thread.setDaemon(true);
        this.worker = thread;
        thread.start();
        return () -> stop();
    }

    /**
     * Stops watching and releases every resource: the worker is interrupted and
     * joined, and queued/in-flight retries are cancelled and awaited — no
     * class loader may hold a jar handle after teardown (otherwise deleting
     * the jar fails on Windows).
     */
    public void stop() {
        running.set(false);
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                // nothing to recover from a failed watch close
            }
        }
        retries.shutdownNow();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (pendingRetries.get() > 0 && System.nanoTime() < deadline) {
            Thread.yield();
        }
        Thread thread = worker;
        if (thread != null && thread != Thread.currentThread()) {
            thread.interrupt();
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void loadInitial(Path jar) {
        String name = jarName(jar);
        try {
            if (!loader.modules.containsKey(name)) {
                loader.loadJar(jar, name);
            }
            fingerprints.put(name, sha256(jar));
        } catch (IOException | RuntimeException | Error e) {
            loader.ctx().logger("loader").warn("cannot load plugin jar " + jar + ": " + e.getMessage());
        }
    }

    @Override
    public void run() {
        while (running.get()) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException | java.nio.file.ClosedWatchServiceException e) {
                return;
            }
            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.context() instanceof Path fileName) {
                    handle(fileName, event.kind());
                }
            }
            key.reset();
        }
    }

    private void handle(Path fileName, WatchEvent.Kind<?> kind) {
        if (!running.get()) return; // teardown in progress — never load after stop
        String file = fileName.getFileName().toString();
        if (!file.endsWith(".jar")) return;
        Path jar = dir.resolve(file);
        String name = jarName(jar);
        if (kind == StandardWatchEventKinds.ENTRY_DELETE || !Files.exists(jar)) {
            fingerprints.remove(name);
            return;
        }
        String hash;
        try {
            hash = sha256(jar);
        } catch (IOException e) {
            scheduleRetry(file);
            return;
        }
        if (Objects.equals(hash, fingerprints.get(name))) return;
        try {
            if (loader.modules.containsKey(name)) {
                loader.replaceJar(jar, name);
            } else {
                loader.loadJar(jar, name);
            }
            fingerprints.put(name, hash);
            loader.ctx().events().emit((Object) null, "hmr/reload", name);
        } catch (RuntimeException | Error e) {
            loader.ctx().logger("loader").error("plugin hot-reload failed for " + name + ": " + e.getMessage());
            scheduleRetry(file);
        }
    }

    /**
     * Re-checks a jar shortly after a failed read or replace, covering the
     * window where a watch event fires before the file was fully written.
     */
    private void scheduleRetry(String file) {
        pendingRetries.incrementAndGet();
        java.util.concurrent.CompletableFuture<Void> future = java.util.concurrent.CompletableFuture.runAsync(
                () -> {
                    // the watcher may have been stopped while we slept: never
                    // load jars after teardown (a fresh class loader would hold
                    // the jar handle and leak it)
                    if (!running.get()) return;
                    handle(Path.of(file), StandardWatchEventKinds.ENTRY_MODIFY);
                },
                java.util.concurrent.CompletableFuture.delayedExecutor(200, TimeUnit.MILLISECONDS, retries));
        // decrement on completion OR cancellation so stop() can wait precisely
        future.whenComplete((ignored, error) -> pendingRetries.decrementAndGet());
    }

    private static String jarName(Path jar) {
        String file = jar.getFileName().toString();
        return file.substring(0, file.length() - ".jar".length());
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
