package io.jcordis.loader;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end expression support in entry options (the safe counterpart of
 * Cordis's evaluated options): {@code ${...}} placeholders expand once, when
 * the options enter the tree, against the loader's evaluation scope — env and
 * prop by default, replaceable for embedding.
 */
class OptionsEvalTest {

    @TempDir
    Path tempDir;

    private static EntryOptions entry(String id, String name) {
        EntryOptions options = new EntryOptions();
        options.id = id;
        options.name = name;
        return options;
    }

    @Test
    void disabledExpressionEvaluatesAgainstTheScope() {
        Context root = Context.create();
        Loader loader = new Loader(root);
        loader.evalScope(Map.of("env", Map.of("OFF", "true"), "prop", Map.of()));
        AtomicInteger calls = new AtomicInteger();
        loader.mock("demo", (ctx, cfg) -> calls.incrementAndGet());

        EntryOptions options = entry("a", "demo");
        options.disabled = "${env.OFF == 'true'}";
        loader.create(options, null);

        assertThat(calls)
                .as("the expression evaluated to true: the entry never loads")
                .hasValue(0);
        assertThat(loader.resolve("a").options.disabled).isEqualTo(true);
    }

    @Test
    void configValuesInterpolate() {
        Context root = Context.create();
        Loader loader = new Loader(root);
        loader.evalScope(Map.of("env", Map.of("NAME", "prod"), "prop", Map.of()));
        java.util.List<Object> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        loader.mock("demo", (ctx, cfg) -> {
            seen.add(cfg);
            return null;
        });

        EntryOptions options = entry("a", "demo");
        options.config = Map.of("greeting", "hello ${env.NAME}", "whole", "${1 + 2}");
        loader.create(options, null);

        assertThat(seen).containsExactly(Map.of("greeting", "hello prod", "whole", 3.0));
    }

    @Test
    void falseExpressionsKeepTheEntryLoaded() {
        Context root = Context.create();
        Loader loader = new Loader(root);
        loader.evalScope(Map.of("env", Map.of("OFF", "false"), "prop", Map.of()));
        AtomicInteger calls = new AtomicInteger();
        loader.mock("demo", (ctx, cfg) -> calls.incrementAndGet());

        EntryOptions options = entry("a", "demo");
        options.disabled = "${env.OFF == 'true'}";
        loader.create(options, null);

        assertThat(calls).hasValue(1);
        assertThat(loader.resolve("a").options.disabled).isEqualTo(false);
    }

    @Test
    void defaultScopeExposesEnvAndProp() throws IOException {
        // the default scope covers the real environment; a system property
        // set for the test proves the prop side resolves
        System.setProperty("jcordis.expr.test", "on");
        try {
            Context root = Context.create();
            Loader loader = new Loader(root);
            java.util.List<Object> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
            loader.mock("demo", (ctx, cfg) -> {
                seen.add(cfg);
                return null;
            });

            EntryOptions options = entry("a", "demo");
            options.config = "prop:${prop['jcordis.expr.test']}";
            loader.create(options, null);

            assertThat(seen).containsExactly("prop:on");
        } finally {
            System.clearProperty("jcordis.expr.test");
        }
    }

    @Test
    void yamlConfigInterpolatesThroughInclude() throws IOException {
        Path config = tempDir.resolve("app.yml");
        Files.writeString(
                config,
                """
                - id: feature
                  name: feature-plugin
                  config:
                    zone: '${prop.user.zone == null ? \"default\" : prop.user.zone}'
                """,
                StandardCharsets.UTF_8);
        Context root = Context.create();
        Loader loader = new Loader(root);
        java.util.List<Object> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        loader.mock("@cordisjs/plugin-include", (ctx, cfg) -> {
            io.jcordis.loader.include.Include include = new io.jcordis.loader.include.Include(ctx, cast(cfg));
            return include.apply(ctx, cast(cfg));
        });
        loader.mock("feature-plugin", (ctx, cfg) -> {
            seen.add(cfg);
            return null;
        });

        loader.read(List.of(entry("inc", "@cordisjs/plugin-include", Map.of("path", config.toString()))));
        loader.await();

        assertThat(seen).containsExactly(Map.of("zone", "default"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }

    private static EntryOptions entry(String id, String name, Object config) {
        EntryOptions options = entry(id, name);
        options.config = config;
        return options;
    }
}
