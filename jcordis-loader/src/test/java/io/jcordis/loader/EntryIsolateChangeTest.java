package io.jcordis.loader;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import io.jcordis.core.fiber.FiberState;
import io.jcordis.core.util.Disposable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Regression: isolate-only changes through {@code loader.read()} (the
 * config-file / HMR path) must take effect. Previously the {@code force=false}
 * update skipped the restart when only {@code isolate} changed, silently
 * dropping the realm move.
 */
class EntryIsolateChangeTest {

    private static EntryOptions entry(String id, String name) {
        EntryOptions options = new EntryOptions();
        options.id = id;
        options.name = name;
        return options;
    }

    @Test
    void isolateChangeViaRead_shouldMoveRealms() {
        Context root = Context.create();
        Loader loader = new Loader(root);
        AtomicReference<Object> bSees = new AtomicReference<>("sentinel");
        AtomicInteger bCalls = new AtomicInteger();
        AtomicInteger bDisposes = new AtomicInteger();
        loader.mock("a", (ctx, config) -> {
            ctx.provide("db", "A");
            return null;
        });
        loader.mock("b", (ctx, config) -> {
            bSees.set(ctx.get("db"));
            bCalls.incrementAndGet();
            return (Disposable) bDisposes::incrementAndGet;
        });

        EntryOptions a = entry("a", "a");
        a.isolate = Map.of("db", "one");
        EntryOptions b = entry("b", "b");
        b.isolate = Map.of("db", "one");
        b.inject = new java.util.HashMap<>();
        b.inject.put("db", null);
        loader.read(List.of(a, b));
        assertThat(bSees.get()).isEqualTo("A");
        assertThat(bCalls).hasValue(1);

        // a moves to realm "two"; b stays in "one" → b's dependency vanishes
        EntryOptions a2 = entry("a", "a");
        a2.isolate = Map.of("db", "two");
        loader.read(List.of(a2, b));
        assertThat(bDisposes)
                .as("b unloads when its dependency leaves its realm")
                .hasValue(1);
        assertThat(loader.expectFiber("b").state()).isNotEqualTo(FiberState.ACTIVE);

        // b follows a into "two" → b reloads and sees a's db again
        EntryOptions b2 = entry("b", "b");
        b2.isolate = Map.of("db", "two");
        b2.inject = new java.util.HashMap<>();
        b2.inject.put("db", null);
        loader.read(List.of(a2, b2));
        assertThat(bCalls).hasValue(2);
        assertThat(bSees.get()).isEqualTo("A");
    }
}
