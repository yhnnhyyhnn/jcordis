package io.jcordis.core.context;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.fiber.Fiber;
import io.jcordis.core.registry.Plugin;
import io.jcordis.core.service.Service;
import io.jcordis.core.service.ServiceKey;
import io.jcordis.core.util.Disposable;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Translates Cordis isolate.spec.ts: isolated realms, shared labels, filtered events. */
class IsolateTest {

    private static final String EVENT = "custom-event";

    /** Builds an inject map (values may be null, unlike {@code Map.of}). */
    private static Map<String, Object> inject(String... names) {
        Map<String, Object> map = new java.util.HashMap<>();
        for (String name : names) {
            map.put(name, null);
        }
        return map;
    }

    @Test
    void isolatedContext() {
        Context root = Context.create();
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger disposes = new AtomicInteger();
        Plugin plugin = Plugin.object("P", inject("foo"), (ctx, config) -> {
            calls.incrementAndGet();
            return (Disposable) disposes::incrementAndGet;
        });

        root.plugin(plugin).await().join();
        Context ctx1 = root.isolate("foo");
        ctx1.plugin(plugin).await().join();
        Context ctx2 = root.isolate("foo");
        ctx2.plugin(plugin).await().join();

        Disposable d0 = root.provide("foo", Map.of("bar", 100));
        assertThat(root.<Map<String, Integer>>get("foo")).containsEntry("bar", 100);
        assertThat(ctx1.<Map<String, Integer>>get("foo")).isNull();
        assertThat(ctx2.<Map<String, Integer>>get("foo")).isNull();
        assertThat(calls).hasValue(1);
        assertThat(disposes).hasValue(0);

        Disposable d1 = ctx1.provide("foo", Map.of("bar", 200));
        assertThat(root.<Map<String, Integer>>get("foo")).containsEntry("bar", 100);
        assertThat(ctx1.<Map<String, Integer>>get("foo")).containsEntry("bar", 200);
        assertThat(ctx2.<Map<String, Integer>>get("foo")).isNull();
        assertThat(calls).hasValue(2);
        assertThat(disposes).hasValue(0);

        d0.dispose();
        assertThat(root.<Map<String, Integer>>get("foo")).isNull();
        assertThat(ctx1.<Map<String, Integer>>get("foo")).containsEntry("bar", 200);
        assertThat(ctx2.<Map<String, Integer>>get("foo")).isNull();
        assertThat(calls).hasValue(2);
        assertThat(disposes).hasValue(1);

        Disposable d2 = ctx2.provide("foo", Map.of("bar", 300));
        assertThat(root.<Map<String, Integer>>get("foo")).isNull();
        assertThat(ctx1.<Map<String, Integer>>get("foo")).containsEntry("bar", 200);
        assertThat(ctx2.<Map<String, Integer>>get("foo")).containsEntry("bar", 300);
        assertThat(calls).hasValue(3);
        assertThat(disposes).hasValue(1);
    }

    @Test
    void sharedLabel() {
        Context root = Context.create();
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger disposes = new AtomicInteger();
        Plugin plugin = Plugin.object("P", inject("foo"), (ctx, config) -> {
            calls.incrementAndGet();
            return (Disposable) disposes::incrementAndGet;
        });

        ServiceKey<?> label = ServiceKey.unique("test");
        root.plugin(plugin).await().join();
        Context ctx1 = root.isolate("foo", label);
        ctx1.plugin(plugin).await().join();
        Context ctx2 = root.isolate("foo", label);
        ctx2.plugin(plugin).await().join();
        assertThat(calls).hasValue(0);

        Disposable d0 = root.provide("foo", Map.of("bar", 100));
        assertThat(root.<Map<String, Integer>>get("foo")).containsEntry("bar", 100);
        assertThat(ctx1.<Map<String, Integer>>get("foo")).isNull();
        assertThat(ctx2.<Map<String, Integer>>get("foo")).isNull();
        assertThat(calls).hasValue(1);
        assertThat(disposes).hasValue(0);

        Disposable d12 = ctx1.provide("foo", Map.of("bar", 200));
        assertThat(root.<Map<String, Integer>>get("foo")).containsEntry("bar", 100);
        assertThat(ctx1.<Map<String, Integer>>get("foo")).containsEntry("bar", 200);
        assertThat(ctx2.<Map<String, Integer>>get("foo")).containsEntry("bar", 200);
        assertThat(calls).hasValue(3);
        assertThat(disposes).hasValue(0);

        d12.dispose();
        assertThat(root.<Map<String, Integer>>get("foo")).containsEntry("bar", 100);
        assertThat(ctx1.<Map<String, Integer>>get("foo")).isNull();
        assertThat(ctx2.<Map<String, Integer>>get("foo")).isNull();
        assertThat(calls).hasValue(3);
        assertThat(disposes).hasValue(2);
    }

    @Test
    void isolatedEvent() {
        Context root = Context.create();
        Context ctx = root.isolate("foo");
        AtomicInteger outer = new AtomicInteger();
        AtomicInteger inner = new AtomicInteger();
        root.on(EVENT, (a, b) -> {
            outer.incrementAndGet();
            return null;
        });
        ctx.on(EVENT, (a, b) -> {
            inner.incrementAndGet();
            return null;
        });

        Plugin foo = Plugin.constructor(IsolatedService.class);
        Fiber fiber = ctx.plugin(foo).await().join();

        assertThat(outer).hasValue(0);
        assertThat(inner).hasValue(1);
        fiber.disposeAsync().join();
    }

    /** Service emitting an event with itself as thisArg during construction. */
    public static class IsolatedService extends Service {
        public IsolatedService(Context ctx) {
            super(ctx, "foo");
            ctx.emit(this, EVENT);
        }
    }
}