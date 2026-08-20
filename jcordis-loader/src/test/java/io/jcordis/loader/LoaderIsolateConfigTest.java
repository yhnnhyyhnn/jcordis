package io.jcordis.loader;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Translates Cordis loader isolate config: entry-level realm isolation via options.isolate. */
class LoaderIsolateConfigTest {

    private static EntryOptions entry(String name) {
        EntryOptions options = new EntryOptions();
        options.name = name;
        return options;
    }

    @Test
    void localIsolation_shouldHideServicesBetweenEntries() {
        Context root = Context.create();
        Loader loader = new Loader(root);
        AtomicReference<Object> bSees = new AtomicReference<>("sentinel");
        loader.mock("a", (ctx, config) -> {
            ctx.provide("db", "A");
            return null;
        });
        loader.mock("b", (ctx, config) -> {
            bSees.set(ctx.get("db"));
            return null;
        });

        EntryOptions a = entry("a");
        a.isolate = Map.of("db", true);
        loader.create(a, null);
        EntryOptions b = entry("b");
        b.isolate = Map.of("db", true);
        loader.create(b, null);

        // b sits in its own local realm: a's 'db' is invisible
        assertThat(bSees.get()).isNull();
    }

    @Test
    void sharedLabel_shouldShareServicesBetweenEntries() {
        Context root = Context.create();
        Loader loader = new Loader(root);
        AtomicReference<Object> bSees = new AtomicReference<>("sentinel");
        loader.mock("a", (ctx, config) -> {
            ctx.provide("db", "A");
            return null;
        });
        loader.mock("b", (ctx, config) -> {
            bSees.set(ctx.get("db"));
            return null;
        });

        EntryOptions a = entry("a");
        a.isolate = Map.of("db", "shared");
        loader.create(a, null);
        EntryOptions b = entry("b");
        b.isolate = Map.of("db", "shared");
        loader.create(b, null);

        // both entries share the @shared global realm: b sees a's 'db'
        assertThat(bSees.get()).isEqualTo("A");
    }

    @Test
    void differentLabels_shouldStayIsolated() {
        Context root = Context.create();
        Loader loader = new Loader(root);
        AtomicReference<Object> bSees = new AtomicReference<>("sentinel");
        loader.mock("a", (ctx, config) -> {
            ctx.provide("db", "A");
            return null;
        });
        loader.mock("b", (ctx, config) -> {
            bSees.set(ctx.get("db"));
            return null;
        });

        EntryOptions a = entry("a");
        a.isolate = Map.of("db", "one");
        loader.create(a, null);
        EntryOptions b = entry("b");
        b.isolate = Map.of("db", "two");
        loader.create(b, null);

        // distinct global realms stay isolated
        assertThat(bSees.get()).isNull();
    }
}
