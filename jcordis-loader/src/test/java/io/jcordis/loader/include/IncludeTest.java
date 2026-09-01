package io.jcordis.loader.include;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import io.jcordis.loader.EntryOptions;
import io.jcordis.loader.Loader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Translates Cordis include patch.spec.ts core semantics: loading and patching. */
class IncludeTest {

    @TempDir
    Path tempDir;

    private Path writeConfig(String content) throws IOException {
        Path path = tempDir.resolve("config.yml");
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }

    @Test
    void loadsWithoutPatches() throws IOException {
        Path path = writeConfig(
                """
                - id: test
                  name: test-plugin
                  config:
                    value: default
                """);
        Context root = Context.create();
        Loader loader = new Loader(root);
        AtomicInteger calls = new AtomicInteger();
        loader.mock("@cordisjs/plugin-include", (ctx, config) -> {
            Include include = new Include(ctx, (java.util.Map<String, Object>) config);
            return include.apply(ctx, config);
        });
        loader.mock("test-plugin", (ctx, config) -> {
            calls.incrementAndGet();
            assertThat(config).isInstanceOf(Map.class);
            return null;
        });
        loader.read(List.of(entry("inc", "@cordisjs/plugin-include", Map.of("path", path.toString()))));

        assertThat(calls).hasValue(1);
    }

    @Test
    void disablesEntryViaPatch() throws IOException {
        Path path = writeConfig("""
                - id: test
                  name: test-plugin
                """);
        Context root = Context.create();
        Loader loader = new Loader(root);
        AtomicInteger calls = new AtomicInteger();
        loader.mock("@cordisjs/plugin-include", (ctx, config) -> {
            Include include = new Include(ctx, (java.util.Map<String, Object>) config);
            return include.apply(ctx, config);
        });
        loader.mock("test-plugin", (ctx, config) -> {
            calls.incrementAndGet();
            return null;
        });
        Map<String, Object> patch = new java.util.HashMap<>();
        patch.put("id", "test");
        patch.put("disabled", true);
        loader.read(List.of(
                entry("inc", "@cordisjs/plugin-include", Map.of("path", path.toString(), "patches", List.of(patch)))));

        assertThat(calls).hasValue(0);
    }

    @Test
    void insertsEntries() throws IOException {
        Path path = writeConfig("""
                - id: test
                  name: test-plugin
                """);
        Context root = Context.create();
        Loader loader = new Loader(root);
        AtomicInteger testCalls = new AtomicInteger();
        AtomicInteger extraCalls = new AtomicInteger();
        loader.mock("@cordisjs/plugin-include", (ctx, config) -> {
            Include include = new Include(ctx, (java.util.Map<String, Object>) config);
            return include.apply(ctx, config);
        });
        loader.mock("test-plugin", (ctx, config) -> {
            testCalls.incrementAndGet();
            return null;
        });
        loader.mock("extra-plugin", (ctx, config) -> {
            extraCalls.incrementAndGet();
            return null;
        });
        Map<String, Object> patch = new java.util.HashMap<>();
        patch.put("insert", List.of(entry(null, "extra-plugin", null)));
        loader.read(List.of(
                entry("inc", "@cordisjs/plugin-include", Map.of("path", path.toString(), "patches", List.of(patch)))));

        assertThat(testCalls).hasValue(1);
        assertThat(extraCalls).hasValue(1);
    }

    @Test
    void overridesConfigViaPatch() throws IOException {
        Path path = writeConfig(
                """
                - id: test
                  name: test-plugin
                  config:
                    value: default
                """);
        Context root = Context.create();
        Loader loader = new Loader(root);
        AtomicInteger calls = new AtomicInteger();
        loader.mock("@cordisjs/plugin-include", (ctx, config) -> {
            Include include = new Include(ctx, (java.util.Map<String, Object>) config);
            return include.apply(ctx, config);
        });
        loader.mock("test-plugin", (ctx, config) -> {
            calls.incrementAndGet();
            assertThat(config).isInstanceOf(Map.class);
            return null;
        });
        Map<String, Object> patch = new java.util.HashMap<>();
        patch.put("id", "test");
        patch.put("config", Map.of("custom", true));
        loader.read(List.of(
                entry("inc", "@cordisjs/plugin-include", Map.of("path", path.toString(), "patches", List.of(patch)))));

        assertThat(calls).hasValue(1);
    }

    @Test
    void configParserStrategy() throws IOException {
        assertThat(ConfigParser.forPath("app.yml")).isSameAs(ConfigParser.YAML);
        assertThat(ConfigParser.forPath("app.yaml")).isSameAs(ConfigParser.YAML);
        assertThat(ConfigParser.forPath("app.json")).isSameAs(ConfigParser.JSON);
        assertThat(ConfigParser.forPath("app.txt")).isSameAs(ConfigParser.JSON);

        io.jcordis.loader.EntryOptions options =
                new io.jcordis.loader.EntryOptions.Builder().id("x").name("n").build();
        String yaml = ConfigParser.YAML.write(java.util.List.of(options));
        assertThat(yaml).contains("x").contains("n");

        java.util.List<io.jcordis.loader.EntryOptions> parsed = ConfigParser.forPath("app.yml")
                .read("""
                - id: y
                  name: plugin
                """);
        assertThat(parsed).hasSize(1);
        assertThat(parsed.get(0).id).isEqualTo("y");
        assertThat(parsed.get(0).name).isEqualTo("plugin");
    }

    private static EntryOptions entry(String id, String name, Object config) {
        EntryOptions options = new EntryOptions();
        options.id = id;
        options.name = name;
        options.config = config;
        return options;
    }
}
