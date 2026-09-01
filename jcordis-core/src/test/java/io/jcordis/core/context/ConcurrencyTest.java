package io.jcordis.core.context;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.fiber.Fiber;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Concurrency audit: the framework must remain correct under concurrent
 * plugin/effect/service traffic (async bodies complete on arbitrary threads,
 * loaders may tear fibers down concurrently).
 */
class ConcurrencyTest {

    @Test
    void registryCounter_shouldBeUniqueUnderConcurrency() {
        Context ctx = Context.create();
        int workers = 8;
        int perWorker = 500;
        Set<Integer> uids = ConcurrentHashMap.newKeySet();
        CompletableFuture<?>[] tasks = new CompletableFuture<?>[workers];
        for (int w = 0; w < workers; w++) {
            tasks[w] = CompletableFuture.runAsync(() -> {
                for (int i = 0; i < perWorker; i++) {
                    uids.add(ctx.registry().counter());
                }
            });
        }
        CompletableFuture.allOf(tasks).join();
        assertThat(uids).hasSize(workers * perWorker);
    }

    @Test
    void concurrentProvide_shouldAllowExactlyOneWinner() {
        Context ctx = Context.create();
        int workers = 16;
        AtomicInteger winners = new AtomicInteger();
        AtomicInteger losers = new AtomicInteger();
        CompletableFuture<?>[] tasks = new CompletableFuture<?>[workers];
        for (int w = 0; w < workers; w++) {
            final int id = w;
            tasks[w] = CompletableFuture.runAsync(() -> {
                try {
                    ctx.reflect().provide("shared", "value-" + id, ctx);
                    winners.incrementAndGet();
                } catch (IllegalStateException e) {
                    losers.incrementAndGet();
                }
            });
        }
        CompletableFuture.allOf(tasks).join();

        // exactly one provide wins; every other one threw (the winner is an
        // arbitrary thread — no ordering guarantee)
        assertThat(winners).hasValue(1);
        assertThat(losers).hasValue(workers - 1);
        assertThat(ctx.<Object>get("shared")).isNotNull().asString().startsWith("value-");
    }

    @Test
    void concurrentEvents_shouldDeliverToAllRegistered() {
        Context ctx = Context.create();
        int workers = 8;
        int perWorker = 200;
        AtomicInteger deliveries = new AtomicInteger();
        // register from many threads concurrently
        CompletableFuture<?>[] registerers = new CompletableFuture<?>[workers];
        for (int w = 0; w < workers; w++) {
            registerers[w] = CompletableFuture.runAsync(() -> {
                for (int i = 0; i < perWorker; i++) {
                    ctx.on("custom-event", (thisArg, args) -> {
                        deliveries.incrementAndGet();
                        return null;
                    });
                }
            });
        }
        CompletableFuture.allOf(registerers).join();
        ctx.emit("custom-event");
        assertThat(deliveries).hasValue(workers * perWorker);
    }

    @Test
    void concurrentLogging_shouldNotCorruptBuffer() {
        Context ctx = Context.create();
        int workers = 8;
        int perWorker = 100;
        CompletableFuture<?>[] tasks = new CompletableFuture<?>[workers];
        for (int w = 0; w < workers; w++) {
            tasks[w] = CompletableFuture.runAsync(() -> {
                for (int i = 0; i < perWorker; i++) {
                    ctx.logger()
                            .info("worker-%d message %d", Thread.currentThread().getId(), i);
                }
            });
        }
        CompletableFuture.allOf(tasks).join();
        // bounded buffer: never exceeds the configured size, no exceptions
        assertThat(ctx.loggerService().buffer().size()).isLessThanOrEqualTo(1000);
        // all messages intact (args[0] is the format string, args[1] the first arg)
        assertThat(ctx.loggerService().buffer().stream().map(m -> m.args()[0]))
                .allMatch(s -> s.equals("worker-%d message %d"));
    }

    @Test
    void concurrentPlugins_shouldKeepEveryFiberConsistent() {
        Context ctx = Context.create();
        int workers = 8;
        CompletableFuture<?>[] tasks = new CompletableFuture<?>[workers];
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int w = 0; w < workers; w++) {
            tasks[w] = CompletableFuture.runAsync(() -> {
                try {
                    for (int i = 0; i < 50; i++) {
                        Fiber fiber = ctx.plugin((c, config) -> null);
                        fiber.disposeAsync().join();
                    }
                } catch (Throwable t) {
                    failure.set(t);
                }
            });
        }
        CompletableFuture.allOf(tasks).join();
        assertThat(failure).hasValue(null);
        // everything disposed: registry back to empty
        assertThat(ctx.registry().size()).isZero();
    }
}
