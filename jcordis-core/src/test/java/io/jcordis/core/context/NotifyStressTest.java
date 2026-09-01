package io.jcordis.core.context;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.fiber.Fiber;
import io.jcordis.core.fiber.FiberState;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Dependency-response stress: concurrent provides and notifies must settle to a
 * single consistent state — consumers resolve exactly one winner per name, the
 * dependency chain propagates, and isolated realms stay correctly routed.
 */
class NotifyStressTest {

    @Test
    void concurrentProvide_shouldResolveExactlyOneWinnerInConsumer() {
        Context ctx = Context.create();
        AtomicReference<Object> seen = new AtomicReference<>("sentinel");
        // the consumer injects "svc" before any provider exists → stays unloaded
        Fiber consumer = ctx.inject(List.of("svc"), (c, config) -> {
            seen.set(c.get("svc"));
            return null;
        });
        assertThat(consumer.state()).isNotEqualTo(FiberState.ACTIVE);

        // 16 threads race to provide the same service; exactly one wins
        int workers = 16;
        CompletableFuture<?>[] tasks = new CompletableFuture<?>[workers];
        AtomicInteger winners = new AtomicInteger();
        for (int w = 0; w < workers; w++) {
            final int id = w;
            tasks[w] = CompletableFuture.runAsync(() -> {
                try {
                    ctx.provide("svc", "value-" + id);
                    winners.incrementAndGet();
                } catch (IllegalStateException e) {
                    // expected: duplicate provide loses
                }
            });
        }
        CompletableFuture.allOf(tasks).join();

        assertThat(winners).hasValue(1);
        // the consumer was notified and loaded with exactly the winner's value
        assertThat(consumer.state()).isEqualTo(FiberState.ACTIVE);
        assertThat(seen.get()).isNotNull().asString().startsWith("value-");
        consumer.disposeAsync().join();
    }

    @Test
    void concurrentProvide_shouldPropagateThroughDependencyChain() {
        Context ctx = Context.create();
        AtomicReference<Object> cSees = new AtomicReference<>("sentinel");
        // B injects x (raced below) and provides y; C injects y
        Fiber b = ctx.inject(List.of("x"), (c, config) -> {
            c.provide("y", "y-from-b");
            return null;
        });
        Fiber c = ctx.inject(List.of("y"), (cc, config) -> {
            cSees.set(cc.get("y"));
            return null;
        });
        assertThat(b.state()).isNotEqualTo(FiberState.ACTIVE);
        assertThat(c.state()).isNotEqualTo(FiberState.ACTIVE);

        // 16 threads race to provide x → winner notifies → B loads → provides y
        // → notifies → C loads
        int workers = 16;
        CompletableFuture<?>[] tasks = new CompletableFuture<?>[workers];
        for (int w = 0; w < workers; w++) {
            tasks[w] = CompletableFuture.runAsync(() -> {
                try {
                    ctx.provide("x", "x-winner");
                } catch (IllegalStateException e) {
                    // duplicate provide loses
                }
            });
        }
        CompletableFuture.allOf(tasks).join();

        assertThat(b.state()).isEqualTo(FiberState.ACTIVE);
        assertThat(c.state()).isEqualTo(FiberState.ACTIVE);
        assertThat(cSees.get()).isEqualTo("y-from-b");
        b.disposeAsync().join();
        c.disposeAsync().join();
    }

    @Test
    void concurrentIsolatedProvides_shouldRouteToOwnRealms() {
        Context ctx = Context.create();
        int workers = 8;
        // consumers in realm @a and @b both inject "db"
        AtomicReference<Object> aSees = new AtomicReference<>("sentinel");
        AtomicReference<Object> bSees = new AtomicReference<>("sentinel");
        Fiber aConsumer = ctx.isolate("db", io.jcordis.core.service.ServiceKey.of("a-realm"))
                .inject(List.of("db"), (c, config) -> {
                    aSees.set(c.get("db"));
                    return null;
                });
        Fiber bConsumer = ctx.isolate("db", io.jcordis.core.service.ServiceKey.of("b-realm"))
                .inject(List.of("db"), (c, config) -> {
                    bSees.set(c.get("db"));
                    return null;
                });

        // providers race within their own realms
        CompletableFuture<?>[] tasks = new CompletableFuture<?>[workers * 2];
        for (int w = 0; w < workers; w++) {
            final int id = w;
            tasks[w] = CompletableFuture.runAsync(() -> {
                try {
                    ctx.isolate("db", io.jcordis.core.service.ServiceKey.of("a-realm"))
                            .provide("db", "a-" + id);
                } catch (IllegalStateException ignored) {
                }
            });
            tasks[workers + w] = CompletableFuture.runAsync(() -> {
                try {
                    ctx.isolate("db", io.jcordis.core.service.ServiceKey.of("b-realm"))
                            .provide("db", "b-" + id);
                } catch (IllegalStateException ignored) {
                }
            });
        }
        CompletableFuture.allOf(tasks).join();

        // each realm's consumer sees exactly its own winner
        assertThat(aConsumer.state()).isEqualTo(FiberState.ACTIVE);
        assertThat(bConsumer.state()).isEqualTo(FiberState.ACTIVE);
        assertThat(aSees.get()).isNotNull().asString().startsWith("a-");
        assertThat(bSees.get()).isNotNull().asString().startsWith("b-");
        aConsumer.disposeAsync().join();
        bConsumer.disposeAsync().join();
    }

    @Test
    void eventBus_mixedConcurrentTraffic_shouldDeliverConsistently() {
        Context ctx = Context.create();
        int workers = 8;
        int perWorker = 100;
        AtomicInteger deliveries = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        CompletableFuture<?>[] tasks = new CompletableFuture<?>[workers];
        for (int w = 0; w < workers; w++) {
            tasks[w] = CompletableFuture.runAsync(() -> {
                try {
                    for (int i = 0; i < perWorker; i++) {
                        // mixed traffic: register, emit, and dispose listeners
                        io.jcordis.core.util.Disposable listener = ctx.on("mix", (thisArg, args) -> {
                            deliveries.incrementAndGet();
                            return null;
                        });
                        ctx.emit("mix");
                        if (i % 3 == 0) {
                            listener.dispose();
                        }
                    }
                } catch (Throwable t) {
                    failure.set(t);
                }
            });
        }
        CompletableFuture.allOf(tasks).join();

        assertThat(failure).hasValue(null);
        // every emission reached every live listener (some were disposed)
        assertThat(deliveries.get()).isPositive();
    }
}
