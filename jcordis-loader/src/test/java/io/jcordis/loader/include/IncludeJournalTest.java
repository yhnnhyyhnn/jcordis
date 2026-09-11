package io.jcordis.loader.include;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end coverage for the two-way sync between the config file and the
 * loader tree (mirrors Cordis's {@code Include.commit(EntryChange)} + journal):
 * runtime changes reach the file, file edits reach the tree, and a conflict is
 * resolved in favor of the file.
 */
class IncludeJournalTest {

    @TempDir
    Path tempDir;

    private Include include(Loader loader, Path config) {
        Include include = new Include(loader.ctx(), Map.of("path", config.toString()));
        include.apply(loader.ctx(), Map.of("path", config.toString()));
        return include;
    }

    @Test
    void runtimeUpdateShouldBeWrittenBackToTheFile() throws IOException {
        Path config = tempDir.resolve("app.yml");
        Files.writeString(
                config, "- id: feature\n  name: feature-plugin\n  config:\n    n: 1\n", StandardCharsets.UTF_8);
        Loader loader = new Loader(Context.create());
        loader.mock("feature-plugin", (ctx, cfg) -> null);
        Include include = include(loader, config);
        assertThat(Files.readString(config)).contains("n: 1");

        EntryOptions next = new EntryOptions();
        next.config = Map.of("n", 2);
        loader.update("feature", next, null);
        include.flush();

        assertThat(Files.readString(config))
                .as("runtime config change persisted")
                .contains("\"n\": 2");
    }

    @Test
    void runtimeDisableShouldBeWrittenBackToTheFile() throws IOException {
        Path config = tempDir.resolve("app.yml");
        Files.writeString(config, "- id: feature\n  name: feature-plugin\n", StandardCharsets.UTF_8);
        Loader loader = new Loader(Context.create());
        loader.mock("feature-plugin", (ctx, cfg) -> null);
        Include include = include(loader, config);

        EntryOptions next = new EntryOptions();
        next.disabled = true;
        loader.update("feature", next, null);
        include.flush();

        assertThat(Files.readString(config)).as("runtime disable persisted").contains("disabled: true");
    }

    @Test
    void refreshWithUnchangedFileShouldNotRewriteIt() throws IOException {
        Path config = tempDir.resolve("app.yml");
        Files.writeString(
                config, "- id: feature\n  name: feature-plugin\n  config:\n    n: 1\n", StandardCharsets.UTF_8);
        Loader loader = new Loader(Context.create());
        loader.mock("feature-plugin", (ctx, cfg) -> null);
        Include include = include(loader, config);
        String before = Files.readString(config);

        include.refresh();
        include.flush();

        assertThat(Files.readString(config)).as("idempotent").isEqualTo(before);
        assertThat(include.pendingChanges()).isZero();
    }

    @Test
    void editingAFileShouldNotRestartAnonymousEntries() throws IOException, InterruptedException {
        Path config = tempDir.resolve("anon.yml");
        Files.writeString(
                config,
                "- name: anon-plugin\n  config:\n    n: 1\n- id: named\n  name: anon-plugin\n  config:\n    n: 1\n",
                StandardCharsets.UTF_8);
        Loader loader = new Loader(Context.create());
        List<Object> loads = new ArrayList<>();
        loader.mock("anon-plugin", (ctx, cfg) -> {
            loads.add(cfg);
            return null;
        });
        Include include = include(loader, config);
        assertThat(loads).hasSize(2);

        // edit the named entry only: the anonymous one keeps its assigned id
        Files.writeString(
                config,
                "- name: anon-plugin\n  config:\n    n: 1\n- id: named\n  name: anon-plugin\n  config:\n    n: 2\n",
                StandardCharsets.UTF_8);
        include.refresh();
        include.flush();

        assertThat(loads).as("anonymous entry was not restarted").hasSize(3);
    }

    @Test
    void entriesNotOwnedByTheFileShouldNotBeWrittenBack() throws IOException {
        Path config = tempDir.resolve("app.yml");
        Files.writeString(config, "- id: feature\n  name: feature-plugin\n", StandardCharsets.UTF_8);
        Loader loader = new Loader(Context.create());
        loader.mock("feature-plugin", (ctx, cfg) -> null);
        Include include = include(loader, config);

        EntryOptions extra = new EntryOptions();
        extra.id = "extra";
        extra.name = "feature-plugin";
        loader.create(extra, null);
        include.flush();

        assertThat(Files.readString(config))
                .as("foreign entry is not persisted")
                .doesNotContain("extra");
    }

    @Test
    void patchOwnedEntriesShouldNotBeWrittenBack() throws IOException {
        Path config = tempDir.resolve("app.yml");
        Files.writeString(
                config, "- id: feature\n  name: feature-plugin\n  config:\n    n: 1\n", StandardCharsets.UTF_8);
        Loader loader = new Loader(Context.create());
        loader.mock("feature-plugin", (ctx, cfg) -> null);
        Include include = new Include(
                loader.ctx(),
                Map.of(
                        "path",
                        config.toString(),
                        "patches",
                        List.of(Map.of("id", "feature", "config", Map.of("n", 9)))));
        include.apply(loader.ctx(), Map.of("path", config.toString()));

        EntryOptions next = new EntryOptions();
        next.config = Map.of("n", 5);
        loader.update("feature", next, null);
        include.flush();

        // the value comes from the patch, so the change belongs to the patch
        assertThat(Files.readString(config)).as("patch-owned entry untouched").contains("n: 1");
    }
}
