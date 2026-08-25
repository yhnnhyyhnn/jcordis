package io.jcordis.loader;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import io.jcordis.core.fiber.Fiber;
import io.jcordis.core.fiber.FiberState;
import io.jcordis.core.registry.Plugin;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;

/**
 * Translates Cordis loader jar loading: SPI discovery scoped to the jar's own
 * class loader, runtime registration, unload.
 */
class PluginJarTest {

    private static final String SAMPLE = "io.jcordis.fixture.SamplePlugin";
    private static final String ISOLATED = "io.jcordis.fixture.IsolatedPlugin";

    @Test
    void hostClasspathPlugin_shouldNotShadowUploadedJar() throws Exception {
        // simulate a plugin jar (demo-plugin) on the host classpath: its SPI
        // manifest is reachable through the parent class loader
        Path serviceDir = Paths.get("target", "test-classes", "META-INF", "services");
        Files.createDirectories(serviceDir);
        Path serviceFile = serviceDir.resolve("io.jcordis.core.registry.Plugin");
        Files.writeString(serviceFile, "io.jcordis.loader.fixture.SamplePlugin\n", StandardCharsets.UTF_8);
        try {
            // an uploaded jar declares its own plugin — it must win over the host one
            Path jar = buildJar("uploaded-plugin.jar", ISOLATED);
            Loader loader = new Loader(Context.create());
            Plugin plugin = loader.loadJar(jar, "uploaded");

            assertThat(plugin).isNotNull();
            assertThat(plugin.getClass().getName()).isEqualTo(ISOLATED);
            loader.unload("uploaded");
        } finally {
            Files.deleteIfExists(serviceFile);
        }
    }

    @Test
    void loadJar_shouldDiscoverAndRunPlugin() throws Exception {
        Context root = Context.create();
        Loader loader = new Loader(root);
        Path jar = buildJar("sample-plugin.jar", SAMPLE);

        Plugin plugin = loader.loadJar(jar, "sample-plugin");
        assertThat(plugin).isNotNull();
        assertThat(plugin.getClass().getName()).isEqualTo(SAMPLE);

        System.clearProperty("jcordis.probe.sample");
        EntryOptions options = new EntryOptions();
        options.name = "sample-plugin";
        String id = loader.create(options, null);
        Fiber fiber = loader.expectFiber(id);
        assertThat(fiber).isNotNull();
        assertThat(fiber.state()).isEqualTo(FiberState.ACTIVE);
        assertThat(System.getProperty("jcordis.probe.sample")).isEqualTo("loaded");
        loader.unload("sample-plugin");
    }

    @Test
    void unload_shouldDisposeFibersAndReleaseJar() throws Exception {
        Context root = Context.create();
        Loader loader = new Loader(root);
        Path jar = buildJar("sample-plugin.jar", SAMPLE);
        loader.loadJar(jar, "sample-plugin");
        EntryOptions options = new EntryOptions();
        options.name = "sample-plugin";
        String id = loader.create(options, null);
        assertThat(loader.expectFiber(id)).isNotNull();

        loader.unload("sample-plugin");

        assertThat(loader.expectFiber(id)).isNull();
        assertThat(loader.modules).doesNotContainKey("sample-plugin");
        // the class loader closed its jar handle: the file can now be deleted
        Files.delete(jar);
    }

    @Test
    void twoJars_shouldRegisterIndependently() throws Exception {
        Context root = Context.create();
        Loader loader = new Loader(root);
        Path jarA = buildJar("a-plugin.jar", SAMPLE);
        Path jarB = buildJar("b-plugin.jar", SAMPLE);

        loader.loadJar(jarA, "a");
        loader.loadJar(jarB, "b");

        assertThat(loader.modules).containsKeys("a", "b");
        EntryOptions a = new EntryOptions();
        a.name = "a";
        EntryOptions b = new EntryOptions();
        b.name = "b";
        assertThat(loader.expectFiber(loader.create(a, null))).isNotNull();
        assertThat(loader.expectFiber(loader.create(b, null))).isNotNull();
        loader.unload("a");
        loader.unload("b");
    }

    /** Packs fixture classes (from test-fixtures-classes) plus an SPI manifest into a jar. */
    private static Path buildJar(String jarName, String... serviceClasses) throws IOException {
        Path jar = Files.createTempFile(jarName, ".jar");
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
