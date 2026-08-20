package io.jcordis.examples.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import io.jcordis.loader.include.Include;
import io.jcordis.loader.EntryOptions;
import io.jcordis.loader.Loader;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Demonstrates YAML config driving the loader via the include plugin. */
class ConfigAppTest {

    @TempDir
    Path tempDir;

    private static EntryOptions entry(String id, String name, Object config) {
        EntryOptions options = new EntryOptions();
        options.id = id;
        options.name = name;
        options.config = config;
        return options;
    }

    @Test
    void yamlConfigLoadsPlugins() throws IOException {
        Path config = tempDir.resolve("app.yml");
        Files.writeString(config, """
                - id: feature-a
                  name: feature-plugin
                  config:
                    name: alpha
                """, StandardCharsets.UTF_8);

        Context root = Context.create();
        Loader loader = new Loader(root);
        AtomicInteger calls = new AtomicInteger();
        loader.mock("@cordisjs/plugin-include", (ctx, cfg) -> {
            Include include = new Include(ctx, (Map<String, Object>) cfg);
            return include.apply(ctx, cfg);
        });
        loader.mock("feature-plugin", (ctx, cfg) -> {
            calls.incrementAndGet();
            assertThat(cfg).isInstanceOf(Map.class);
            return null;
        });

        loader.read(List.of(entry("inc", "@cordisjs/plugin-include", Map.of("path", config.toString()))));

        assertThat(calls).hasValue(1);
    }

    @Test
    void groupConfigSpawnsNestedEntries() {
        Context root = Context.create();
        Loader loader = new Loader(root);
        AtomicInteger calls = new AtomicInteger();
        loader.mock("feature-plugin", (ctx, cfg) -> {
            calls.incrementAndGet();
            return null;
        });

        EntryOptions group = new EntryOptions();
        group.id = "group";
        group.name = "group-plugin";
        group.group = true;
        EntryOptions nested = new EntryOptions();
        nested.name = "feature-plugin";
        nested.config = Map.of("name", "nested");
        group.config = List.of(nested);
        loader.create(group, null);

        assertThat(calls).hasValue(1);
        assertThat(loader.entries()).hasSize(2);
    }
}