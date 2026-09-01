package io.jcordis.core.fiber;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Mirrors Cordis fiber failure semantics: a throwing (sync or async) plugin
 * body records an error, reverts to {@code FAILED}, and only recovers through
 * {@code Fiber.update} — which clears the error before restarting.
 */
class FiberFailureTest {

    @Test
    void syncBodyFailure_shouldFailFiberAndThrowFromAwait() {
        Context ctx = Context.create();
        Fiber fiber = ctx.plugin((c, config) -> {
            throw new IllegalStateException("boom");
        });

        assertThat(fiber.state()).isEqualTo(FiberState.FAILED);
        assertThatThrownBy(() -> fiber.await().join()).hasRootCauseMessage("boom");
    }

    @Test
    void syncBodyFailure_shouldDisposePartialEffects() {
        Context ctx = Context.create();
        AtomicInteger disposes = new AtomicInteger();
        Fiber fiber = ctx.plugin((c, config) -> {
            c.effect(r -> EffectResult.of(disposes::incrementAndGet), "partial");
            throw new IllegalStateException("boom");
        });

        assertThat(fiber.state()).isEqualTo(FiberState.FAILED);
        assertThat(disposes).as("partial effect disposed on failure").hasValue(1);
    }

    @Test
    void asyncBodyFailure_shouldFailFiber() {
        Context ctx = Context.create();
        CompletableFuture<Object> gate = new CompletableFuture<>();
        Fiber fiber = ctx.plugin((c, config) -> gate);

        assertThat(fiber.state()).isEqualTo(FiberState.LOADING);
        gate.completeExceptionally(new IllegalStateException("async boom"));
        assertThatThrownBy(() -> fiber.await().join()).hasRootCauseMessage("async boom");
        assertThat(fiber.state()).isEqualTo(FiberState.FAILED);
    }

    @Test
    void update_shouldRecoverFailedFiber() {
        Context ctx = Context.create();
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger boom = new AtomicInteger(1);
        Fiber fiber = ctx.plugin((c, config) -> {
            if (boom.getAndSet(0) == 1) {
                throw new IllegalStateException("first run fails");
            }
            calls.incrementAndGet();
            return null;
        });

        assertThat(fiber.state()).isEqualTo(FiberState.FAILED);

        fiber.update(null, false);

        assertThat(fiber.state()).isEqualTo(FiberState.ACTIVE);
        assertThat(calls).hasValue(1);
    }

    @Test
    void restart_shouldNotRecoverFailedFiberWithoutUpdate() {
        Context ctx = Context.create();
        AtomicInteger calls = new AtomicInteger();
        Fiber fiber = ctx.plugin((c, config) -> {
            calls.incrementAndGet();
            throw new IllegalStateException("always fails");
        });

        assertThat(fiber.state()).isEqualTo(FiberState.FAILED);
        assertThatThrownBy(() -> fiber.restart().join()).hasRootCauseMessage("always fails");
        assertThat(calls)
                .as("no re-execution: epoch transitions are blocked while failed")
                .hasValue(1);
    }

    @Test
    void failedPlugin_shouldBeDisposable() {
        Context ctx = Context.create();
        Plugin plugin = (c, config) -> {
            throw new IllegalStateException("boom");
        };
        Fiber fiber = ctx.plugin(plugin);
        assertThat(fiber.state()).isEqualTo(FiberState.FAILED);

        fiber.disposeAsync().join();
        assertThat(fiber.state()).isEqualTo(FiberState.DISPOSED);
        assertThat(fiber.uid()).isEqualTo(-1);
    }
}
