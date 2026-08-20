package io.jcordis.loader;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import io.jcordis.core.fiber.Fiber;
import io.jcordis.core.fiber.FiberState;
import io.jcordis.core.registry.Plugin;
import io.jcordis.loader.fixture.SamplePlugin;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;

/** Translates Cordis loader jar loading: SPI discovery, runtime registration, unload. */
class PluginJarTest {

    @Test
    void loadJar_shouldDiscoverAndRunPlugin() throws Exception {
        Context root = Context.create();
        Loader loader = new Loader(root);
        Path jar = buildJar("sample-plugin.jar", "io.jcordis.loader.fixture.SamplePlugin");

        Plugin plugin = loader.loadJar(jar, "sample-plugin");
        assertThat(plugin).isNotNull();

        SamplePlugin.calls.set(0);
        EntryOptions options = new EntryOptions();
        options.name = "sample-plugin";
        String id = loader.create(options, null);
        Fiber fiber = loader.expectFiber(id);
        assertThat(fiber).isNotNull();
        assertThat(fiber.state()).isEqualTo(FiberState.ACTIVE);
        assertThat(SamplePlugin.calls).hasValue(1);
    }

    @Test
    void unload_shouldDisposeFibersAndReleaseJar() throws Exception {
        Context root = Context.create();
        Loader loader = new Loader(root);
        Path jar = buildJar("sample-plugin.jar", "io.jcordis.loader.fixture.SamplePlugin");
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
        Path jarA = buildJar("a-plugin.jar", "io.jcordis.loader.fixture.SamplePlugin");
        Path jarB = buildJar("b-plugin.jar", "io.jcordis.loader.fixture.SamplePlugin");

        loader.loadJar(jarA, "a");
        loader.loadJar(jarB, "b");

        assertThat(loader.modules).containsKeys("a", "b");
        EntryOptions a = new EntryOptions();
        a.name = "a";
        EntryOptions b = new EntryOptions();
        b.name = "b";
        assertThat(loader.expectFiber(loader.create(a, null))).isNotNull();
        assertThat(loader.expectFiber(loader.create(b, null))).isNotNull();
    }

    /** Packs the given classes (from test-classes) plus an SPI manifest into a jar. */
    private static Path buildJar(String jarName, String... serviceClasses) throws IOException, URISyntaxException {
        Path jar = Files.createTempFile(jarName, ".jar");
        Path testClasses = Paths.get(
                SamplePlugin.class.getProtectionDomain().getCodeSource().getLocation().toURI());
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
