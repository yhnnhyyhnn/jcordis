package io.jcordis.core.fiber;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import io.jcordis.core.util.Disposable;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Translates Cordis fiber restart semantics: body re-execution on restart/update. */
class FiberRestartTest {

    @Test
    void restart_shouldRerunPluginBody() {
        Context ctx = Context.create();
        AtomicInteger calls = new AtomicInteger();
        Fiber fiber = ctx.plugin((c, config) -> {
            calls.incrementAndGet();
            return null;
        });
        assertThat(calls).hasValue(1);

        fiber.restart().join();

        assertThat(calls).hasValue(2);
    }

    @Test
    void restart_shouldDisposeOldBodyBeforeRerun() {
        Context ctx = Context.create();
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger disposes = new AtomicInteger();
        Fiber fiber = ctx.plugin((c, config) -> {
            calls.incrementAndGet();
            return (Disposable) disposes::incrementAndGet;
        });

        fiber.restart().join();

        assertThat(calls).hasValue(2);
        assertThat(disposes).hasValue(1);
    }

    @Test
    void update_shouldRestartBodyWithNewConfig() {
        Context ctx = Context.create();
        AtomicReference<Object> seen = new AtomicReference<>();
        Fiber fiber = ctx.plugin(
                (c, config) -> {
                    seen.set(config);
                    return null;
                },
                Map.of("a", 1));
        assertThat(seen.get()).isEqualTo(Map.of("a", 1));

        fiber.update(Map.of("a", 2), false);

        assertThat(seen.get()).isEqualTo(Map.of("a", 2));
    }

    @Test
    void restart_onRootFiber_shouldBeNoop() {
        Context ctx = Context.create();
        assertThat(ctx.fiber().restart().join()).isSameAs(ctx.fiber());
    }
}
