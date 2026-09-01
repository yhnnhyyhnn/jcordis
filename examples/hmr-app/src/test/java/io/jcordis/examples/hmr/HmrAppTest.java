package io.jcordis.examples.hmr;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import io.jcordis.loader.Loader;
import io.jcordis.loader.include.Hmr;
import io.jcordis.loader.include.JarWatcher;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end hot reload demo:
 * <ul>
 *   <li>config-file change → Hmr diff-updates the tree → the plugin body
 *       re-runs with the new config;</li>
 *   <li>plugin jar replacement → JarWatcher swaps the plugin atomically → the
 *       entry is reloaded with the new implementation.</li>
 * </ul>
 */
class HmrAppTest {

    private static final String V1 = "io.jcordis.examples.hmr.fixture.V1Plugin";
    private static final String V2 = "io.jcordis.examples.hmr.fixture.V2Plugin";
    private static final String PROBE = "jcordis.probe.version";

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
    void configChange_hotReloadsPlugin() throws Exception {
        Path config = tempDir.resolve("app.yml");
        Files.writeString(
                config,
                """
                - id: greeter
                  name: greeter-plugin
                  config:
                    greeting: hello
                """,
                StandardCharsets.UTF_8);

        Context root = Context.create();
        Loader loader = new Loader(root);
        AtomicInteger greetings = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<String> seen = new java.util.concurrent.atomic.AtomicReference<>();
        loader.builtin("greeter-plugin", (ctx, cfg) -> {
            if (cfg instanceof Map<?, ?> map) {
                seen.set(String.valueOf(map.get("greeting")));
            }
            greetings.incrementAndGet();
            return null;
        });

        Hmr hmr = new Hmr(root, loader, Map.of("path", config.toString(), "interval", 30));
        hmr.start();
        try {
            waitFor(() -> "hello".equals(seen.get()));
            assertThat(greetings).hasValue(1);

            // edit the config: the plugin re-runs with the new greeting
            Files.writeString(
                    config,
                    """
                    - id: greeter
                      name: greeter-plugin
                      config:
                        greeting: bonjour
                    """,
                    StandardCharsets.UTF_8);
            waitFor(() -> "bonjour".equals(seen.get()));
            assertThat(greetings).hasValue(2);

            // removing the entry from the config disposes the plugin
            Files.writeString(config, "[]", StandardCharsets.UTF_8);
            waitFor(() -> loader.expectFiber("greeter") == null);
            assertThat(greetings).hasValue(2);
        } finally {
            hmr.stop();
        }
    }

    @Test
    void jarReplacement_hotSwapsPlugin() throws Exception {
        Path pluginsDir = tempDir.resolve("plugins");
        Files.createDirectories(pluginsDir);
        Path jar = pluginsDir.resolve("demo-plugin.jar");
        buildJar(jar, V1);

        Context root = Context.create();
        Loader loader = new Loader(root);
        JarWatcher watcher = new JarWatcher(loader, pluginsDir);
        watcher.start();
        try {
            waitFor(() -> loader.modules.containsKey("demo-plugin"));
            io.jcordis.loader.EntryOptions options = new io.jcordis.loader.EntryOptions();
            options.name = "demo-plugin";
            loader.create(options, null);
            waitFor(() -> "1".equals(System.getProperty(PROBE)));

            // replace the jar: the plugin is swapped and the entry reloaded
            buildJar(jar, V2);
            waitFor(() -> "2".equals(System.getProperty(PROBE)));
        } finally {
            System.clearProperty(PROBE);
            watcher.stop();
            loader.unload("demo-plugin");
        }
    }

    /** Packs fixture classes (from test-fixtures-classes) plus an SPI manifest into a jar. */
    private static void buildJar(Path jar, String... serviceClasses) throws IOException {
        Path fixtureClasses = Path.of("target", "test-fixtures-classes");
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
    }
}
