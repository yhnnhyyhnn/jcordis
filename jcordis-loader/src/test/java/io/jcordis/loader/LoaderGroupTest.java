package io.jcordis.loader;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Translates Cordis loader group.spec.ts core semantics: group nesting, cascade. */
class LoaderGroupTest {

    private static EntryOptions entry(String name) {
        EntryOptions options = new EntryOptions();
        options.name = name;
        return options;
    }

    private static EntryOptions group(String id, List<EntryOptions> config) {
        EntryOptions options = new EntryOptions();
        options.id = id;
        options.name = "@cordisjs/plugin-group";
        options.group = true;
        options.config = config;
        return options;
    }

    @Test
    void initializeAndCascade() {
        Context root = Context.create();
        Loader loader = new Loader(root);
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger disposes = new AtomicInteger();
        Plugin foo = loader.mock("foo", (ctx, config) -> {
            calls.incrementAndGet();
            return (io.jcordis.core.util.Disposable) disposes::incrementAndGet;
        });

        loader.create(group("outer", List.of(entry("foo"))), null);
        assertThat(calls).hasValue(1);
        assertThat(disposes).hasValue(0);
        assertThat(loader.entries()).hasSize(2);

        // disable outer: nested foo entry is disposed
        EntryOptions disabled = new EntryOptions();
        disabled.disabled = true;
        loader.update("outer", disabled, null);
        assertThat(disposes).hasValue(1);
        assertThat(loader.entries()).hasSize(2);

        // re-enable outer: nested foo reloads
        EntryOptions enabled = new EntryOptions();
        enabled.disabled = false;
        loader.update("outer", enabled, null);
        assertThat(calls).hasValue(2);
        assertThat(disposes).hasValue(1);
    }

    @Test
    void groupIntercept() {
        Context root = Context.create();
        Loader loader = new Loader(root);
        List<Object> captured = new java.util.ArrayList<>();
        loader.mock("foo", (ctx, config) -> {
            captured.add(ctx.interceptConfig("foo"));
            return null;
        });

        EntryOptions options = group("outer", List.of(entry("foo")));
        options.intercept = Map.of("foo", Map.of("a", 1));
        loader.create(options, null);

        assertThat(captured).hasSize(1);
        assertThat(captured.get(0)).isEqualTo(Map.of("a", 1));
    }
}
