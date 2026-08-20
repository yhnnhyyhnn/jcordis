package io.jcordis.loader.include;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import io.jcordis.loader.include.fixture.V1Plugin;
import io.jcordis.loader.include.fixture.V2Plugin;
import io.jcordis.loader.EntryOptions;
import io.jcordis.loader.Loader;
import java.io.IOException;
import java.net.URISyntaxException;
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

    @TempDir
    Path tempDir;

    @Test
    void start_shouldLoadExistingJars() throws Exception {
        Path jar = buildJar(tempDir.resolve("demo-plugin.jar"), V1Plugin.class.getName());
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
        buildJar(jar, V1Plugin.class.getName());
        Context root = Context.create();
        Loader loader = new Loader(root);
        JarWatcher watcher = new JarWatcher(loader, tempDir);
        watcher.start();
        try {
            waitFor(() -> loader.modules.containsKey("demo-plugin"));
            EntryOptions options = new EntryOptions();
            options.name = "demo-plugin";
            String id = loader.create(options, null);
            waitFor(() -> V1Plugin.calls.get() >= 1);

            V1Plugin.calls.set(0);
            buildJar(jar, V2Plugin.class.getName());

            // the entry is reloaded with the swapped plugin
            waitFor(() -> V2Plugin.calls.get() >= 1);
            assertThat(V1Plugin.calls).hasValue(0);
        } finally {
            watcher.stop();
            loader.unload("demo-plugin");
        }
    }

    @Test
    void brokenJar_shouldKeepOldPlugin() throws Exception {
        Path jar = tempDir.resolve("demo-plugin.jar");
        buildJar(jar, V1Plugin.class.getName());
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

    /** Packs the given classes (from test-classes) plus an SPI manifest into a jar. */
    private static Path buildJar(Path jar, String... serviceClasses) throws IOException, URISyntaxException {
        Path testClasses = Paths.get(
                V1Plugin.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (String className : serviceClasses) {
                String resource = className.replace('.', '/') + ".class";
                out.putNextEntry(new JarEntry(resource));
                Files.copy(testClasses.resolve(resource), out);
                out.closeEntry();
            }
            out.putNextEntry(new JarEntry("META-INF/services/io.jcordis.core.registry.Plugin"));
            out.write((String.join("\n", serviceClasses) + "\n").getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }
}
