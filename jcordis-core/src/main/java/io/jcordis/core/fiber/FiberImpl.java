package io.jcordis.core.fiber;

import io.jcordis.core.context.Context;
import io.jcordis.core.event.EventHandler;
import io.jcordis.core.reflect.Impl;
import io.jcordis.core.registry.PluginRuntime;
import io.jcordis.core.registry.RegistryService;
import io.jcordis.core.util.Disposable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Default {@link Fiber} implementation.
 *
 * <p>Two kinds exist:
 * <ul>
 *   <li>the <em>root fiber</em> (uid 0) owning the context tree's services and
 *       teardown chain;</li>
 *   <li><em>plugin fibers</em> created by {@code ctx.plugin()}, whose lifecycle
 *       is registered as an effect on the parent fiber — disposing the parent
 *       cascades to every child plugin in reverse order.</li>
 * </ul>
 *
 * <p>Effect semantics mirror Cordis: each {@link #effect} call runs its runner
 * immediately and collects the returned disposables; the returned wrapper is
 * idempotent; fiber teardown disposes all collected disposables in
 * <em>reverse</em> registration order. Plugin bodies only execute when every
 * injected service is available (their impl's fiber is {@code ACTIVE}), and
 * re-execute or unload when that dependency set changes.
 */
public final class FiberImpl implements Fiber {

    private static final String INACTIVE = "__INACTIVE__";

    private final FiberImpl parent;
    private final String name;
    private final List<Disposable> disposables = new ArrayList<>();
    private final List<EffectMeta> effectMetas = new ArrayList<>();
    private final AtomicBoolean disposed = new AtomicBoolean(false);
    private final Map<String, List<EventHandler>> hooks = new ConcurrentHashMap<>();
    private final Map<String, Object> inject = new HashMap<>();
    private final Map<String, Impl> store = new HashMap<>();
    private final PluginRuntime runtime;

    /**
     * Monitor lock guarding the effect collections (concurrency pattern).
     *
     * <p>Async plugin bodies complete on arbitrary threads; every mutation of
     * {@link #disposables}/{@link #effectMetas} happens under this lock so the
     * loader thread and the completing thread cannot corrupt each other. User
     * callbacks are never invoked while holding it (see {@link #drainEffects}).
     */
    private final Object lifecycle = new Object();

    private volatile Context ctx;
    private volatile int uid;
    private volatile FiberState state;
    private volatile Object config;
    private volatile Object entry;
    private volatile String epoch;
    private volatile CompletableFuture<Void> inertia;
    /** Body failure captured by {@link #reload}; cleared only by {@link #update}. */
    private volatile Throwable error;

    @Override
    public Object entry() {
        return entry;
    }

    @Override
    public void setEntry(Object entry) {
        this.entry = entry;
    }

    /** Creates the root fiber: uid 0, immediately active. */
    public static FiberImpl root(Context ctx) {
        return new FiberImpl(ctx, null, null, Map.of());
    }

    /** Creates a plugin fiber and registers its lifecycle on the parent fiber. */
    public FiberImpl(Context parent, Object config, Map<String, Object> inject, PluginRuntime runtime) {
        this.parent = (FiberImpl) parent.fiber();
        this.ctx = parent.child(this);
        this.name = null;
        this.runtime = runtime;
        this.config = config;
        this.inject.putAll(inject);
        this.uid = parent.registry().counter();
        this.state = FiberState.PENDING;
        this.epoch = INACTIVE;

        // resolve initial dependencies before the lifecycle effect runs
        for (String name : inject.keySet()) {
            checkImpl(name);
        }

        // register the plugin lifecycle as an effect on the parent fiber
        parent.fiber()
                .effect(
                        runner -> {
                            runtime.fibers().add(this);
                            refresh();
                            return EffectResult.of(() -> unload());
                        },
                        "ctx.plugin()");
    }

    private FiberImpl(Context ctx, String name, Object config, Map<String, Object> inject) {
        this.parent = null;
        this.ctx = ctx;
        this.name = name;
        this.runtime = null;
        this.config = config;
        this.inject.putAll(inject);
        this.uid = 0;
        this.state = FiberState.ACTIVE;
        this.epoch = "";
    }

    @Override
    public int uid() {
        return uid;
    }

    @Override
    public String name() {
        FiberImpl fiber = this;
        while (fiber != null) {
            if (fiber.runtime != null && fiber.runtime.name() != null) {
                return fiber.runtime.name();
            }
            if (fiber.name != null) {
                return fiber.name;
            }
            fiber = fiber.parent;
        }
        return "root";
    }

    @Override
    public FiberState state() {
        return state;
    }

    @Override
    public Context ctx() {
        return ctx;
    }

    @Override
    public Object config() {
        return config;
    }

    @Override
    public PluginRuntime runtime() {
        return runtime;
    }

    @Override
    public Map<String, Object> inject() {
        return inject;
    }

    public void updateConfig(Object config) {
        this.config = config;
    }

    @Override
    public Map<String, List<EventHandler>> hooks() {
        return hooks;
    }

    @Override
    public void rebindContext(Context ctx) {
        this.ctx = ctx.child(this);
    }

    @Override
    public CompletableFuture<Void> inertia() {
        return inertia;
    }

    @Override
    public void update(Object config, boolean noSave) {
        assertActive();
        ctx.events().waterfall(this, "internal/update", new Object[] {config, noSave}, args -> {
            updateConfig(config);
            // a failed fiber only recovers through update() (mirrors Cordis)
            error = null;
            return restart();
        });
    }

    @Override
    public CompletableFuture<Fiber> restart() {
        assertActive();
        if (runtime == null) {
            return CompletableFuture.completedFuture(this);
        }
        setEpoch(INACTIVE);
        refresh();
        return await();
    }

    /** Adds a disposable under the lifecycle lock. */
    private void addDisposable(Disposable disposable) {
        synchronized (lifecycle) {
            disposables.add(disposable);
        }
    }

    /**
     * Snapshots the registered effects under the lock, then disposes them in
     * reverse order <em>outside</em> the lock — user callbacks are never
     * invoked while the monitor is held.
     */
    private void drainEffects() {
        List<Disposable> snapshot;
        synchronized (lifecycle) {
            snapshot = new ArrayList<>(disposables);
            disposables.clear();
            effectMetas.clear();
        }
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            snapshot.get(i).dispose();
        }
    }

    @Override
    public Disposable effect(EffectRunner runner, String label) {
        assertActive();
        List<Disposable> collected = new ArrayList<>();
        // nested effects registered by this runner grow the fiber's lists; on a
        // throwing runner they must be disposed immediately (mirrors Cordis's
        // `_execute` catch → `dispose()` for already-collected disposables)
        int registeredBefore;
        synchronized (lifecycle) {
            registeredBefore = disposables.size();
        }
        EffectResult result;
        try {
            result = runner.run(ctx);
        } catch (Throwable error) {
            disposeTail(registeredBefore);
            throw error;
        }
        EffectMeta meta = new EffectMeta(label, new ArrayList<>());
        if (result instanceof EffectResult.Noop) {
            // no disposables
        } else if (result instanceof EffectResult.Single single) {
            collectEffect(collected, meta, single.disposable());
        } else if (result instanceof EffectResult.Multiple multiple) {
            for (Disposable disposable : multiple.disposables()) {
                collectEffect(collected, meta, disposable);
            }
        }
        AtomicBoolean once = new AtomicBoolean(false);
        EffectDisposable wrapper = new EffectDisposable(meta, () -> {
            if (!once.compareAndSet(false, true)) return;
            for (int i = collected.size() - 1; i >= 0; i--) {
                collected.get(i).dispose();
            }
        });
        synchronized (lifecycle) {
            disposables.add(wrapper);
            effectMetas.add(meta);
        }
        return wrapper;
    }

    /** Disposes (reverse order) and removes the effect tail starting at {@code from}. */
    private void disposeTail(int from) {
        List<Disposable> snapshot;
        synchronized (lifecycle) {
            if (disposables.size() <= from) {
                while (effectMetas.size() > from) {
                    effectMetas.remove(effectMetas.size() - 1);
                }
                return;
            }
            snapshot = new ArrayList<>(disposables.subList(from, disposables.size()));
            while (disposables.size() > from) {
                disposables.remove(disposables.size() - 1);
            }
            while (effectMetas.size() > from) {
                effectMetas.remove(effectMetas.size() - 1);
            }
        }
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            snapshot.get(i).dispose();
        }
    }

    private void collectEffect(List<Disposable> collected, EffectMeta meta, Disposable disposable) {
        collected.add(disposable);
        if (disposable instanceof EffectDisposable child) {
            meta.children().add(child.meta());
            // the nested effect is now owned by the outer disposer: remove it
            // from the fiber's own list so teardown does not double-manage it
            // (mirrors Cordis's `_disposables.delete(dispose)` in collect)
            synchronized (lifecycle) {
                disposables.remove(child);
                effectMetas.remove(child.meta());
            }
        }
    }

    /** A disposable carrying its effect metadata (so nested effects become children). */
    private static final class EffectDisposable implements Disposable {
        private final EffectMeta meta;
        private final Disposable delegate;

        EffectDisposable(EffectMeta meta, Disposable delegate) {
            this.meta = meta;
            this.delegate = delegate;
        }

        EffectMeta meta() {
            return meta;
        }

        @Override
        public void dispose() {
            delegate.dispose();
        }
    }

    @Override
    public List<EffectMeta> getEffects() {
        synchronized (lifecycle) {
            return List.copyOf(effectMetas);
        }
    }

    @Override
    public void assertActive() {
        if (disposed.get()) {
            throw new CordisError(CordisError.Code.INACTIVE_EFFECT);
        }
    }

    @Override
    public void checkImpl(String name) {
        Impl impl = ctx.reflect().getImpl(name, true, ctx);
        if (impl == null) {
            store.remove(name);
            return;
        }
        if (impl.check() != null) {
            try {
                if (!impl.check().test(impl.value())) {
                    store.remove(name);
                    return;
                }
            } catch (Throwable error) {
                impl.fiber().ctx().logger().error(error);
                store.remove(name);
                return;
            }
        }
        store.put(name, impl);
    }

    @Override
    public void refresh() {
        if (runtime == null) return;
        StringBuilder epoch = new StringBuilder();
        for (String name : inject.keySet()) {
            Impl impl = store.get(name);
            if (impl == null) {
                setEpoch(INACTIVE);
                return;
            }
            epoch.append(':').append(impl.fiber().uid());
        }
        setEpoch(epoch.toString());
    }

    private void setEpoch(String newEpoch) {
        // a failed fiber only recovers through update(), which clears the error
        if (error != null) return;
        if (Objects.equals(newEpoch, epoch)) return;
        String oldEpoch = epoch;
        epoch = newEpoch;
        transition(oldEpoch, newEpoch);
    }

    /**
     * State transition on dependency-epoch change (state pattern): moving
     * INACTIVE → ready reloads the plugin body; ready → INACTIVE unloads it.
     */
    private void transition(String oldEpoch, String newEpoch) {
        boolean wasInactive = oldEpoch.equals(INACTIVE);
        boolean isInactive = newEpoch.equals(INACTIVE);
        if (wasInactive && !isInactive) {
            reload();
        } else if (!wasInactive && isInactive) {
            unloadBody();
        }
    }

    /**
     * Sets the lifecycle state, emitting {@code internal/status} with the old
     * value (mirrors Cordis's {@code _updateState}). Constructor-time and
     * root-fiber assignments set the field directly.
     */
    private void transitionState(FiberState next) {
        FiberState old = state;
        if (old == next) return;
        state = next;
        ctx.events().emit((Object) null, "internal/status", this, old);
    }

    private void reload() {
        transitionState(FiberState.LOADING);
        try {
            Object result = runtime.callback().apply(ctx, config);
            if (result instanceof CompletableFuture<?> pending) {
                @SuppressWarnings("unchecked")
                CompletableFuture<Object> future = (CompletableFuture<Object>) pending;
                inertia = future.handle((value, failure) -> {
                    if (failure != null) {
                        // body failed; a disposal racing the completion wins
                        if (!disposed.get()) {
                            handleBodyFailure(unwrapCause(failure));
                        }
                    } else if (value instanceof Disposable disposable) {
                        // the collect decision is atomic with the drain in
                        // unload(): if the fiber was disposed in the meantime,
                        // the produced disposable must not leak — dispose it
                        // immediately (mirrors dispose.spec 'async return 2')
                        boolean collected;
                        synchronized (lifecycle) {
                            collected = !disposed.get();
                            if (collected) {
                                disposables.add(disposable);
                            }
                        }
                        if (collected) {
                            transitionState(FiberState.ACTIVE);
                            notifyServices();
                        } else {
                            disposable.dispose();
                        }
                    } else {
                        transitionState(FiberState.ACTIVE);
                        notifyServices();
                    }
                    // mark the load as settled so tree task tracking sees it
                    inertia = null;
                    return null;
                });
            } else {
                if (result instanceof Disposable disposable) {
                    addDisposable(disposable);
                }
                transitionState(FiberState.ACTIVE);
                notifyServices();
            }
        } catch (Throwable failure) {
            handleBodyFailure(failure);
        }
    }

    /**
     * Records a plugin body failure: logs it, reverts the dependency epoch to
     * inactive (so no further transitions occur) and unloads the partial body.
     * Mirrors Cordis's {@code _reload} catch path.
     */
    private void handleBodyFailure(Throwable failure) {
        error = failure;
        ctx.logger().error(failure);
        epoch = INACTIVE;
        unloadBody();
        transitionState(FiberState.FAILED);
    }

    private static Throwable unwrapCause(Throwable failure) {
        return failure instanceof java.util.concurrent.CompletionException e && e.getCause() != null
                ? e.getCause()
                : failure;
    }

    private void unloadBody() {
        drainEffects();
        // the dependency cache (`store`) is deliberately NOT cleared: it is the
        // resolution snapshot used by refresh() — a restart must be able to
        // re-resolve dependencies that are still available (mirrors Cordis
        // keeping `_store` across `_unload`). checkImpl() on notify updates it.
        transitionState(FiberState.PENDING);
    }

    private void notifyServices() {
        for (Impl impl : ctx.reflect().providedBy(this)) {
            ctx.reflect().notify(List.of(impl.name()), ctx);
        }
    }

    private void unload() {
        if (!disposed.compareAndSet(false, true)) return;
        uid = -1;
        ctx.events().emit((Object) null, "internal/plugin", this);
        RegistryService registry = ctx.registry();
        if (runtime != null && registry.has(runtime.callback())) {
            runtime.fibers().remove(this);
            if (runtime.fibers().isEmpty()) {
                registry.delete(runtime.callback());
            }
        }
        drainEffects();
        store.clear();
        transitionState(FiberState.DISPOSED);
    }

    @Override
    public CompletableFuture<Fiber> await() {
        Throwable failure = error;
        if (failure != null) {
            return CompletableFuture.failedFuture(failure);
        }
        CompletableFuture<Void> task = inertia;
        if (task == null) {
            return CompletableFuture.completedFuture(this);
        }
        return task.thenApply(ignored -> this);
    }

    @Override
    public int effectCount() {
        synchronized (lifecycle) {
            return disposables.size();
        }
    }

    /** Clears all registered effects (used after framework setup). */
    public void clearEffects() {
        synchronized (lifecycle) {
            disposables.clear();
            effectMetas.clear();
        }
    }

    @Override
    public CompletableFuture<Void> disposeAsync() {
        if (runtime == null) {
            return CompletableFuture.runAsync(() -> {
                if (!disposed.compareAndSet(false, true)) return;
                transitionState(FiberState.UNLOADING);
                RuntimeException failure = null;
                List<Disposable> snapshot;
                synchronized (lifecycle) {
                    snapshot = new ArrayList<>(disposables);
                    disposables.clear();
                    effectMetas.clear();
                }
                for (int i = snapshot.size() - 1; i >= 0; i--) {
                    try {
                        snapshot.get(i).dispose();
                    } catch (RuntimeException e) {
                        if (failure == null) failure = e;
                    }
                }
                transitionState(FiberState.DISPOSED);
                if (failure != null) throw failure;
            });
        }
        return CompletableFuture.runAsync(() -> {
            // the plugin lifecycle was registered as an effect on the parent
            // fiber; dispose it by running the collected disposables
            unload();
        });
    }
}
