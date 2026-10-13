package io.jcordis.core.timer;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Boundary coverage for the timer service: lazy scheduler life cycle,
 * cancellation races, and the throttle/debounce switch combinations.
 */
class TimerEdgeTest {

    private static void sleep(long ms) {
        try {
            TimeUnit.MILLISECONDS.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void closeShouldBeIdempotentAndAllowRescheduling() {
        TimerService timer = new TimerService(Context.create());
        AtomicInteger calls = new AtomicInteger();

        timer.close();
        timer.close();
        // the scheduler is recreated on demand after a shutdown
        timer.timeout(calls::incrementAndGet, 10);
        sleep(120);

        assertThat(calls).hasValue(1);
        timer.close();
    }

    @Test
    void disposingAnAlreadyFiredTimeoutShouldBeHarmless() {
        TimerService timer = new TimerService(Context.create());
        AtomicInteger calls = new AtomicInteger();

        io.jcordis.core.util.Disposable disposable = timer.timeout(calls::incrementAndGet, 10);
        sleep(120);
        disposable.dispose();

        assertThat(calls).as("the callback already ran").hasValue(1);
        timer.close();
    }

    @Test
    void disposingAPendingTimeoutShouldCancelIt() {
        TimerService timer = new TimerService(Context.create());
        AtomicInteger calls = new AtomicInteger();

        io.jcordis.core.util.Disposable disposable = timer.timeout(calls::incrementAndGet, 80);
        disposable.dispose();
        sleep(150);

        assertThat(calls).hasValue(0);
        timer.close();
    }

    @Test
    void futureTimeoutShouldFailWhenDisposedEarly() {
        Context root = Context.create();
        TimerService timer = new TimerService(root);

        CompletableFuture<Void> pending = timer.timeout(500);
        root.fiber().disposeAsync().join();

        // a disposed timeout completes exceptionally instead of hanging
        assertThat(pending).isCompletedExceptionally();
        timer.close();
    }

    @Test
    void throttleWithoutTrailingShouldDropCallsInsideTheWindow() {
        TimerService timer = new TimerService(Context.create());
        AtomicInteger calls = new AtomicInteger();
        TimerService.Timer throttled = timer.throttle(calls::incrementAndGet, 100, true);

        throttled.run(); // first call fires immediately
        throttled.run(); // inside the window: dropped, no trailing call
        sleep(200);

        assertThat(calls).hasValue(1);
        timer.close();
    }

    @Test
    void throttleShouldReArmAfterTheWindow() {
        TimerService timer = new TimerService(Context.create());
        AtomicInteger calls = new AtomicInteger();
        TimerService.Timer throttled = timer.throttle(calls::incrementAndGet, 50, true);

        throttled.run();
        sleep(120);
        throttled.run();
        sleep(120);

        assertThat(calls).hasValue(2);
        timer.close();
    }

    @Test
    void disposingAThrottleShouldStopTrailingCalls() {
        TimerService timer = new TimerService(Context.create());
        AtomicInteger calls = new AtomicInteger();
        TimerService.Timer throttled = timer.throttle(calls::incrementAndGet, 100, false);

        throttled.run();
        throttled.dispose();
        sleep(200);

        assertThat(calls).as("the trailing call was cancelled").hasValue(1);
        timer.close();
    }

    @Test
    void debounceAfterDisposeShouldNotSchedule() {
        TimerService timer = new TimerService(Context.create());
        AtomicInteger calls = new AtomicInteger();
        TimerService.Timer debounced = timer.debounce(calls::incrementAndGet, 40);

        debounced.dispose();
        debounced.run();
        sleep(120);

        assertThat(calls).hasValue(0);
        timer.close();
    }

    @Test
    void throttleAfterDisposeShouldNotExecuteEvenOutsideTheWindow() {
        TimerService timer = new TimerService(Context.create());
        AtomicInteger calls = new AtomicInteger();
        TimerService.Timer throttled = timer.throttle(calls::incrementAndGet, 60, false);

        throttled.run();
        throttled.dispose();
        sleep(120);

        // the window has elapsed, so the immediate branch would fire without
        // the disposed check (mirrors Cordis #161)
        throttled.run();

        assertThat(calls).as("a disposed throttle never runs again").hasValue(1);
        timer.close();
    }

    @Test
    void intervalShouldKeepFiringUntilDisposed() {
        TimerService timer = new TimerService(Context.create());
        AtomicInteger calls = new AtomicInteger();

        io.jcordis.core.util.Disposable disposable = timer.interval(calls::incrementAndGet, 20);
        sleep(150);
        disposable.dispose();
        int settled = calls.get();
        sleep(100);

        assertThat(settled).isGreaterThanOrEqualTo(2);
        assertThat(calls).as("no ticks after dispose").hasValue(settled);
        timer.close();
    }
}
