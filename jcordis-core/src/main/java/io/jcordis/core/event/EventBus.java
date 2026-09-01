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
        // listener on the fiber that registered it (fiber-local hooks)
        on(
                ctx,
                "internal/listener",
                (thisArg, args) -> {
                    String name = (String) args[0];
                    EventHandler listener = (EventHandler) args[1];
                    EventOptions options = (EventOptions) args[2];
                    if ("internal/update".equals(name) && !options.global()) {
                        List<EventHandler> fiberHooks = ((Context) thisArg)
                                .fiber()
                                .hooks()
                                .computeIfAbsent("internal/update", key -> new CopyOnWriteArrayList<>());
                        if (options.prepend()) {
                            fiberHooks.add(0, listener);
                        } else {
                            fiberHooks.add(listener);
                        }
                        return (Disposable) () -> fiberHooks.remove(listener);
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
                    boolean noSave = (Boolean) args[1];
                    @SuppressWarnings("unchecked")
                    Supplier<Object> next = (Supplier<Object>) args[2];
                    List<EventHandler> callbacks =
                            new ArrayList<>(fiber.hooks().getOrDefault("internal/update", List.of()));
                    Object[] chainArgs = {config, noSave, null};
                    Supplier<Object>[] holder = new Supplier[1];
                    holder[0] = () -> {
                        EventHandler callback = callbacks.isEmpty() ? null : callbacks.remove(0);
                        if (callback == null) {
                            return next.get();
                        }
                        return callback.invoke(fiber, chainArgs);
                    };
                    chainArgs[2] = holder[0];
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
        return register(ctx, target, listener, options, "ctx.on(\"" + name + "\")");
    }

    public Disposable once(Context ctx, String name, EventHandler listener) {
        return once(ctx, name, listener, EventOptions.of());
    }

    public Disposable once(Context ctx, String name, EventHandler listener, EventOptions options) {
        AtomicReference<Disposable> ref = new AtomicReference<>();
        Disposable wrapper = on(
                ctx,
                name,
                (thisArg, args) -> {
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
            Context ctx, List<Hook> target, EventHandler listener, EventOptions options, String label) {
        return ctx.fiber()
                .effect(
                        runner -> {
                            if (options.prepend()) {
                                target.add(0, new Hook(ctx, listener, true, options.global()));
                            } else {
                                target.add(new Hook(ctx, listener, false, options.global()));
                            }
                            return EffectResult.of(() -> unregister(target, listener));
                        },
                        label);
    }

    private boolean unregister(List<Hook> target, EventHandler callback) {
        for (int i = 0; i < target.size(); i++) {
            if (target.get(i).callback() == callback) {
                target.remove(i);
                return true;
            }
        }
        return false;
    }

    // ----- dispatch -----

    private List<EventHandler> resolve(String mode, String name, Object thisArg, Object[] args) {
        if (!name.startsWith("internal/")
                && !hooks.getOrDefault("internal/dispatch", List.of()).isEmpty()) {
            emit((Object) null, "internal/dispatch", mode, name, args, thisArg);
        }
        EventFilter filter = thisArg instanceof EventFilter eventFilter ? eventFilter : null;
        List<Hook> candidates = hooks.get(name);
        if (candidates == null) {
            return List.of();
        }
        List<EventHandler> callbacks = new ArrayList<>();
        for (Hook hook : candidates) {
            if (hook.global() || filter == null || filter.test(hook.ctx())) {
                callbacks.add(hook.callback());
            }
        }
        return callbacks;
    }

    public void emit(Object thisArg, String name, Object... args) {
        for (EventHandler callback : resolve("emit", name, thisArg, args)) {
            callback.invoke(thisArg, args);
        }
    }

    public Object bail(Object thisArg, String name, Object... args) {
        for (EventHandler callback : resolve("bail", name, thisArg, args)) {
            Object result = callback.invoke(thisArg, args);
            if (isBailed(result)) {
                return result;
            }
        }
        return null;
    }

    public CompletableFuture<Void> parallel(Object thisArg, String name, Object... args) {
        List<EventHandler> callbacks = resolve("parallel", name, thisArg, args);
        if (callbacks.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        List<CompletableFuture<Object>> futures = new ArrayList<>();
        for (EventHandler callback : callbacks) {
            CompletableFuture<Object> future = new CompletableFuture<>();
            try {
                Object result = callback.invoke(thisArg, args);
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
                        throw new AggregateError(errors);
                    }
                    return null;
                });
    }

    public CompletableFuture<Object> serial(Object thisArg, String name, Object... args) {
        List<EventHandler> callbacks = resolve("serial", name, thisArg, args);
        AtomicBoolean stopped = new AtomicBoolean(false);
        AtomicReference<Object> result = new AtomicReference<>();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (EventHandler callback : callbacks) {
            chain = chain.thenCompose(ignored -> {
                if (stopped.get()) {
                    return CompletableFuture.completedFuture(null);
                }
                try {
                    Object value = callback.invoke(thisArg, args);
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
        List<EventHandler> callbacks = new ArrayList<>(resolve("waterfall", name, thisArg, args));
        Object[] chainArgs = Arrays.copyOf(args, args.length + 1);
        Supplier<Object> next = () -> {
            if (callbacks.isEmpty()) {
                return inner.apply(chainArgs);
            }
            EventHandler callback = callbacks.remove(0);
            return callback.invoke(thisArg, chainArgs);
        };
        chainArgs[chainArgs.length - 1] = next;
        return next.get();
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
