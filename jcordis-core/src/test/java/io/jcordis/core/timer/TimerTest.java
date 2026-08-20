package io.jcordis.core.timer;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import io.jcordis.core.util.Disposable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Translates Cordis timer.spec.ts core semantics: timeout/interval/throttle/debounce. */
class TimerTest {

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Context setup() {
        Context root = Context.create();
        new TimerService(root);
        return root;
    }

    @Test
    void timeoutBasic() {
        Context root = setup();
        AtomicInteger calls = new AtomicInteger();
        root.plugin((ctx, config) -> {
            ctx.get("timer");
            return null;
        }).await().join();
        TimerService timer = (TimerService) root.get("timer");

        timer.timeout(calls::incrementAndGet, 50);
        assertThat(calls).hasValue(0);
        sleep(120);
        assertThat(calls).hasValue(1);
        sleep(120);
        assertThat(calls).hasValue(1);
    }

    @Test
    void timeoutDispose() {
        Context root = setup();
        AtomicInteger calls = new AtomicInteger();
        TimerService timer = (TimerService) root.get("timer");

        Disposable dispose = timer.timeout(calls::incrementAndGet, 50);
        assertThat(calls).hasValue(0);
        dispose.dispose();
        sleep(120);
        assertThat(calls).hasValue(0);
    }

    @Test
    void timeoutPromise() {
        Context root = setup();
        TimerService timer = (TimerService) root.get("timer");

        CompletableFuture<Void> future = timer.timeout(30);
        sleep(80);
        assertThat(future.isDone()).isTrue();
        assertThat(future.isCompletedExceptionally()).isFalse();
    }

    @Test
    void intervalBasic() {
        Context root = setup();
        AtomicInteger calls = new AtomicInteger();
        TimerService timer = (TimerService) root.get("timer");

        Disposable dispose = timer.interval(calls::incrementAndGet, 30);
        assertThat(calls).hasValue(0);
        sleep(80);
        assertThat(calls.get()).isGreaterThanOrEqualTo(1);
        int before = calls.get();
        dispose.dispose();
        sleep(100);
        assertThat(calls).hasValue(before);
    }

    @Test
    void intervalCancelledOnFiberDispose() {
        Context root = Context.create();
        AtomicInteger calls = new AtomicInteger();
        io.jcordis.core.fiber.Fiber fiber = root.plugin((ctx, config) -> {
            new TimerService(ctx);
            TimerService timer = (TimerService) ctx.get("timer");
            timer.interval(calls::incrementAndGet, 20);
            return null;
        }).await().join();

        sleep(100);
        assertThat(calls.get()).isGreaterThanOrEqualTo(1);
        int before = calls.get();
        fiber.disposeAsync().join();
        sleep(50);
        int after = calls.get();
        sleep(80);
        assertThat(calls.get()).isEqualTo(after);
        assertThat(after - before).isLessThanOrEqualTo(1);
    }

    @Test
    void throttleBasic() {
        Context root = setup();
        AtomicInteger calls = new AtomicInteger();
        TimerService timer = (TimerService) root.get("timer");

        TimerService.Timer throttled = timer.throttle(calls::incrementAndGet, 100, false);
        throttled.run();
        assertThat(calls).hasValue(1);
        sleep(30);
        throttled.run();
        assertThat(calls).hasValue(1);
        sleep(150);
        assertThat(calls).hasValue(2);
    }

    @Test
    void throttleTrailing() {
        Context root = setup();
        AtomicInteger calls = new AtomicInteger();
        TimerService timer = (TimerService) root.get("timer");

        TimerService.Timer throttled = timer.throttle(calls::incrementAndGet, 100, true);
        throttled.run();
        assertThat(calls).hasValue(1);
        sleep(30);
        throttled.run();
        assertThat(calls).hasValue(1);
        sleep(150);
        assertThat(calls).hasValue(1);
    }

    @Test
    void debounceBasic() {
        Context root = setup();
        AtomicInteger calls = new AtomicInteger();
        TimerService timer = (TimerService) root.get("timer");

        TimerService.Timer debounced = timer.debounce(calls::incrementAndGet, 30);
        debounced.run();
        assertThat(calls).hasValue(0);
        sleep(100);
        assertThat(calls).hasValue(1);
        debounced.dispose();
    }

    @Test
    void debounceResetsOnRepeat() {
        Context root = setup();
        AtomicInteger calls = new AtomicInteger();
        TimerService timer = (TimerService) root.get("timer");

        TimerService.Timer debounced = timer.debounce(calls::incrementAndGet, 50);
        debounced.run();
        sleep(30);
        debounced.run();
        assertThat(calls).hasValue(0);
        sleep(80);
        assertThat(calls).hasValue(1);
        debounced.dispose();
    }
}