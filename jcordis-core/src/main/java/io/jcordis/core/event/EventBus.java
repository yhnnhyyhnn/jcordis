package io.jcordis.core.event;

import io.jcordis.core.context.Context;
import io.jcordis.core.fiber.EffectResult;
import io.jcordis.core.fiber.Fiber;
import io.jcordis.core.util.Disposable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The event hub of a context tree, mirroring Cordis's {@code EventsService}.
 *
 * <p>Supports five dispatch modes:
 * <ul>
 *   <li>{@link #emit} — synchronous fan-out;</li>
 *   <li>{@link #parallel} — concurrent fan-out collecting every failure;</li>
 *   <li>{@link #serial} — sequential fan-out short-circuiting on a bailed value;</li>
 *   <li>{@link #bail} — synchronous fan-out short-circuiting on a bailed value;</li>
 *   <li>{@link #waterfall} — chained fan-out where each listener may continue
 *       or short-circuit the chain.</li>
 * </ul>
 */
public final class EventBus {

    private final Map<String, List<Hook>> hooks = new ConcurrentHashMap<>();

    public EventBus(Context ctx) {
        // intercepts non-global 'internal/update' registrations and stores the
        // listener on the fiber that registered it (fiber-local hooks); the
        // registration is itself an effect on that fiber, so a reload (which
        // re-runs the body and re-registers) never accumulates stale hooks
        // (mirrors Cordis #56)
        on(
                ctx,
                "internal/listener",
                (thisArg, args) -> {
                    String name = (String) args[0];
                    EventHandler listener = (EventHandler) args[1];
                    EventOptions options = (EventOptions) args[2];
                    if ("internal/update".equals(name) && !options.global()) {
                        Context owner = (Context) thisArg;
                        List<EventHandler> fiberHooks = owner.fiber()
                                .hooks()
                                .computeIfAbsent("internal/update", key -> new CopyOnWriteArrayList<>());
                        if (options.prepend()) {
                            fiberHooks.add(0, listener);
                        } else {
                            fiberHooks.add(listener);
                        }
                        return owner.fiber()
                                .effect(
                                        runner -> EffectResult.of(() -> fiberHooks.remove(listener)),
                                        "ctx.on(\"internal/update\")");
                    }
                    return null;
                },
                EventOptions.of());

        // global (and prepended) 'internal/update' handler: chains the
        // fiber-local listeners, then falls back to the waterfall tail
        on(
                ctx,
                "internal/update",
                (thisArg, args) -> {
                    Fiber fiber = (Fiber) thisArg;
                    Object config = args[0];
                    @SuppressWarnings("unchecked")
                    Supplier<Object> next = (Supplier<Object>) args[1];
                    List<EventHandler> callbacks =
                            new ArrayList<>(fiber.hooks().getOrDefault("internal/update", List.of()));
                    Object[] chainArgs = {config, null};
                    Supplier<Object>[] holder = new Supplier[1];
                    holder[0] = () -> {
                        EventHandler callback = callbacks.isEmpty() ? null : callbacks.remove(0);
                        if (callback == null) {
                            return next.get();
                        }
                        return callback.invoke(fiber, chainArgs);
                    };
                    chainArgs[1] = holder[0];
                    return holder[0].get();
                },
                EventOptions.of(true, true));
    }

    // ----- registration -----

    public Disposable on(Context ctx, String name, EventHandler listener) {
        return on(ctx, name, listener, EventOptions.of());
    }

    public Disposable on(Context ctx, String name, EventHandler listener, EventOptions options) {
        ctx.fiber().assertActive();
        Object result = bail(ctx, "internal/listener", name, listener, options);
        if (result != null) {
            return (Disposable) result;
        }
        List<Hook> target = hooks.computeIfAbsent(name, key -> new CopyOnWriteArrayList<>());
        return register(ctx, name, target, listener, options, "ctx.on(\"" + name + "\")");
    }

    public Disposable once(Context ctx, String name, EventHandler listener) {
        return once(ctx, name, listener, EventOptions.of());
    }

    public Disposable once(Context ctx, String name, EventHandler listener, EventOptions options) {
        AtomicReference<Disposable> ref = new AtomicReference<>();
        AtomicBoolean armed = new AtomicBoolean(true);
        Disposable wrapper = on(
                ctx,
                name,
                (thisArg, args) -> {
                    // CAS guards the double-dispatch window: two threads
                    // emitting the same event must run the listener once
                    if (!armed.compareAndSet(true, false)) return null;
                    Disposable disposable = ref.get();
                    if (disposable != null) {
                        disposable.dispose();
                    }
                    return listener.invoke(thisArg, args);
                },
                options);
        ref.set(wrapper);
        return wrapper;
    }

    private Disposable register(
            Context ctx, String name, List<Hook> target, EventHandler listener, EventOptions options, String label) {
        return ctx.fiber()
                .effect(
                        runner -> {
                            if (options.prepend()) {
                                target.add(0, new Hook(ctx, listener, true, options.global()));
                            } else {
                                target.add(new Hook(ctx, listener, false, options.global()));
                            }
                            return EffectResult.of(() -> unregister(name, target, listener));
                        },
                        label);
    }

    private boolean unregister(String name, List<Hook> target, EventHandler callback) {
        // one disposable removes one registration: a listener registered twice
        // owns two disposables, and each must only remove its own slot
        // (mirrors Cordis's findIndex + splice). The one-shot latch inside the
        // atomically-locked removeIf keeps concurrent unregisters safe.
        AtomicBoolean firstMatch = new AtomicBoolean(true);
        List<Hook> matched = new ArrayList<>();
        boolean removed = target.removeIf(hook -> {
            if (hook.callback() == callback && firstMatch.compareAndSet(true, false)) {
                matched.add(hook);
                return true;
            }
            return false;
        });
        // flag the removed registration: an in-flight dispatch holding a
        // snapshot of the list must see the removal (mirrors Cordis #242)
        for (Hook hook : matched) {
            hook.removed = true;
        }
        if (removed && target.isEmpty()) {
            // drop empty lists so dynamically named events do not accumulate
            // in the hook table (mirrors Cordis's `delete this._hooks[name]`);
            // the remove(key, value) form keeps a concurrent re-registration
            // of the same name intact
            hooks.remove(name, target);
        }
        return removed;
    }

    // ----- dispatch -----

    private List<Hook> resolve(String mode, String name, Object thisArg, Object[] args) {
        if (!name.startsWith("internal/")
                && !hooks.getOrDefault("internal/dispatch", List.of()).isEmpty()) {
            emit((Object) null, "internal/dispatch", mode, name, args, thisArg);
        }
        EventFilter filter = thisArg instanceof EventFilter eventFilter ? eventFilter : null;
        List<Hook> candidates = hooks.get(name);
        if (candidates == null) {
            return List.of();
        }
        List<Hook> snapshot = new ArrayList<>();
        for (Hook hook : candidates) {
            if (hook.global() || filter == null || filter.test(hook.ctx())) {
                snapshot.add(hook);
            }
        }
        return snapshot;
    }

    public void emit(Object thisArg, String name, Object... args) {
        for (Hook hook : resolve("emit", name, thisArg, args)) {
            if (hook.removed) continue;
            hook.callback().invoke(thisArg, args);
        }
    }

    public Object bail(Object thisArg, String name, Object... args) {
        for (Hook hook : resolve("bail", name, thisArg, args)) {
            if (hook.removed) continue;
            Object result = hook.callback().invoke(thisArg, args);
            if (isBailed(result)) {
                return result;
            }
        }
        return null;
    }

    public CompletableFuture<Void> parallel(Object thisArg, String name, Object... args) {
        List<Hook> callbacks = resolve("parallel", name, thisArg, args);
        if (callbacks.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        List<CompletableFuture<Object>> futures = new ArrayList<>();
        for (Hook hook : callbacks) {
            CompletableFuture<Object> future = new CompletableFuture<>();
            try {
                // a member disposed between snapshot and its turn is skipped,
                // not invoked (mirrors Cordis #242)
                Object result = hook.removed ? null : hook.callback().invoke(thisArg, args);
                if (result instanceof CompletableFuture<?> pending) {
                    pending.whenComplete((value, error) -> {
                        if (error != null) {
                            future.completeExceptionally(error);
                        } else {
                            future.complete(value);
                        }
                    });
                } else {
                    future.complete(result);
                }
            } catch (Throwable error) {
                future.completeExceptionally(error);
            }
            futures.add(future);
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .handle((ignored, error) -> {
                    List<Throwable> errors = new ArrayList<>();
                    for (CompletableFuture<Object> future : futures) {
                        try {
                            future.join();
                        } catch (CompletionException e) {
                            errors.add(unwrap(e));
                        }
                    }
                    if (!errors.isEmpty()) {
                        // naming the failing event saves the caller from
                        // correlating the members by hand (mirrors Cordis #205)
                        throw new AggregateError(errors, "one or more listeners of event \"" + name + "\" failed");
                    }
                    return null;
                });
    }

    public CompletableFuture<Object> serial(Object thisArg, String name, Object... args) {
        List<Hook> callbacks = resolve("serial", name, thisArg, args);
        AtomicBoolean stopped = new AtomicBoolean(false);
        AtomicReference<Object> result = new AtomicReference<>();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (Hook hook : callbacks) {
            chain = chain.thenCompose(ignored -> {
                if (stopped.get() || hook.removed) {
                    return CompletableFuture.completedFuture(null);
                }
                try {
                    Object value = hook.callback().invoke(thisArg, args);
                    CompletableFuture<Object> pending = value instanceof CompletableFuture<?> future
                            ? cast(future)
                            : CompletableFuture.completedFuture(value);
                    return pending.thenAccept(v -> {
                        if (isBailed(v)) {
                            stopped.set(true);
                            result.set(v);
                        }
                    });
                } catch (Throwable error) {
                    return CompletableFuture.failedFuture(error);
                }
            });
        }
        return chain.thenApply(ignored -> result.get());
    }

    public Object waterfall(Object thisArg, String name, Object[] args, Function<Object[], Object> inner) {
        List<Hook> callbacks = new ArrayList<>(resolve("waterfall", name, thisArg, args));
        return dispatch(callbacks, thisArg, args, inner);
    }

    /**
     * Dispatches one waterfall stage. Each listener receives its <em>own</em>
     * {@code next} guarded so it can only be invoked once — a second call
     * throws (mirrors Cordis's waterfall continuation guard): a listener that
     * calls {@code next()} twice would otherwise re-enter the chain. A member
     * disposed after the snapshot passes the buck straight to {@code next}
     * (mirrors Cordis #242).
     */
    private Object dispatch(List<Hook> callbacks, Object thisArg, Object[] args, Function<Object[], Object> inner) {
        if (callbacks.isEmpty()) {
            return inner.apply(args);
        }
        Hook hook = callbacks.remove(0);
        if (hook.removed) {
            return dispatch(callbacks, thisArg, args, inner);
        }
        Object[] chainArgs = Arrays.copyOf(args, args.length + 1);
        AtomicBoolean called = new AtomicBoolean(false);
        chainArgs[chainArgs.length - 1] = (Supplier<Object>) () -> {
            if (!called.compareAndSet(false, true)) {
                throw new IllegalStateException("next() called multiple times");
            }
            return dispatch(callbacks, thisArg, args, inner);
        };
        return hook.callback().invoke(thisArg, chainArgs);
    }

    static boolean isBailed(Object value) {
        return value != null && !Boolean.FALSE.equals(value);
    }

    /** Snapshot of non-empty hook lists keyed by event name (for tests). */
    public Map<String, Integer> hookCounts() {
        Map<String, Integer> result = new java.util.HashMap<>();
        for (Map.Entry<String, List<Hook>> entry : hooks.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                result.put(entry.getKey(), entry.getValue().size());
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static CompletableFuture<Object> cast(CompletableFuture<?> future) {
        return (CompletableFuture<Object>) future;
    }

    private static Throwable unwrap(Throwable error) {
        return error instanceof CompletionException e && e.getCause() != null ? e.getCause() : error;
    }
}
