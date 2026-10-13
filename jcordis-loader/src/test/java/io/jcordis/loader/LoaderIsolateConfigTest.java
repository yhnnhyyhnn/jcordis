package io.jcordis.loader;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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

    @Test
    void interceptUpdate_shouldPropagateToRunningFiber() {
        Context root = Context.create();
        Loader loader = new Loader(root);
        AtomicInteger runs = new AtomicInteger();
        AtomicReference<Object> seen = new AtomicReference<>("sentinel");
        loader.mock("svc", (ctx, config) -> {
            runs.incrementAndGet();
            seen.set(ctx.interceptConfig("svc"));
            return null;
        });

        EntryOptions options = entry("a");
        options.id = "a";
        options.name = "svc";
        loader.create(options, null);
        assertThat(seen.get()).isNull();

        // an intercept edit propagates onto the RUNNING fiber's context and
        // re-resolves services — without restarting the body (mirrors Cordis's
        // patch-context swap + notify; only a config change restarts)
        EntryOptions updated = entry("a");
        updated.id = "a";
        updated.name = "svc";
        updated.intercept = Map.of("svc", Map.of("base", "url"));
        loader.update("a", updated, null);

        assertThat(runs).as("intercept edits do not restart the body").hasValue(1);
        assertThat(loader.resolve("a").fiber.ctx().interceptConfig("svc")).isEqualTo(Map.of("base", "url"));

        // a config change still restarts, and the restarted body is
        // constructed under the new intercept
        EntryOptions reconfigured = entry("a");
        reconfigured.id = "a";
        reconfigured.name = "svc";
        reconfigured.intercept = Map.of("svc", Map.of("base", "url"));
        reconfigured.config = Map.of("v", 1);
        loader.update("a", reconfigured, null);

        assertThat(runs).hasValue(2);
        assertThat(seen.get()).isEqualTo(Map.of("base", "url"));
    }
}
