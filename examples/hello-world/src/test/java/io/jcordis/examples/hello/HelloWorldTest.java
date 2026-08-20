package io.jcordis.examples.hello;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import io.jcordis.core.fiber.Fiber;
import io.jcordis.core.util.Disposable;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Verifies the minimal plugin loads and unloads. */
class HelloWorldTest {

    @Test
    void pluginLoadsAndUnloads() {
        Context root = Context.create();
        AtomicInteger unloads = new AtomicInteger();

        Fiber fiber = root.plugin(new CountingHello(unloads)).await().join();
        assertThat(unloads).hasValue(0);
        fiber.disposeAsync().join();
        assertThat(unloads).hasValue(1);
    }

    @Test
    void pluginRegistersEventHook() {
        Context root = Context.create();
        AtomicInteger calls = new AtomicInteger();
        root.plugin((ctx, config) -> {
            ctx.on("custom-event", (thisArg, args) -> {
                calls.incrementAndGet();
                return null;
            });
            return null;
        }).await().join();

        root.emit("custom-event");
        assertThat(calls).hasValue(1);
    }

    /** HelloPlugin variant whose dispose is observable. */
    private static final class CountingHello extends HelloPlugin {
        private final AtomicInteger unloads;

        CountingHello(AtomicInteger unloads) {
            this.unloads = unloads;
        }

        @Override
        public Object apply(Context ctx, Object config) {
            return (Disposable) unloads::incrementAndGet;
        }
    }
}