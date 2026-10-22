package io.jcordis.loader.include;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jcordis.core.context.Context;
import io.jcordis.loader.EntryOptions;
import io.jcordis.loader.Loader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Boundary coverage for the include plugin: every patch variant, missing and
 * malformed config files, and the anonymous-id assignment rules.
 */
class IncludePatchTest {

    @TempDir
    Path tempDir;

    private Include include(Loader loader, Path config, List<Map<String, Object>> patches) {
        Map<String, Object> cfg = patches.isEmpty()
                ? Map.of("path", config.toString())
                : Map.of("path", config.toString(), "patches", patches);
        Include include = new Include(loader.ctx(), cfg);
        include.apply(loader.ctx(), cfg);
        return include;
    }

    private static Loader loader() {
        Loader loader = new Loader(Context.create());
        loader.mock("feature-plugin", (ctx, cfg) -> null);
        loader.mock("group-plugin", Loader.GROUP_PLUGIN);
        return loader;
    }

    private static String configOf(Loader loader, String id) {
        EntryOptions options = loader.resolve(id).options;
        return options.config instanceof Map<?, ?> map ? String.valueOf(map.get("n")) : "?";
    }

    @Test
    void patchShouldInsertIntoAGroup() throws IOException {
        Path config = tempDir.resolve("app.yml");
        Files.writeString(
                config,
                """
                - id: group
                  name: group-plugin
                  group: true
                  config: []
                """,
                StandardCharsets.UTF_8);
        Loader loader = loader();
        AtomicInteger nested = new AtomicInteger();
        loader.mock("feature-plugin", (ctx, cfg) -> {
            nested.incrementAndGet();
            return null;
        });

        include(
                loader,
                config,
                List.of(Map.of("id", "group", "insert", List.of(Map.of("id", "extra", "name", "feature-plugin")))));

        assertThat(loader.resolve("group:extra").fiber).isNotNull();
        assertThat(nested).hasValue(1);
    }

    @Test
    void patchShouldAppendAtTheTopLevelWithoutAnId() throws IOException {
        Path config = tempDir.resolve("app.yml");
        Files.writeString(config, "- id: base\n  name: feature-plugin\n", StandardCharsets.UTF_8);
        Loader loader = loader();

        include(loader, config, List.of(Map.of("insert", List.of(Map.of("id", "top", "name", "feature-plugin")))));

        assertThat(loader.store).containsKey("top");
    }

    @Test
    void wrongPatchesShouldBeIgnored() throws IOException {
        Path config = tempDir.resolve("app.yml");
        Files.writeString(config, "- id: base\n  name: feature-plugin\n", StandardCharsets.UTF_8);
        Loader loader = loader();

        include(
                loader,
                config,
                List.of(
                        // insert into a missing group
                        Map.of("id", "missing", "insert", List.of(Map.of("id", "x", "name", "feature-plugin"))),
                        // insert into a non-group entry
                        Map.of("id", "base", "insert", List.of(Map.of("id", "y", "name", "feature-plugin"))),
                        // non-insert patch without an id
                        Map.of("config", Map.of("n", 1)),
                        // patch for an unknown entry
                        Map.of("id", "unknown", "config", Map.of("n", 1)),
                        // name mismatch
                        Map.of("id", "base", "name", "other-plugin", "config", Map.of("n", 1))));

        assertThat(loader.store).doesNotContainKeys("x", "y");
        assertThat(configOf(loader, "base")).as("no patch applied").isEqualTo("?");
    }

    @Test
    void patchShouldOverrideEveryPersistableField() throws IOException {
        Path config = tempDir.resolve("app.yml");
        Files.writeString(config, "- id: base\n  name: feature-plugin\n", StandardCharsets.UTF_8);
        Loader loader = loader();

        include(
                loader,
                config,
                List.of(Map.of(
                        "id",
                        "base",
                        "config",
                        Map.of("n", 7),
                        "inject",
                        Map.of("timer", true),
                        "intercept",
                        Map.of("loader", Map.of("await", true)),
                        "isolate",
                        Map.of("db", "realm-a"),
                        // unknown keys must be ignored
                        "unknownKey",
                        "ignored")));

        EntryOptions options = loader.resolve("base").options;
        assertThat(configOf(loader, "base")).isEqualTo("7");
        assertThat(options.inject).containsEntry("timer", true);
        assertThat(options.intercept).containsKey("loader");
        assertThat(options.isolate).containsEntry("db", "realm-a");
    }

    @Test
    void patchShouldBeAbleToDisableAnEntry() throws IOException {
        Path config = tempDir.resolve("app.yml");
        Files.writeString(config, "- id: base\n  name: feature-plugin\n", StandardCharsets.UTF_8);
        Loader loader = loader();
        AtomicInteger loads = new AtomicInteger();
        loader.mock("feature-plugin", (ctx, cfg) -> {
            loads.incrementAndGet();
            return null;
        });

        include(loader, config, List.of(Map.of("id", "base", "disabled", true)));

        assertThat(loads).as("the patched entry is disabled").hasValue(0);
    }

