package io.jcordis.core;

import io.jcordis.core.context.Context;
import io.jcordis.core.fiber.EffectResult;
import io.jcordis.core.fiber.Fiber;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Professional JMH microbenchmarks mirroring {@code PerfBenchmarkTest} — run
 * with {@code mvn -Pbenchmark -pl jcordis-core test -Dtest=JmhRunnerTest} for
 * calibrated fork/warmup/measurement results (see docs/perf.md).
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(1)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@State(Scope.Benchmark)
public class JmhBenchmarks {

    private Context ctx;
    private io.jcordis.core.logger.Message message;
    private io.jcordis.core.logger.Exporter exporter;

    @Setup
    public void setup() {
        ctx = Context.create();
        for (int i = 0; i < 10; i++) {
            ctx.on("bench-event", (thisArg, args) -> null);
        }
        ctx.provide("bench-service", "value");
        message = new io.jcordis.core.logger.Message(
                1, System.currentTimeMillis(), "bench", "info", 2, new Object[] {"value: %s (%d)", "x", 42});
        exporter = new io.jcordis.core.logger.Exporter() {
            @Override
            public void export(io.jcordis.core.logger.Message ignored) {}
        };
    }

    @Benchmark
    public Fiber fiberCreateAndDispose() {
        Fiber fiber = ctx.plugin((c, config) -> null);
        fiber.disposeAsync().join();
        return fiber;
    }

    @Benchmark
    public void eventEmitTenListeners() {
        ctx.emit("bench-event");
    }

    @Benchmark
    public io.jcordis.core.util.Disposable effectRegisterDispose() {
        io.jcordis.core.util.Disposable disposable = ctx.effect(r -> EffectResult.of(() -> {}), "bench");
        disposable.dispose();
        return disposable;
    }

    @Benchmark
    public Object serviceGet() {
        return ctx.get("bench-service");
    }

    @Benchmark
    public String loggerFormat() {
        return io.jcordis.core.logger.Logger.format(exporter, message);
    }
}
