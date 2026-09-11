package io.jcordis.loader.include;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import io.jcordis.core.util.Disposable;
import io.jcordis.loader.Loader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Generic file watching ({@code hmr.watch()}), mirroring Cordis's
 * {@code feat(hmr): support hmr.watch()} (commit {@code caab04e}): any path can
 * be watched, several callbacks may share a path, and a registration is
 * removed by its disposable.
 */
class HmrWatchTest {

    @TempDir
    Path tempDir;

    private static void waitFor(Supplier<Boolean> condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.get()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within 5s");
            }
            Thread.sleep(20);
        }
    }

    @Test
    void watch_shouldRunCallbackOnChangeAndStopAfterDispose() throws Exception {
        Path file = tempDir.resolve("watched.txt");
        Files.writeString(file, "one", StandardCharsets.UTF_8);

        Context root = Context.create();
        Loader loader = new Loader(root);
        // no `path` option: only generic watch() is active
        Hmr hmr = new Hmr(root, loader, Map.of("interval", 30));
        hmr.start();
        AtomicInteger calls = new AtomicInteger();
        Disposable registration = hmr.watch(file, calls::incrementAndGet);
        try {
            Thread.sleep(150);
            assertThat(calls).as("no change yet").hasValue(0);

            Files.writeString(file, "two", StandardCharsets.UTF_8);
            waitFor(() -> calls.get() >= 1);

            // unregistering stops the callback
            registration.dispose();
            Thread.sleep(100);
            int settled = calls.get();
            Files.writeString(file, "three", StandardCharsets.UTF_8);
            Thread.sleep(200);
            assertThat(calls).as("no callback after dispose").hasValue(settled);
        } finally {
            hmr.stop();
        }
    }

    @Test
    void watch_shouldSupportSeveralCallbacksPerPath() throws Exception {
        Path file = tempDir.resolve("multi.txt");
        Files.writeString(file, "one", StandardCharsets.UTF_8);

        Context root = Context.create();
        Loader loader = new Loader(root);
        Hmr hmr = new Hmr(root, loader, Map.of("interval", 30));
        hmr.start();
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        hmr.watch(file, first::incrementAndGet);
        hmr.watch(file, second::incrementAndGet);
        assertThat(hmr.isWatching(file)).isTrue();
        try {
            Files.writeString(file, "two", StandardCharsets.UTF_8);
            waitFor(() -> first.get() >= 1 && second.get() >= 1);
        } finally {
            hmr.stop();
        }
    }
}
