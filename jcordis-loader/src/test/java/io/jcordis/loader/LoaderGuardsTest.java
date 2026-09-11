package io.jcordis.loader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jcordis.core.context.Context;
import io.jcordis.core.fiber.Fiber;
import io.jcordis.core.registry.Plugin;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;

/**
 * Boundary coverage for the loader's public surface: unknown names, re-loads,
 * entry resolution failures and location lookups.
 */
class LoaderGuardsTest {

    private static final String COUNTING = "io.jcordis.fixture.CountingPlugin";

    private static EntryOptions entry(String id, String name) {
        EntryOptions options = new EntryOptions();
        options.id = id;
        options.name = name;
        return options;
    }

    @Test
    void importPluginShouldResolveBuiltinsAndNamespacedNames() {
        Loader loader = new Loader(Context.create());
        loader.builtin("demo", (ctx, cfg) -> null);

        assertThat(loader.importPlugin("demo")).isNotNull();
        assertThat(loader.importPlugin("cordis:@cordisjs/plugin-group")).isNotNull();
        assertThat(loader.importPlugin("missing"))
                .as("unknown names resolve to nothing")
                .isNull();
    }

    @Test
    void expectFiberShouldReturnNullForUnknownIds() {
        Loader loader = new Loader(Context.create());

        assertThat(loader.expectFiber("nope")).isNull();
    }

    @Test
    void unloadShouldTolerateUnknownNames() {
        Loader loader = new Loader(Context.create());

        loader.unload("never-loaded");

        assertThat(loader.modules).doesNotContainKey("never-loaded");
    }

    @Test
    void loadJarTwiceShouldReplaceTheRegistration() throws IOException {
        Path jar = buildJar("twice.jar", COUNTING);
        Loader loader = new Loader(Context.create());

        Plugin first = loader.loadJar(jar, "counting");
        Plugin second = loader.loadJar(jar, "counting");

        assertThat(second).isNotNull().isNotSameAs(first);
        assertThat(loader.modules).containsKey("counting");
    }

    @Test
    void replaceJarShouldWorkWhenNothingWasRegisteredBefore() throws IOException {
        Path jar = buildJar("fresh.jar", COUNTING);
        Loader loader = new Loader(Context.create());

        Plugin plugin = loader.replaceJar(jar, "fresh");

        assertThat(plugin).isNotNull();
        assertThat(loader.modules).containsKey("fresh");
    }

    @Test
    void discoverShouldFailForAJarWithoutAPlugin() throws IOException {
        Path empty = buildJar("empty.jar");
        Loader loader = new Loader(Context.create());

        assertThatThrownBy(() -> loader.loadJar(empty, "empty")).isInstanceOf(RuntimeException.class);
        assertThat(loader.modules).doesNotContainKey("empty");
    }

    @Test
    void resolveShouldRejectUnknownEntriesAndNonGroups() {
        Loader loader = new Loader(Context.create());
        loader.builtin("demo", (ctx, cfg) -> null);
        loader.create(entry("a", "demo"), null);
        loader.create(entry("plain", "demo"), null);

        assertThatThrownBy(() -> loader.resolve("missing")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> loader.resolveGroup("plain"))
                .as("only group entries own subgroups")
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(loader.resolveGroup(null))
                .as("null resolves to the root group")
                .isSameAs(loader.root);
    }

    @Test
    void locateShouldWalkUpToTheOwningEntry() {
        Loader loader = new Loader(Context.create());
        loader.builtin("demo", (ctx, cfg) -> null);
        String id = loader.create(entry("a", "demo"), null);

        Fiber fiber = loader.expectFiber(id);
        assertThat(fiber).isNotNull();
        assertThat(loader.locate(fiber)).isEqualTo("a");
        assertThat(loader.locate(null))
                .as("null falls back to the calling fiber")
                .isNull();
    }

    @Test
    void subtreeEntriesShouldReportTheirNestedId() {
        Loader loader = new Loader(Context.create());
        loader.builtin("demo", (ctx, cfg) -> null);
        loader.builtin("group-plug", Loader.GROUP_PLUGIN);

        EntryOptions group = entry("g", "group-plug");
        group.group = true;
        group.config = new ArrayList<>(List.of(entry("child", "demo")));
        loader.create(group, null);

        assertThat(loader.resolve("g:child").fiber).isNotNull();
        assertThat(loader.locate(loader.resolve("g:child").fiber))
                .as("nested entries carry their group prefix")
                .isEqualTo("g:child");
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