    @Test
    void missingFileWithoutInitialShouldFail() {
        Path config = tempDir.resolve("absent.yml");
        Loader loader = loader();
        Include include = new Include(loader.ctx(), Map.of("path", config.toString()));

        assertThatThrownBy(() -> include.apply(loader.ctx(), Map.of("path", config.toString())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("config file not found");
    }

    @Test
    void missingFileWithInitialShouldBeCreated() throws IOException {
        Path config = tempDir.resolve("created.yml");
        Loader loader = loader();
        Map<String, Object> cfg = Map.of(
                "path",
                config.toString(),
                "initial",
                List.of(new EntryOptions.Builder()
                        .id("base")
                        .name("feature-plugin")
                        .build()));

        Include include = new Include(loader.ctx(), cfg);
        include.apply(loader.ctx(), cfg);

        assertThat(config).exists();
        assertThat(loader.store).containsKey("base");
    }

    @Test
    void malformedFileShouldBeReportedAndThePreviousStateKept() throws IOException {
        Path config = tempDir.resolve("app.yml");
        Files.writeString(config, "- id: base\n  name: feature-plugin\n", StandardCharsets.UTF_8);
        Loader loader = loader();
        Include include = include(loader, config, List.of());

        // a half-written file is routine while editing: the include warns and
        // keeps the last good state instead of tearing the tree down
        Files.writeString(config, "::: not yaml :::", StandardCharsets.UTF_8);
        include.refresh();
        include.flush();

        assertThat(loader.store).containsKey("base");
        assertThat(loader.resolve("base").fiber).isNotNull();
    }

    @Test
    void anonymousEntriesShouldKeepTheirIdWhenUnrelatedFieldsChange() throws IOException {
        Path config = tempDir.resolve("anon.yml");
        Files.writeString(
                config,
                """
                - name: feature-plugin
                  config:
                    n: 1
                - id: named
                  name: feature-plugin
                  config:
                    n: 1
                """,
                StandardCharsets.UTF_8);
        Loader loader = loader();
        List<Object> loads = new ArrayList<>();
        loader.mock("feature-plugin", (ctx, cfg) -> {
            loads.add(cfg);
            return null;
        });
        Include include = include(loader, config, List.of());
        assertThat(loads).hasSize(2);

        // only the named entry is edited: the anonymous one keeps its id
        Files.writeString(
                config,
                """
                - name: feature-plugin
                  config:
                    n: 1
                - id: named
                  name: feature-plugin
                  config:
                    n: 2
                """,
                StandardCharsets.UTF_8);
        include.refresh();
        include.flush();

        assertThat(loads).as("only the edited entry restarted").hasSize(3);
    }

    @Test
    void anonymousEntryInAnotherGroupShouldGetAFreshId() throws IOException {
        Path config = tempDir.resolve("anon.yml");
        Files.writeString(
                config,
                """
                - id: group
                  name: group-plugin
                  group: true
                  config:
                    - name: feature-plugin
                      config:
                        n: 1
                """,
                StandardCharsets.UTF_8);
        Loader loader = loader();
        List<Object> loads = new ArrayList<>();
        loader.mock("feature-plugin", (ctx, cfg) -> {
            loads.add(cfg);
            return null;
        });
        Include include = include(loader, config, List.of());
        assertThat(loads).hasSize(1);

        // the same anonymous entry, now at the top level: a different parent, so
        // it must not silently adopt the previous id
        Files.writeString(
                config,
                """
                - id: group
                  name: group-plugin
                  group: true
                  config: []
                - name: feature-plugin
                  config:
                    n: 1
                """,
                StandardCharsets.UTF_8);
        include.refresh();
        include.flush();

        assertThat(loads).as("the moved anonymous entry loaded again").hasSize(2);
    }

    @Test
    void anonymousIdIsNotReusedOnceExplicitlyOwned() throws IOException {
        Path config = tempDir.resolve("reuse.yml");
        Files.writeString(
                config,
                """
                - name: feature-plugin
                  config:
                    n: 1
                """,
                StandardCharsets.UTF_8);
        Loader loader = loader();
        Include include = include(loader, config, List.of());

        // read the id the anonymous entry received on the first pass
        String assigned = loader.store.values().iterator().next().options.id;
        assertThat(assigned).isNotBlank();

        // the next file revision names another entry with exactly that id;
        // the unchanged anonymous entry must NOT keep it (mirrors Cordis #216:
        // reuse only while the id is not explicitly owned)
        Files.writeString(
                config,
                """
                - id: %s
                  name: feature-plugin
                  config:
                    n: 2
                - name: feature-plugin
                  config:
                    n: 1
                """
                        .formatted(assigned),
                StandardCharsets.UTF_8);
        include.refresh();
        include.flush();

        assertThat(loader.store.keySet())
                .as("two distinct entries: the explicit one and a re-assigned anonymous one")
                .hasSize(2);
        assertThat(loader.resolve(assigned).options.config).isEqualTo(Map.of("n", 2));
        for (String id : loader.store.keySet()) {
            if (!id.equals(assigned)) {
                assertThat(id)
                        .as("the anonymous entry got a fresh id instead of the claimed one")
                        .isNotEqualTo(assigned);
            }
        }
    }
}
