package io.jcordis.core.fiber;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import io.jcordis.core.util.Disposable;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Translates dispose.spec.ts async paths for the Java model (plugin bodies may
 * return {@code CompletableFuture}): async disposable collection, immediate
 * disposal when the fiber was already torn down, and failure handling that
 * respects a racing disposal.
 */
class FiberAsyncTest {

    @Test
    void asyncBody_shouldCollectDisposableOnCompletion() {
        Context ctx = Context.create();
        List<String> seq = new java.util.ArrayList<>();
        CompletableFuture<Object> gate = new CompletableFuture<>();
        Fiber fiber = ctx.plugin((c, config) -> {
            seq.add("body");
            return gate;
        });
        assertThat(fiber.state()).isEqualTo(FiberState.LOADING);
        assertThat(seq).containsExactly("body");

        // completing with a disposable collects it (mirrors 'async return 1')
        gate.complete((Disposable) () -> seq.add("dispose"));
        assertThat(fiber.state()).isEqualTo(FiberState.ACTIVE);

        fiber.disposeAsync().join();
        assertThat(seq).containsExactly("body", "dispose");
    }

    @Test
    void asyncBodyDispose_afterFiberDisposed_shouldDisposeResultImmediately() {
        Context ctx = Context.create();
        AtomicInteger disposes = new AtomicInteger();
        CompletableFuture<Object> gate = new CompletableFuture<>();
        Fiber fiber = ctx.plugin((c, config) -> gate);

        // tear the fiber down while the body is still loading
        fiber.disposeAsync().join();
        assertThat(fiber.state()).isEqualTo(FiberState.DISPOSED);
        assertThat(disposes).hasValue(0);

        // the late-completing body must not leak its disposable ('async return 2')
        gate.complete((Disposable) disposes::incrementAndGet);
        assertThat(disposes).as("result disposed immediately, not leaked").hasValue(1);
        assertThat(fiber.state()).isEqualTo(FiberState.DISPOSED);
    }

    @Test
    void asyncBodyFailure_afterDispose_shouldBeIgnored() {
        Context ctx = Context.create();
        CompletableFuture<Object> gate = new CompletableFuture<>();
        Fiber fiber = ctx.plugin((c, config) -> gate);

        fiber.disposeAsync().join();
        gate.completeExceptionally(new IllegalStateException("late failure"));

        // the disposal wins the race: the fiber must stay DISPOSED, not FAILED
        assertThat(fiber.state()).isEqualTo(FiberState.DISPOSED);
        assertThat(fiber.await().join()).isSameAs(fiber);
    }

    @Test
    void asyncBody_concurrentCompleteAndDispose_shouldNotLeak() throws Exception {
        // stress the race between the completing thread and the disposing
        // thread; every completed disposable must be disposed exactly once
        for (int i = 0; i < 200; i++) {
            Context ctx = Context.create();
            AtomicInteger disposes = new AtomicInteger();
            CompletableFuture<Object> gate = new CompletableFuture<>();
            ctx.plugin((c, config) -> gate);

            CompletableFuture<Void> completer =
                    CompletableFuture.runAsync(() -> gate.complete((Disposable) disposes::incrementAndGet));
            CompletableFuture<Void> disposer =
                    CompletableFuture.runAsync(() -> ctx.fiber().disposeAsync().join());
            completer.join();
            disposer.join();

            // whichever side won the race, the disposable was handled exactly once
            assertThat(disposes).hasValue(1);
        }
    }
}
