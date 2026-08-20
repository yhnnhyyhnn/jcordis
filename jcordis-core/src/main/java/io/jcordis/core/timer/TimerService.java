package io.jcordis.core.timer;

import io.jcordis.core.context.Context;
import io.jcordis.core.fiber.EffectResult;
import io.jcordis.core.service.Service;
import io.jcordis.core.util.Disposable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Timer service, mirroring Cordis's {@code TimerService}.
 *
 * <p>Registers itself as the {@code timer} service on construction. Every
 * scheduled task is wrapped in {@code ctx.effect(...)}, so the fiber teardown
 * cancels pending timers automatically (temporal composability).
 */
public class TimerService extends Service {

    /**
     * Lazily created scheduler (proxy/lazy-init pattern): the daemon thread
     * pool only exists once a timer is actually scheduled.
     */
    private volatile ScheduledExecutorService scheduler;

    public TimerService(Context ctx) {
        super(ctx, "timer");
    }

    private ScheduledExecutorService scheduler() {
        ScheduledExecutorService existing = scheduler;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (scheduler == null) {
                scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "jcordis-timer");
                    thread.setDaemon(true);
                    return thread;
                });
            }
            return scheduler;
        }
    }

    /** A callable wrapper with an attached disposer (throttle/debounce result). */
    public static final class Timer implements Runnable {
        private final Runnable callback;
        private final Disposable dispose;

        Timer(Runnable callback, Disposable dispose) {
            this.callback = callback;
            this.dispose = dispose;
        }

        @Override
        public void run() {
            callback.run();
        }

        public void dispose() {
            dispose.dispose();
        }
    }

    /** Schedules a one-shot callback, returning a disposable that cancels it. */
    public Disposable timeout(Runnable callback, long delay) {
        return ctx.effect(runner -> {
            ScheduledFuture<?>[] holder = new ScheduledFuture[1];
            AtomicLong[] state = new AtomicLong[1];
            state[0] = new AtomicLong();
            holder[0] = scheduler().schedule(() -> {
                state[0].set(1);
                callback.run();
            }, delay, TimeUnit.MILLISECONDS);
            return EffectResult.of(() -> {
                if (state[0].get() == 0) {
                    holder[0].cancel(true);
                }
            });
        }, "ctx.timeout()");
    }

    /** Returns a future completing after the delay (cancelled on dispose). */
    public CompletableFuture<Void> timeout(long delay) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        Disposable dispose = ctx.effect(runner -> {
            ScheduledFuture<?> task = scheduler().schedule(
                    () -> future.complete(null), delay, TimeUnit.MILLISECONDS);
            return EffectResult.of(() -> {
                task.cancel(true);
                future.completeExceptionally(new IllegalStateException("Context has been disposed"));
            });
        }, "ctx.timeout()");
        future.whenComplete((value, error) -> dispose.dispose());
        return future;
    }

    /** Schedules a repeating callback, returning a disposable that stops it. */
    public Disposable interval(Runnable callback, long delay) {
        return ctx.effect(runner -> {
            ScheduledFuture<?> task =
                    scheduler().scheduleAtFixedRate(callback, delay, delay, TimeUnit.MILLISECONDS);
            return EffectResult.of(() -> task.cancel(true));
        }, "ctx.interval()");
    }

    /** Returns a throttled wrapper: at most one call per {@code delay} window. */
    public Timer throttle(Runnable callback, long delay, boolean noTrailing) {
        AtomicLong lastCall = new AtomicLong(0);
        ScheduledFuture<?>[] pending = new ScheduledFuture[1];
        Runnable wrapper = () -> {
            long now = System.currentTimeMillis();
            long last = lastCall.get();
            long elapsed = last == 0 ? Long.MAX_VALUE : now - last;
            if (elapsed >= delay) {
                lastCall.set(now);
                callback.run();
            } else if (!noTrailing) {
                long remaining = delay - elapsed;
                if (pending[0] != null) {
                    pending[0].cancel(true);
                }
                pending[0] = scheduler().schedule(() -> {
                    lastCall.set(System.currentTimeMillis());
                    callback.run();
                }, remaining, TimeUnit.MILLISECONDS);
            }
        };
        return new Timer(wrapper, ctx.effect(runner -> EffectResult.of(() -> {
            if (pending[0] != null) {
                pending[0].cancel(true);
            }
        }), "ctx.throttle()"));
    }

    /** Returns a debounced wrapper: callbacks fire after {@code delay} of silence. */
    public Timer debounce(Runnable callback, long delay) {
        ScheduledFuture<?>[] pending = new ScheduledFuture[1];
        Runnable wrapper = () -> {
            if (pending[0] != null) {
                pending[0].cancel(true);
            }
            pending[0] = scheduler().schedule(callback, delay, TimeUnit.MILLISECONDS);
        };
        return new Timer(wrapper, ctx.effect(runner -> EffectResult.of(() -> {
            if (pending[0] != null) {
                pending[0].cancel(true);
            }
        }), "ctx.debounce()"));
    }

    /** Shuts down the scheduler (called on root teardown). */
    public void close() {
        scheduler().shutdownNow();
    }
}