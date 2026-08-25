package io.jcordis.loader.include;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import io.jcordis.loader.EntryOptions;
import io.jcordis.loader.Loader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Translates Cordis HMR module watching: jar hot-replace with rollback. */
class JarWatcherTest {

    private static final String V1 = "io.jcordis.fixture.V1Plugin";
    private static final String V2 = "io.jcordis.fixture.V2Plugin";
    private static final String PROBE = "jcordis.probe.version";

    @TempDir
    Path tempDir;

    @Test
    void start_shouldLoadExistingJars() throws Exception {
        Path jar = buildJar(tempDir.resolve("demo-plugin.jar"), V1);
        Context root = Context.create();
        Loader loader = new Loader(root);
        JarWatcher watcher = new JarWatcher(loader, tempDir);
        watcher.start();
        try {
            waitFor(() -> loader.modules.containsKey("demo-plugin"));

            EntryOptions options = new EntryOptions();
            options.name = "demo-plugin";
            String id = loader.create(options, null);
            assertThat(loader.expectFiber(id)).isNotNull();
        } finally {
            watcher.stop();
            loader.unload("demo-plugin");
        }
    }

    @Test
    void jarReplacement_shouldSwapPlugin() throws Exception {
        Path jar = tempDir.resolve("demo-plugin.jar");
        buildJar(jar, V1);
        Context root = Context.create();
        Loader loader = new Loader(root);
        JarWatcher watcher = new JarWatcher(loader, tempDir);
        watcher.start();
        try {
            waitFor(() -> loader.modules.containsKey("demo-plugin"));
            EntryOptions options = new EntryOptions();
            options.name = "demo-plugin";
            loader.create(options, null);
            waitFor(() -> "1".equals(System.getProperty(PROBE)));

            buildJar(jar, V2);

            // the entry is reloaded with the swapped plugin
            waitFor(() -> "2".equals(System.getProperty(PROBE)));
        } finally {
            System.clearProperty(PROBE);
            watcher.stop();
            loader.unload("demo-plugin");
        }
    }

    @Test
    void brokenJar_shouldKeepOldPlugin() throws Exception {
        Path jar = tempDir.resolve("demo-plugin.jar");
        buildJar(jar, V1);
        Context root = Context.create();
        Loader loader = new Loader(root);
        JarWatcher watcher = new JarWatcher(loader, tempDir);
        watcher.start();
        try {
            waitFor(() -> loader.modules.containsKey("demo-plugin"));
            io.jcordis.core.registry.Plugin before = loader.modules.get("demo-plugin");

            Files.write(jar, new byte[] {1, 2, 3, 4});
            Thread.sleep(500);

            // the failed replace left the previous plugin untouched
            assertThat(loader.modules.get("demo-plugin")).isSameAs(before);
        } finally {
            watcher.stop();
            loader.unload("demo-plugin");
        }
    }

    private static void waitFor(Supplier<Boolean> condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.get()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within 5s");
            }
            Thread.sleep(20);
        }
    }

    /** Packs the given classes (from test-fixtures-classes) plus an SPI manifest into a jar. */
    private static Path buildJar(Path jar, String... serviceClasses) throws IOException {
        Path fixtureClasses = Paths.get("target", "test-fixtures-classes");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (String className : serviceClasses) {
                String resource = className.replace('.', '/') + ".class";
                out.putNextEntry(new JarEntry(resource));
                Files.copy(fixtureClasses.resolve(resource), out);
                out.closeEntry();
            }
            out.putNextEntry(new JarEntry("META-INF/services/io.jcordis.core.registry.Plugin"));
            out.write((String.join("\n", serviceClasses) + "\n").getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }
}
