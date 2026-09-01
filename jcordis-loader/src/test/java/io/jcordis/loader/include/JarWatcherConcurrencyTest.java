package io.jcordis.loader.include;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import io.jcordis.loader.Loader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Stress: the JarWatcher thread hot-swaps jars while an app thread reads the loader. */
class JarWatcherConcurrencyTest {

    private static final String FIXTURE = "io.jcordis.fixture.IsolatedPlugin";

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
    void watcherThread_shouldNotCorruptUnderConcurrentJarTraffic() throws Exception {
        Path dir = tempDir.resolve("plugins");
        Files.createDirectories(dir);
        Path jar = dir.resolve("iso.jar");
        buildJar(jar);

        Context root = Context.create();
        Loader loader = new Loader(root);
        JarWatcher watcher = new JarWatcher(loader, dir);
        watcher.start();
        waitFor(() -> loader.modules.containsKey("iso"));
        AtomicReference<Throwable> failure = new AtomicReference<>();

        // a writer keeps replacing the jar (MODIFY → atomic hot-swap)
        CompletableFuture<Void> replacer = CompletableFuture.runAsync(() -> {
            try {
                for (int i = 0; i < 15; i++) {
                    buildJar(jar);
                    Thread.sleep(25);
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        // an app thread reads the loader concurrently
        CompletableFuture<Void> reader = CompletableFuture.runAsync(() -> {
            try {
                for (int i = 0; i < 300; i++) {
                    loader.entries();
                    loader.modules.containsKey("iso");
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        CompletableFuture.allOf(replacer, reader).join();

        // data-corruption exceptions fail the test; the plugin stays registered
        assertThat(failure).hasValue(null);
        assertThat(loader.modules).containsKey("iso");
        watcher.stop();
        // release the jar handle so @TempDir cleanup can delete it
        loader.unload("iso");
    }

    private static void buildJar(Path jar) throws IOException {
        Path fixtureClasses = Paths.get("target", "test-fixtures-classes");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            String resource = FIXTURE.replace('.', '/') + ".class";
            out.putNextEntry(new JarEntry(resource));
            Files.copy(fixtureClasses.resolve(resource), out);
            out.closeEntry();
            out.putNextEntry(new JarEntry("META-INF/services/io.jcordis.core.registry.Plugin"));
            out.write((FIXTURE + "\n").getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }
}
