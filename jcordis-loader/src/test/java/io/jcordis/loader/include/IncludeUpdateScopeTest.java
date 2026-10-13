package io.jcordis.loader.include;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import io.jcordis.loader.Entry;
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
 * Probes two registration-scope/ownership defects of the include against its
 * Cordis counterpart: the {@code internal/update} hook must be fiber-local
 * (only the include plugin's own updates pass through it), and entries the
 * config file does not own must survive a file-driven re-apply.
 *
 * <p>Both tests are disabled pending the fixes — they reproduce the defects
 * and turn green once the include registers its hook fiber-locally and the
 * file-driven re-apply stops removing entries it does not own.
 */
class IncludeUpdateScopeTest {

    @TempDir
    Path tempDir;

    private Loader includeOver(Path config, List<Number> applied) throws IOException {
        Files.writeString(config, "- id: holder\n  name: holder-plugin\n", StandardCharsets.UTF_8);
        Context root = Context.create();
        Loader loader = new Loader(root);
        loader.mock("holder-plugin", (ctx, cfg) -> null);
        loader.mock("@cordisjs/plugin-include", (ctx, cfg) -> {
            Include include = new Include(ctx, (Map<String, Object>) cfg);
            return include.apply(ctx, cfg);
        });
        loader.mock("feature-plugin", (ctx, cfg) -> {
            if (cfg instanceof Map<?, ?> map && map.get("n") != null) {
                applied.add((Number) map.get("n"));
            }
            return null;
        });
        loader.read(List.of(entry("inc", "@cordisjs/plugin-include", Map.of("path", config.toString()))));
        return loader;
    }

    @Test
    void unrelatedPluginUpdateWithPathKeyShouldNotBeIntercepted() throws IOException {
        Path config = tempDir.resolve("app.yml");
        List<Number> applied = new ArrayList<>();
        Loader loader = includeOver(config, applied);

        // an entry created programmatically, outside the config file; its
        // config carries a `path` key equal to the include's own file
        loader.create(entry("feature", "feature-plugin", Map.of("path", config.toString(), "n", 1)), null);
        assertThat(applied).containsExactly(1);
        System.out.println("[probe1] store=" + loader.store.keySet() + " hooks="
                + loader.ctx().events().hookCounts());

        // the feature plugin updates its own config, which merely happens to
        // carry a `path` key — Cordis does not intercept this (fiber-local
        // hook); the plugin must reload with n=2
        loader.resolve("feature").fiber.update(Map.of("path", config.toString(), "n", 2), true);
        System.out.println("[probe1] after update applied=" + applied);

        assertThat(applied)
                .as("the unrelated plugin's update must restart its body")
                .containsExactly(1, 2);
    }

    @Test
    void foreignEntriesShouldSurviveAFileDrivenReapply() throws IOException {
        Path config = tempDir.resolve("app.yml");
        List<Number> applied = new ArrayList<>();
        Loader loader = includeOver(config, applied);

        Entry feature = loader.resolve(loader.create(entry("feature", "feature-plugin", Map.of("n", 1)), null));
        assertThat(applied).containsExactly(1);

        // an unrelated touch of the config file triggers the include's
        // re-apply; the file does not own `feature`, so it must survive
        Files.writeString(config, "- id: holder\n  name: holder-plugin\n  config:\n    x: 1\n", StandardCharsets.UTF_8);
        Include include = new Include(loader.ctx(), Map.of("path", config.toString()));
        include.apply(loader.ctx(), Map.of("path", config.toString()));

        assertThat(loader.store.get("feature"))
                .as("entries outside the config file must not be removed by a re-apply")
                .isNotNull();
        assertThat(applied).containsExactly(1);
    }

    private static EntryOptions entry(String id, String name, Object config) {
        EntryOptions options = new EntryOptions();
        options.id = id;
        options.name = name;
        options.config = config;
        return options;
    }
}
