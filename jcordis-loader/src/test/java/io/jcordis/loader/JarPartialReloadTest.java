package io.jcordis.loader;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Three-stage partial reload for jar hot-swap (mirrors Cordis's
 * {@code partialReload}): validate (all-or-nothing) → unload every matching
 * entry first → reload per entry with isolated failures and a per-fiber drain.
 */
class JarPartialReloadTest {

    private static final String COUNTING = "io.jcordis.fixture.CountingPlugin";
    private static final String FAILING = "io.jcordis.fixture.FailingPlugin";
    private static final String ASYNC = "io.jcordis.fixture.AsyncPlugin";

    @BeforeEach
    void resetProbes() {
        System.clearProperty("jcordis.probe.count");
        System.clearProperty("jcordis.probe.ok");
    }

    private static EntryOptions entry(String id, String name, Object config) {
        EntryOptions options = new EntryOptions();
        options.id = id;
        options.name = name;
        options.config = config;
        return options;
    }

    @Test
    void siblingEntries_shouldAllBeRebuilt() throws Exception {
        Path jar = buildJar("siblings.jar", COUNTING);
        Loader loader = new Loader(Context.create());
        loader.loadJar(jar, "siblings");

        loader.read(List.of(entry("a", "siblings", null), entry("b", "siblings", null)));
        assertThat(System.getProperty("jcordis.probe.count")).isEqualTo("2");

        loader.replaceJar(jar, "siblings");

        assertThat(System.getProperty("jcordis.probe.count"))
                .as("both siblings rebuilt")
                .isEqualTo("4");
        assertThat(loader.expectFiber("a")).isNotNull();
        assertThat(loader.expectFiber("b")).isNotNull();
    }

    @Test
    void validateStageFailure_shouldLeaveRunningEntriesUntouched() throws Exception {
        Path jar = buildJar("valid.jar", COUNTING);
        Loader loader = new Loader(Context.create());
        loader.loadJar(jar, "plugin");

        loader.read(List.of(entry("a", "plugin", null)));
        int before = Integer.parseInt(System.getProperty("jcordis.probe.count", "0"));
        io.jcordis.core.fiber.Fiber fiber = loader.expectFiber("a");
        assertThat(fiber).isNotNull();

        // a jar declaring no discoverable plugin fails stage 1
        Path broken = buildJar("broken.jar");
        try {
            loader.replaceJar(broken, "plugin");
        } catch (RuntimeException expected) {
            // validation failure: nothing must have been touched
        }

        assertThat(Integer.parseInt(System.getProperty("jcordis.probe.count", "0")))
                .as("no rebuild happened")
                .isEqualTo(before);
        assertThat(loader.expectFiber("a")).as("running fiber left untouched").isSameAs(fiber);
        assertThat(loader.modules).containsKey("plugin");
    }

    @Test
    void reloadFailureShouldBeIsolatedToItsEntry() throws Exception {
        Path jar = buildJar("failing.jar", FAILING);
        Loader loader = new Loader(Context.create());
        loader.loadJar(jar, "failing");

        loader.read(List.of(entry("good", "failing", null), entry("bad", "failing", Map.of("fail", true))));
        assertThat(System.getProperty("jcordis.probe.ok"))
                .as("only the good entry loaded")
                .isEqualTo("1");

        loader.replaceJar(jar, "failing");

        // stage 3 has no rollback: the failing entry stays failed, the healthy
        // one is reloaded regardless
        assertThat(System.getProperty("jcordis.probe.ok"))
                .as("healthy entry reloaded")
                .isEqualTo("2");
        assertThat(loader.expectFiber("good")).isNotNull();
    }

    @Test
    void reloadShouldDrainInFlightInitialization() throws Exception {
        Path jar = buildJar("async.jar", ASYNC);
        Loader loader = new Loader(Context.create());
        loader.loadJar(jar, "async");

        // the plugin body is still running (it settles after ~120ms)
        loader.read(List.of(entry("a", "async", null)));
        assertThat(loader.expectFiber("a")).isNotNull();

        // a change arriving mid-initialization drains it instead of racing it
        loader.replaceJar(jar, "async");

        assertThat(loader.expectFiber("a"))
                .as("entry still loaded after the swap")
                .isNotNull();
        assertThat(loader.modules).containsKey("async");
    }

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
