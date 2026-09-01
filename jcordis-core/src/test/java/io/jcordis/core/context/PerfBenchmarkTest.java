package io.jcordis.core.context;

import io.jcordis.core.fiber.EffectResult;
import io.jcordis.core.fiber.Fiber;
import org.junit.jupiter.api.Test;

/**
 * Lightweight throughput benchmark (no hard assertions — informational only,
 * results are printed to the surefire report and recorded in
 * {@code docs/perf.md}). Run on a representative machine to re-baseline.
 */
class PerfBenchmarkTest {

    private static long time(Runnable body) {
        // warmup
        for (int i = 0; i < 1000; i++) {
            body.run();
        }
        long start = System.nanoTime();
        for (int i = 0; i < 10_000; i++) {
            body.run();
        }
        return (System.nanoTime() - start) / 10_000;
    }

    private static void report(String name, long nanos) {
        System.out.printf("[perf] %-38s %6d ns/op (%.2f M ops/s)%n", name, nanos, 1_000.0 / nanos);
    }

    @Test
    void fiberCreation() {
        Context ctx = Context.create();
        report("fiber create + dispose", time(() -> {
            Fiber fiber = ctx.plugin((c, config) -> null);
            fiber.disposeAsync().join();
        }));
    }

    @Test
    void effectRegisterDispose() {
        Context ctx = Context.create();
        report("effect register + dispose", time(() -> {
            io.jcordis.core.util.Disposable disposable = ctx.effect(r -> EffectResult.of(() -> {}), "bench");
            disposable.dispose();
        }));
    }

    @Test
    void eventEmit() {
        Context ctx = Context.create();
        for (int i = 0; i < 10; i++) {
            ctx.on("bench-event", (thisArg, args) -> null);
        }
        report("event emit (10 listeners)", time(() -> ctx.emit("bench-event")));
    }

    @Test
    void serviceProvideGet() {
        Context ctx = Context.create();
        ctx.provide("bench-service", "value");
        report("service get (provided)", time(() -> ctx.get("bench-service")));
    }

    @Test
    void loggerFormat() {
        Context ctx = Context.create();
        io.jcordis.core.logger.Message message = new io.jcordis.core.logger.Message(
                1, System.currentTimeMillis(), "bench", "info", 2, new Object[] {"value: %s (%d)", "x", 42});
        io.jcordis.core.logger.Exporter exporter = new io.jcordis.core.logger.Exporter() {
            @Override
            public void export(io.jcordis.core.logger.Message ignored) {}
        };
        report("logger format", time(() -> io.jcordis.core.logger.Logger.format(exporter, message)));
    }
}
