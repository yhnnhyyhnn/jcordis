package io.jcordis.loader;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;

/**
 * End-to-end plugin jar isolation: fixture classes live only inside the jar
 * (compiled to {@code target/test-fixtures-classes}, off the host classpath),
 * so these tests prove real class loading and unloading.
 */
class PluginIsolationTest {

    private static final String FIXTURE_CLASS = "io.jcordis.fixture.IsolatedPlugin";
    private static final String PROBE = "jcordis.probe.loader";

    @Test
    void pluginClass_shouldBeLoadedByPluginClassLoader() throws Exception {
        Path jar = buildJar("iso-plugin.jar", FIXTURE_CLASS);
        Context root = Context.create();
        Loader loader = new Loader(root);
        try {
            loader.loadJar(jar, "iso");
            EntryOptions options = new EntryOptions();
            options.name = "iso";
            loader.create(options, null);

            String loaderName = System.getProperty(PROBE);
            assertThat(loaderName).isNotNull().isEqualTo(PluginClassLoader.class.getName());
            assertThat(loaderName).isNotEqualTo(getClass().getClassLoader().getClass().getName());
        } finally {
            System.clearProperty(PROBE);
            loader.unload("iso");
        }
    }

    @Test
    void unload_shouldUnloadPluginClasses() throws Exception {
        WeakReference<Class<?>> ref = loadAndUnload();
        assertGc(ref);
    }

    /** Loads and unloads in a separate frame so no local strong reference survives. */
    private static WeakReference<Class<?>> loadAndUnload() throws Exception {
        Path jar = buildJar("iso-plugin.jar", FIXTURE_CLASS);
        Loader loader = new Loader(Context.create());
        loader.loadJar(jar, "iso");
        PluginClassLoader classLoader = loader.classLoaders.get("iso");
        Class<?> clazz = classLoader.loadClass(FIXTURE_CLASS);
        WeakReference<Class<?>> ref = new WeakReference<>(clazz);
        loader.unload("iso");
        return ref;
    }

    @Test
    void twoPlugins_shouldHaveDistinctClassLoaders() throws Exception {
        Path jarA = buildJar("a-plugin.jar", FIXTURE_CLASS);
        Path jarB = buildJar("b-plugin.jar", FIXTURE_CLASS);
        Loader loader = new Loader(Context.create());
        try {
            loader.loadJar(jarA, "a");
            loader.loadJar(jarB, "b");

            assertThat(loader.classLoaders.get("a")).isNotNull().isNotSameAs(loader.classLoaders.get("b"));
        } finally {
            loader.unload("a");
            loader.unload("b");
        }
    }

    private static void assertGc(WeakReference<Class<?>> ref) throws InterruptedException {
        for (int i = 0; i < 50 && ref.get() != null; i++) {
            System.gc();
            Thread.sleep(20);
        }
        assertThat(ref.get()).isNull();
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
