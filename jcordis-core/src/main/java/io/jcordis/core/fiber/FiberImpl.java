package io.jcordis.core.fiber;

import io.jcordis.core.context.Context;
import io.jcordis.core.context.ContextImpl;
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
    private final Context ctx;
    private final String name;
    private final List<Disposable> disposables = new ArrayList<>();
    private final AtomicBoolean disposed = new AtomicBoolean(false);
    private final Map<String, List<EventHandler>> hooks = new ConcurrentHashMap<>();
    private final Map<String, Object> inject = new HashMap<>();
    private final Map<String, Impl> store = new HashMap<>();
    private final PluginRuntime runtime;

    private volatile int uid;
    private volatile FiberState state;
    private volatile Object config;
    private volatile Object entry;
    private volatile String epoch;
    private volatile CompletableFuture<Void> inertia;

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
        parent.fiber().effect(runner -> {
            runtime.fibers().add(this);
            refresh();
            return EffectResult.of(() -> unload());
        }, "ctx.plugin()");
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
    public void update(Object config, boolean noSave) {
        assertActive();
        ctx.events()
                .waterfall(this, "internal/update", new Object[] {config, noSave}, args -> {
                    updateConfig(config);
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

    @Override
    public Disposable effect(EffectRunner runner, String label) {
        assertActive();
        List<Disposable> collected = new ArrayList<>();
        EffectResult result = runner.run(ctx);
        switch (result) {
            case EffectResult.Noop ignored -> {}
            case EffectResult.Single(Disposable disposable) -> collected.add(disposable);
            case EffectResult.Multiple(List<Disposable> list) -> collected.addAll(list);
        }
        AtomicBoolean once = new AtomicBoolean(false);
        Disposable wrapper = () -> {
            if (!once.compareAndSet(false, true)) return;
            for (int i = collected.size() - 1; i >= 0; i--) {
                collected.get(i).dispose();
            }
        };
        disposables.add(wrapper);
        return wrapper;
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

    private void reload() {
        state = FiberState.LOADING;
        try {
            Object result = runtime.callback().apply(ctx, config);
            if (result instanceof CompletableFuture<?> pending) {
                @SuppressWarnings("unchecked")
                CompletableFuture<Object> future = (CompletableFuture<Object>) pending;
                inertia = future.thenAccept(value -> {
                    if (value instanceof Disposable disposable) {
                        disposables.add(disposable);
                    }
                    state = FiberState.ACTIVE;
                    notifyServices();
                });
            } else {
                if (result instanceof Disposable disposable) {
                    disposables.add(disposable);
                }
                state = FiberState.ACTIVE;
                notifyServices();
            }
        } catch (Throwable error) {
            state = FiberState.FAILED;
        }
    }

    private void unloadBody() {
        for (int i = disposables.size() - 1; i >= 0; i--) {
            disposables.get(i).dispose();
        }
        disposables.clear();
        store.clear();
        state = FiberState.PENDING;
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
        for (int i = disposables.size() - 1; i >= 0; i--) {
            disposables.get(i).dispose();
        }
        disposables.clear();
        store.clear();
        state = FiberState.DISPOSED;
    }

    @Override
    public CompletableFuture<Fiber> await() {
        CompletableFuture<Void> task = inertia;
        if (task == null) {
            return CompletableFuture.completedFuture(this);
        }
        return task.thenApply(ignored -> this);
    }

    @Override
    public int effectCount() {
        return disposables.size();
    }

    /** Clears all registered effects (used after framework setup). */
    public void clearEffects() {
        disposables.clear();
    }

    @Override
    public CompletableFuture<Void> disposeAsync() {
        if (runtime == null) {
            return CompletableFuture.runAsync(() -> {
                if (!disposed.compareAndSet(false, true)) return;
                state = FiberState.UNLOADING;
                RuntimeException failure = null;
                for (int i = disposables.size() - 1; i >= 0; i--) {
                    try {
                        disposables.get(i).dispose();
                    } catch (RuntimeException e) {
                        if (failure == null) failure = e;
                    }
                }
                disposables.clear();
                state = FiberState.DISPOSED;
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