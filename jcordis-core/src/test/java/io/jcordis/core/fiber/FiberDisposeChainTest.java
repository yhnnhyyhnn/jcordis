package io.jcordis.core.fiber;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import io.jcordis.core.util.Disposable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * Regressions for the dispose chain and inject configuration: one throwing
 * dispose must not abort the teardown of the remaining effects (they would
 * leak with live hooks), and non-null inject configs must reach the fiber's
 * intercept layer so injected services can resolve their configuration.
 */
class FiberDisposeChainTest {

    @Test
    void throwingDisposeShouldNotAbortTheChain() {
        Context root = Context.create();
        List<String> order = new CopyOnWriteArrayList<>();
        Fiber fiber = root.plugin((ctx, cfg) -> {
            ctx.effect(runner -> io.jcordis.core.fiber.EffectResult.of(() -> order.add("first")), "first");
            ctx.effect(
                    runner -> io.jcordis.core.fiber.EffectResult.of(() -> {
                        order.add("second");
                        throw new IllegalStateException("boom");
                    }),
                    "second");
            ctx.effect(runner -> io.jcordis.core.fiber.EffectResult.of(() -> order.add("third")), "third");
            return null;
        });

        // teardown is reverse-registration: third, second (throws), first —
        // the throwing dispose must not strand the remaining one
        fiber.disposeAsync().join();

        assertThat(order).containsExactly("third", "second", "first");
        assertThat(fiber.state()).isEqualTo(FiberState.DISPOSED);
    }

    @Test
    void cascadedDisposeShouldTearDownEveryDescendant() {
        Context root = Context.create();
        List<String> order = new CopyOnWriteArrayList<>();
        Fiber child = root.plugin((ctx, cfg) -> {
            ctx.effect(
                    runner -> io.jcordis.core.fiber.EffectResult.of(() -> {
                        order.add("child");
                        throw new IllegalStateException("boom");
                    }),
                    "child");
            return null;
        });
        Fiber grandChild = child.ctx().plugin((ctx, cfg) -> {
            ctx.effect(runner -> io.jcordis.core.fiber.EffectResult.of(() -> order.add("grand-child")), "gc");
            return null;
        });
        assertThat(grandChild.state()).isEqualTo(FiberState.ACTIVE);

        // the child's throwing dispose must not strand the root's teardown of
        // the grand-child
        root.fiber().disposeAsync().join();

        assertThat(order).containsExactly("grand-child", "child");
    }

    @Test
    void injectConfigShouldReachTheFiberInterceptLayer() {
        Context root = Context.create();
        root.provide("timer", new Object());
        List<Object> seen = new CopyOnWriteArrayList<>();
        root.inject(Map.of("timer", Map.of("interval", 50)), (ctx, cfg) -> {
            seen.add(ctx.interceptConfig("timer"));
            return (Disposable) () -> {};
        });

        assertThat(seen).containsExactly(Map.of("interval", 50));
    }
}
