package io.jcordis.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import io.jcordis.core.fiber.EffectResult;
import io.jcordis.core.fiber.Fiber;
import io.jcordis.core.registry.Plugin;
import io.jcordis.core.util.Disposable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Translates Cordis service.spec.ts: injection, pending init, traceable effects. */
class ServiceRegistryTest {

    private static final String EVENT = "custom-event";

    /** Builds an inject map (values may be null, unlike {@code Map.of}). */
    private static Map<String, Object> inject(String... names) {
        Map<String, Object> map = new java.util.HashMap<>();
        for (String name : names) {
            map.put(name, null);
        }
        return map;
    }

    /** Counter service with effect-based increment. */
    static class Counter {
        final Context ctx;
        int value;

        Counter(Context ctx) {
            this.ctx = ctx;
        }

        Disposable increase() {
            return ctx.effect(r -> {
                value++;
                return EffectResult.of(() -> value--);
            }, "increase");
        }
    }

    @Test
    void pendingInject() {
        Context root = Context.create();
        AtomicInteger calls = new AtomicInteger();

        root.inject(List.of("foo"), (ctx, config) -> {
            calls.incrementAndGet();
            return null;
        });
        assertThat(calls).hasValue(0);

        CompletableFuture<Object> gate = new CompletableFuture<>();
        Plugin foo = Plugin.object("Foo", Map.of(), (ctx, config) -> {
            ctx.provide("foo", "foo");
            ctx.on(EVENT, (a, b) -> {
                gate.complete(null);
                return null;
            });
            return gate;
        });
        Fiber fooFiber = root.plugin(foo);
        assertThat(calls).hasValue(0);

        root.emit(EVENT);
        fooFiber.await().join();
        assertThat(calls).hasValue(1);
    }

    @Test
    void traceableEffectWithInject() {
        Context root = Context.create();
        root.provide("counter", null);
        root.set("counter", new Counter(root));

        Plugin foo = Plugin.object("Foo", inject("counter"), (ctx, config) -> {
            ctx.provide("foo", new Foo(ctx));
            return null;
        });
        root.plugin(foo).await().join();

        Foo fooSvc = (Foo) root.get("foo");
        fooSvc.increase();
        assertThat(fooSvc.value()).isEqualTo(1);

        Fiber fiber = root.inject(List.of("foo"), (ctx, config) -> {
            ((Foo) root.get("foo")).increase();
            assertThat(((Foo) ctx.get("foo")).value()).isEqualTo(2);
            return null;
        });
        fiber.await().join();

        fiber.disposeAsync().join();
        ((Foo) root.get("foo")).increase();
        assertThat(((Foo) root.get("foo")).value()).isEqualTo(3);
    }

    /** Foo service whose value delegates to the counter. */
    static class Foo {
        final Context ctx;

        Foo(Context ctx) {
            this.ctx = ctx;
        }

        int value() {
            return ((Counter) ctx.get("counter")).value;
        }

        Disposable increase() {
            return ((Counter) ctx.get("counter")).increase();
        }
    }

    @Test
    void traceableEffectWithoutInject() {
        Context root = Context.create();
        root.provide("counter", null);
        root.set("counter", new Counter(root));

        Plugin foo = Plugin.object("Foo", Map.of(), (ctx, config) -> {
            ctx.provide("foo", new Foo(ctx));
            return null;
        });
        root.plugin(foo).await().join();

        Foo fooSvc = (Foo) root.get("foo");
        fooSvc.increase();
        assertThat(fooSvc.value()).isEqualTo(1);

        Fiber fiber = root.inject(List.of("foo"), (ctx, config) -> {
            ((Foo) root.get("foo")).increase();
            assertThat(((Foo) ctx.get("foo")).value()).isEqualTo(2);
            return null;
        });
        fiber.await().join();

        fiber.disposeAsync().join();
        ((Foo) root.get("foo")).increase();
        assertThat(((Foo) root.get("foo")).value()).isEqualTo(3);
    }

    @Test
    void compareSnapshot() {
        Context root = Context.create();
        Plugin test = Plugin.object("Test", Map.of(), (ctx, config) -> {
            ctx.inject(List.of("test"), (c, cfg) -> null);
            return null;
        });

        Map<String, Integer> before = root.events().hookCounts();
        root.plugin(test).await().join();
        Map<String, Integer> after = root.events().hookCounts();

        root.registry().delete(test);
        assertThat(root.events().hookCounts()).isEqualTo(before);

        root.plugin(test).await().join();
        assertThat(root.events().hookCounts()).isEqualTo(after);
    }

    @Test
    void multipleInjects() {
        Context root = Context.create();
        AtomicInteger foo = new AtomicInteger();
        AtomicInteger bar = new AtomicInteger();
        AtomicInteger qux = new AtomicInteger();

        Plugin quxPlugin = Plugin.object("Qux", Map.of(), (ctx, config) -> {
            ctx.provide("qux", "qux");
            qux.incrementAndGet();
            return null;
        });
        Plugin fooPlugin = Plugin.object("Foo", inject("qux"), (ctx, config) -> {
            ctx.provide("foo", "foo");
            foo.incrementAndGet();
            return null;
        });
        Plugin barPlugin = Plugin.object("Bar", inject("foo", "qux"), (ctx, config) -> {
            ctx.provide("bar", "bar");
            bar.incrementAndGet();
            return null;
        });

        root.plugin(fooPlugin).await().join();
        root.plugin(barPlugin).await().join();
        root.plugin(quxPlugin).await().join();

        assertThat(foo).hasValue(1);
        assertThat(bar).hasValue(1);
        assertThat(qux).hasValue(1);
    }
}