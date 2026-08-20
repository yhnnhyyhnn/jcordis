package io.jcordis.core.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jcordis.core.context.Context;
import io.jcordis.core.fiber.CordisError;
import io.jcordis.core.fiber.Fiber;
import io.jcordis.core.fiber.FiberState;
import io.jcordis.core.util.Disposable;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Translates Cordis plugin.spec.ts: plugin forms, lifecycle, registry, disposal. */
class PluginRegistryTest {

    private static final String EVENT = "custom-event";

    @Test
    void applyFunctionalPlugin() {
        Context root = Context.create();
        AtomicInteger calls = new AtomicInteger();
        Object options = Map.of("foo", "bar");

        root.plugin((ctx, config) -> {
            calls.incrementAndGet();
            assertThat(config).isEqualTo(options);
            return null;
        }, options).await().join();

        assertThat(calls).hasValue(1);
    }

    @Test
    void applyObjectPlugin() {
        Context root = Context.create();
        AtomicInteger calls = new AtomicInteger();
        Object options = Map.of("bar", "foo");
        Plugin plugin = Plugin.object(null, Map.of(), (ctx, config) -> {
            calls.incrementAndGet();
            assertThat(config).isEqualTo(options);
            return null;
        });

        root.plugin(plugin, options).await().join();

        assertThat(calls).hasValue(1);
    }

    @Test
    void applyInvalidPlugin() {
        Context root = Context.create();
        assertThatThrownBy(() -> root.plugin(null)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void inactiveContext() {
        Context root = Context.create();
        AtomicInteger calls = new AtomicInteger();

        Fiber fiber = root.plugin((ctx, config) -> (Disposable) () -> {
            assertThatThrownBy(() -> ctx.plugin((c, cfg) -> null)).isInstanceOf(CordisError.class);
            assertThatThrownBy(() -> ctx.effect(r -> null, "x")).isInstanceOf(CordisError.class);
            assertThatThrownBy(() -> ctx.on(EVENT, (a, b) -> null)).isInstanceOf(CordisError.class);
        });
        fiber.disposeAsync().join();
        assertThat(calls).hasValue(0);
    }

    @Test
    void contextInspect() {
        Context root = Context.create();
        assertThat(root.toString()).isEqualTo("Context <root>");

        root.plugin((ctx, config) -> {
            assertThat(ctx.toString()).isEqualTo("Context <root>");
            return null;
        }).await().join();

        root.plugin(Plugin.object("foo", Map.of(), (ctx, config) -> {
            assertThat(ctx.toString()).isEqualTo("Context <foo>");
            return null;
        })).await().join();

        root.plugin(Plugin.object("bar", Map.of(), (ctx, config) -> {
            assertThat(ctx.toString()).isEqualTo("Context <bar>");
            return null;
        })).await().join();

        root.plugin(Plugin.constructor(Qux.class)).await().join();
    }

    /** Named class plugin for context inspection. */
    public static class Qux implements Plugin {
        @Override
        public Object apply(Context ctx, Object config) {
            assertThat(ctx.toString()).isEqualTo("Context <Qux>");
            return null;
        }
    }

    @Test
    void registryIteration() {
        Context root = Context.create();
        root.registry().keys();
        root.registry().values();
        root.registry().entries();
        root.registry().forEach((plugin, runtime) -> {});
        assertThat(root.registry().size()).isZero();
    }

    @Test
    void nestedPlugins() {
        Context root = Context.create();
        AtomicInteger calls = new AtomicInteger();

        Plugin inner = (ctx, config) -> {
            ctx.on(EVENT, (a, b) -> {
                calls.incrementAndGet();
                return null;
            });
            return null;
        };
        Plugin middle = (ctx, config) -> {
            ctx.on(EVENT, (a, b) -> {
                calls.incrementAndGet();
                return null;
            });
            ctx.plugin(inner).await().join();
            return null;
        };
        Plugin outer = (ctx, config) -> {
            ctx.on(EVENT, (a, b) -> {
                calls.incrementAndGet();
                return null;
            });
            ctx.plugin(middle).await().join();
            return null;
        };

        root.on(EVENT, (a, b) -> {
            calls.incrementAndGet();
            return null;
        });
        Fiber fiber = root.plugin(outer).await().join();

        assertThat(root.registry().size()).isEqualTo(3);
        root.emit(EVENT);
        assertThat(calls).hasValue(4);

        fiber.disposeAsync().join();
        assertThat(root.registry().size()).isZero();
        root.emit(EVENT);
        assertThat(calls).hasValue(5);

        fiber.disposeAsync().join();
        assertThat(root.registry().size()).isZero();
        root.emit(EVENT);
        assertThat(calls).hasValue(6);
    }

    @Test
    void compareSnapshot() {
        Context root = Context.create();
        Plugin plugin = (ctx, config) -> {
            ctx.on(EVENT, (a, b) -> null);
            ctx.plugin((c, cfg) -> {
                c.on(EVENT, (a, b) -> null);
                return null;
            }).await().join();
            return null;
        };

        Map<String, Integer> before = root.events().hookCounts();
        root.plugin(plugin).await().join();
        Map<String, Integer> after = root.events().hookCounts();

        root.registry().delete(plugin);
        assertThat(root.events().hookCounts()).isEqualTo(before);

        root.plugin(plugin).await().join();
        assertThat(root.events().hookCounts()).isEqualTo(after);
    }

    @Test
    void rootDispose() {
        Context root = Context.create();
        AtomicInteger calls = new AtomicInteger();
        Fiber fiber = root.plugin((ctx, config) -> (Disposable) calls::incrementAndGet);

        assertThat(root.fiber().uid()).isZero();
        assertThat(fiber.uid()).isEqualTo(1);
        assertThat(calls).hasValue(0);
        assertThat(root.fiber().effectCount()).isEqualTo(1);

        root.fiber().disposeAsync().join();
        assertThat(root.fiber().uid()).isZero();
        assertThat(fiber.uid()).isEqualTo(-1);
        assertThat(calls).hasValue(1);
        assertThat(root.fiber().effectCount()).isZero();

        root.fiber().disposeAsync().join();
        assertThat(root.fiber().uid()).isZero();
        assertThat(fiber.uid()).isEqualTo(-1);
        assertThat(calls).hasValue(1);
        assertThat(root.fiber().effectCount()).isZero();
    }

    @Test
    void serviceInit() {
        Context root = Context.create();
        InitPlugin.start.set(0);
        InitPlugin.stop.set(0);

        Fiber fiber = root.plugin(Plugin.constructor(InitPlugin.class)).await().join();

        assertThat(InitPlugin.start).hasValue(1);
        assertThat(InitPlugin.stop).hasValue(0);

        fiber.disposeAsync().join();
        assertThat(InitPlugin.start).hasValue(1);
        assertThat(InitPlugin.stop).hasValue(1);
    }

    @Test
    void serviceInit_shouldMarkFiberActive() {
        Context root = Context.create();
        Fiber fiber = root.plugin(Plugin.constructor(FooService.class)).await().join();
        assertThat(fiber.state()).isEqualTo(FiberState.ACTIVE);
        fiber.disposeAsync().join();
    }

    /** Class plugin whose init registers a disposable. */
    public static class InitPlugin implements Initializable {
        private static final AtomicInteger start = new AtomicInteger();
        private static final AtomicInteger stop = new AtomicInteger();

        @Override
        public Object init() {
            start.incrementAndGet();
            return (Disposable) stop::incrementAndGet;
        }
    }

    /** Class plugin whose init registers a disposable. */
    public static class FooService implements Initializable {
        @Override
        public Object init() {
            return null;
        }
    }
}