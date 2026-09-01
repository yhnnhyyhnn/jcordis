package io.jcordis.core.fiber;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jcordis.core.context.Context;
import io.jcordis.core.util.Disposable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** Translates Cordis fiber semantics: effect collection, reverse-order disposal, inactivity. */
class FiberEffectTest {

    private final Context ctx = Context.create();

    @Test
    void effect_shouldRunRunnerImmediately() {
        List<String> log = new ArrayList<>();

        ctx.effect(
                r -> {
                    log.add("run");
                    return EffectResult.of(() -> log.add("dispose"));
                },
                "test");

        assertThat(log).containsExactly("run");
    }

    @Test
    void getEffects_shouldExposeLabelsAndNesting() {
        List<String> log = new ArrayList<>();

        Disposable outer = ctx.effect(
                r -> {
                    Disposable inner =
                            ctx.effect(r2 -> EffectResult.of(() -> log.add("inner-dispose")), "ctx.on(\"custom\")");
                    return EffectResult.of(() -> log.add("outer-dispose"), inner);
                },
                "outer");

        List<EffectMeta> effects = ctx.fiber().getEffects();
        assertThat(effects).hasSize(1);
        assertThat(effects.get(0).label()).isEqualTo("outer");
        assertThat(effects.get(0).children()).extracting(EffectMeta::label).containsExactly("ctx.on(\"custom\")");

        outer.dispose();
        assertThat(log).containsExactly("inner-dispose", "outer-dispose");
        ctx.fiber().disposeAsync().join();
        assertThat(log).containsExactly("inner-dispose", "outer-dispose");
    }

    @Test
    void internalStatus_shouldReportStateTransitions() {
        List<String> transitions = new ArrayList<>();
        ctx.on("internal/status", (thisArg, args) -> {
            Fiber fiber = (Fiber) args[0];
            FiberState oldState = (FiberState) args[1];
            transitions.add(oldState + "->" + fiber.state());
            return null;
        });

        Fiber fiber = ctx.plugin((c, config) -> null);
        assertThat(transitions).contains("PENDING->LOADING", "LOADING->ACTIVE");

        fiber.disposeAsync().join();
        assertThat(transitions).contains("ACTIVE->DISPOSED");
    }

    @Test
    void internalGet_shouldAllowInterception() {
        ctx.on("internal/get", (thisArg, args) -> {
            // intercept access to "secret" and substitute a value
            if ("secret".equals(args[1])) {
                return "masked";
            }
            @SuppressWarnings("unchecked")
            java.util.function.Supplier<Object> next = (java.util.function.Supplier<Object>) args[2];
            return next.get();
        });
        ctx.provide("secret", "real");

        assertThat(ctx.<Object>get("secret")).isEqualTo("masked");
    }

    @Test
    void internalSet_shouldAllowRejection() {
        ctx.provide("counter", null);
        ctx.on("internal/set", (thisArg, args) -> {
            if ("counter".equals(args[1])) {
                throw new IllegalStateException("read-only");
            }
            @SuppressWarnings("unchecked")
            java.util.function.Supplier<Object> next = (java.util.function.Supplier<Object>) args[2];
            return next.get();
        });

        assertThatThrownBy(() -> ctx.set("counter", 1)).hasMessageContaining("read-only");
    }

    @Test
    void effectRunnerError_shouldDisposeNestedEffectsImmediately() {
        List<String> log = new ArrayList<>();

        // mirrors dispose.spec 'yield with error': nested effects registered
        // before the throw are disposed right away, and the error propagates
        assertThatThrownBy(() -> ctx.effect(
                        r -> {
                            ctx.effect(r2 -> EffectResult.of(() -> log.add("nested-dispose")), "ctx.on(\"custom\")");
                            throw new IllegalStateException("my error");
                        },
                        "outer"))
                .hasMessage("my error");

        assertThat(log).as("nested effect disposed on runner failure").containsExactly("nested-dispose");
        assertThat(ctx.fiber().getEffects()).as("no leftover effect metadata").isEmpty();
    }

    @Test
    void effectDispose_shouldInvokeCollectedDisposable() {
        List<String> log = new ArrayList<>();

        Disposable dispose = ctx.effect(r -> EffectResult.of(() -> log.add("dispose")), "test");
        dispose.dispose();

        assertThat(log).containsExactly("dispose");
    }

    @Test
    void effectDispose_shouldBeIdempotent() {
        List<String> log = new ArrayList<>();

        Disposable dispose = ctx.effect(r -> EffectResult.of(() -> log.add("dispose")), "test");
        dispose.dispose();
        dispose.dispose();

        assertThat(log).containsExactly("dispose");
    }

    @Test
    void multipleDisposables_shouldDisposeInReverseOrder() {
        List<String> log = new ArrayList<>();

        ctx.effect(
                r -> EffectResult.of(() -> log.add("first"), () -> log.add("second"), () -> log.add("third")), "test");
        ctx.fiber().disposeAsync().join();

        assertThat(log).containsExactly("third", "second", "first");
    }

    @Test
    void effects_shouldDisposeInReverseRegistrationOrderOnFiberTeardown() {
        List<String> log = new ArrayList<>();

        ctx.effect(r -> EffectResult.of(() -> log.add("effect-1")), "e1");
        ctx.effect(r -> EffectResult.of(() -> log.add("effect-2")), "e2");

        ctx.fiber().disposeAsync().join();

        assertThat(log).containsExactly("effect-2", "effect-1");
    }

    @Test
    void effectWrapperDispose_shouldNotDisposeSiblingEffects() {
        List<String> log = new ArrayList<>();

        Disposable first = ctx.effect(r -> EffectResult.of(() -> log.add("first")), "e1");
        ctx.effect(r -> EffectResult.of(() -> log.add("second")), "e2");

        first.dispose();

        assertThat(log).containsExactly("first");
    }

    @Test
    void noopEffect_shouldReturnDisposableWithoutSideEffects() {
        Disposable dispose = ctx.effect(r -> EffectResult.NOOP, "noop");
        dispose.dispose();
        assertThat(ctx.fiber().state()).isEqualTo(FiberState.ACTIVE);
    }

    @Test
    void effect_shouldRejectOnDisposedFiber() {
        Fiber fiber = ctx.fiber();
        fiber.disposeAsync().join();

        assertThat(fiber.state()).isEqualTo(FiberState.DISPOSED);
        assertThatThrownBy(() -> ctx.effect(r -> EffectResult.NOOP, "late"))
                .isInstanceOf(CordisError.class)
                .hasMessage("INACTIVE_EFFECT");
    }

    @Test
    void await_shouldCompleteWithSelf() {
        Fiber fiber = ctx.fiber();
        CompletableFuture<Fiber> future = fiber.await();
        assertThat(future.join()).isSameAs(fiber);
    }

    @Test
    void disposeAsync_shouldBeIdempotent() {
        List<String> log = new ArrayList<>();
        ctx.effect(r -> EffectResult.of(() -> log.add("dispose")), "e1");

        ctx.fiber().disposeAsync().join();
        ctx.fiber().disposeAsync().join();

        assertThat(log).containsExactly("dispose");
    }
}
