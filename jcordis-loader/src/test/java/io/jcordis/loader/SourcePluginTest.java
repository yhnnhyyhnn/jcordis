package io.jcordis.loader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jcordis.core.context.Context;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Dynamic source compilation and hot swap (the JDK-backed counterpart of the
 * jar path, mirroring Cordis's dynamic ESM import): a source directory
 * compiles into a throwaway class loader of its own, recompiling swaps every
 * entry using the plugin, and a broken tree never touches what is running.
 */
class SourcePluginTest {

    @TempDir
    Path tempDir;

    private static final String SOURCE_V1 =
            """
            package demo;

            import io.jcordis.core.registry.Plugin;

            public class DemoPlugin implements Plugin {
                @Override
                public Object apply(io.jcordis.core.context.Context ctx, Object config) {
                    System.setProperty("jcordis.src.probe", "v1");
                    return null;
                }
            }
            """;

    private static final String SOURCE_V2 =
            """
            package demo;

            import io.jcordis.core.registry.Plugin;

            public class DemoPlugin implements Plugin {
                @Override
                public Object apply(io.jcordis.core.context.Context ctx, Object config) {
                    System.setProperty("jcordis.src.probe", "v2");
                    return null;
                }
            }
            """;

    private Path sourcesOf(String body) throws IOException {
        Path dir = Files.createTempDirectory(tempDir, "src");
        Files.createDirectories(dir.resolve("demo"));
        Files.writeString(dir.resolve("demo/DemoPlugin.java"), body, StandardCharsets.UTF_8);
        return dir;
    }

    private static EntryOptions entry(String id, String name) {
        EntryOptions options = new EntryOptions();
        options.id = id;
        options.name = name;
        return options;
    }

    @Test
    void compiledSourceLoadsAndRuns() throws IOException {
        Loader loader = new Loader(Context.create());
        loader.loadSource(sourcesOf(SOURCE_V1), "demo");

        loader.read(List.of(entry("a", "demo")));

        assertThat(System.getProperty("jcordis.src.probe")).isEqualTo("v1");
        assertThat(loader.expectFiber("a")).isNotNull();
        System.clearProperty("jcordis.src.probe");
    }

    @Test
    void replaceSourceHotSwapsTheRunningBehavior() throws IOException {
        Loader loader = new Loader(Context.create());
        loader.loadSource(sourcesOf(SOURCE_V1), "demo");
        loader.read(List.of(entry("a", "demo")));
        assertThat(System.getProperty("jcordis.src.probe")).isEqualTo("v1");
        System.clearProperty("jcordis.src.probe");

        loader.replaceSource(sourcesOf(SOURCE_V2), "demo");

        assertThat(System.getProperty("jcordis.src.probe"))
                .as("the entry re-ran with the recompiled plugin")
                .isEqualTo("v2");
        assertThat(loader.expectFiber("a")).isNotNull();
        System.clearProperty("jcordis.src.probe");
    }

    @Test
    void brokenSourcesLeaveTheRunningPluginUntouched() throws IOException {
        Loader loader = new Loader(Context.create());
        loader.loadSource(sourcesOf(SOURCE_V1), "demo");
        loader.read(List.of(entry("a", "demo")));

        Path broken = Files.createTempDirectory(tempDir, "broken");
        Files.writeString(
                broken.resolve("Broken.java"),
                "package demo; public class Broken { this is not java",
                StandardCharsets.UTF_8);
        assertThatThrownBy(() -> loader.replaceSource(broken, "demo"))
                .isInstanceOf(SourceCompiler.CompilationException.class)
                .hasMessageContaining("compilation failed");

        // stage 1 failed before anything was touched: the fiber is still the v1 one
        assertThat(loader.expectFiber("a")).isNotNull();
        loader.resolve("a").fiber.update(java.util.Map.of(), true);
        assertThat(System.getProperty("jcordis.src.probe")).isEqualTo("v1");
        System.clearProperty("jcordis.src.probe");
    }

    @Test
    void sourceWithoutAPluginImplementationFailsClearly() throws IOException {
        Loader loader = new Loader(Context.create());
        Path dir = Files.createTempDirectory(tempDir, "util");
        Files.writeString(
                dir.resolve("Helper.java"),
                "package demo; public class Helper { public static int square(int x) { return x * x; } }",
                StandardCharsets.UTF_8);

        assertThatThrownBy(() -> loader.loadSource(dir, "helper"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no Plugin implementation");
    }

    @Test
    void multipleImplementationsRequireAnSpiDeclaration() throws IOException {
        Loader loader = new Loader(Context.create());
        Path dir = Files.createTempDirectory(tempDir, "multi");
        Files.createDirectories(dir.resolve("demo"));
        Files.writeString(dir.resolve("demo/First.java"), pluginSource("First"), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("demo/Second.java"), pluginSource("Second"), StandardCharsets.UTF_8);

        assertThatThrownBy(() -> loader.loadSource(dir, "multi"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("multiple Plugin implementations");

        // an explicit declaration picks one
        Files.createDirectories(dir.resolve("META-INF/services"));
        Files.writeString(
                dir.resolve("META-INF/services/io.jcordis.core.registry.Plugin"), "demo.First", StandardCharsets.UTF_8);
        loader.loadSource(dir, "multi");
        loader.read(List.of(entry("m", "multi")));
        assertThat(System.getProperty("jcordis.src.probe")).isEqualTo("First");
        System.clearProperty("jcordis.src.probe");
    }

    @Test
    void missingSourceDirectoryFailsClearly() {
        Loader loader = new Loader(Context.create());
        assertThatThrownBy(() -> loader.loadSource(tempDir.resolve("nope"), "ghost"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source directory not found");
    }

    private static String pluginSource(String name) {
        return """
                package demo;

                import io.jcordis.core.registry.Plugin;

                public class %s implements Plugin {
                    @Override
                    public Object apply(io.jcordis.core.context.Context ctx, Object config) {
                        System.setProperty("jcordis.src.probe", "%s");
                        return null;
                    }
                }
                """
                .formatted(name, name);
    }
}
