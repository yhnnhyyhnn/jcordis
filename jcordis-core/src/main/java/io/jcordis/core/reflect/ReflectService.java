package io.jcordis.core.reflect;

import io.jcordis.core.context.Context;
import io.jcordis.core.fiber.Fiber;
import io.jcordis.core.fiber.FiberState;
import io.jcordis.core.registry.PluginRuntime;
import io.jcordis.core.service.ServiceKey;
import io.jcordis.core.util.Disposable;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service registry of a context tree, mirroring Cordis's {@code ReflectService}.
 *
 * <p>Provides the coeffect context: services are stored by isolation key and
 * {@link #getImpl(String, boolean, Context)} gates visibility on the providing
 * fiber's state (strict mode requires an {@code ACTIVE} fiber). Changes are
 * propagated through {@link #notify}, which re-checks dependencies of every
 * registered plugin fiber and refreshes them when an injection becomes
 * available or disappears.
 */
public final class ReflectService {

    private final Context ctx;
    private final Map<ServiceKey, Impl> store = new ConcurrentHashMap<>();
    private final Map<String, String> props = new ConcurrentHashMap<>();

    public ReflectService(Context ctx) {
        this.ctx = ctx;
    }

    public Object get(String name, Context source) {
        Impl impl = getImpl(name, false, source);
        return impl == null ? null : impl.value();
    }

    @SuppressWarnings("unchecked")
    public <T> T get(ServiceKey<T> key) {
        Impl impl = store.get(key);
        return impl == null ? null : (T) impl.value();
    }

    public <T> void set(ServiceKey<T> key, T value) {
        Impl impl = store.get(key);
        store.put(key, new Impl(impl.name(), value, impl.fiber(), impl.check()));
    }

    public void set(String name, Object value, Context source) {
        Impl impl = getImpl(name, false, source);
        if (impl == null) {
            throw new IllegalStateException("cannot set property \"" + name + "\" without provide");
        }
        store.put(implKey(name, source), impl.withValue(value));
    }

    /** Returns the impl under {@code name}, or {@code null} when missing or inactive. */
    public Impl getImpl(String name, boolean strict, Context source) {
        ServiceKey<?> key = source.isolateKey(name);
        if (key == null) {
            key = ServiceKey.of(name);
        }
        Impl impl = store.get(key);
        if (impl == null) return null;
        if (strict && impl.fiber().state() != FiberState.ACTIVE) return null;
        return impl;
    }

    private ServiceKey<?> implKey(String name, Context source) {
        ServiceKey<?> key = source.isolateKey(name);
        return key != null ? key : ServiceKey.of(name);
    }

    /** Registers a service, returning a disposable that unregisters it. */
    public Disposable provide(String name, Object value, Context source) {
        return source.fiber().effect(runner -> {
            ServiceKey<?> key = implKey(name, source);
            if (store.containsKey(key)) {
                throw new IllegalStateException("service \"" + name + "\" has been registered at <" + source.fiber().name() + ">");
            }
            props.put(name, "service");
            Impl impl = new Impl(name, value, source.fiber(), null);
            store.put(key, impl);
            if (source.fiber().state() == FiberState.ACTIVE) {
                notify(List.of(name), source);
            }
            return io.jcordis.core.fiber.EffectResult.of(() -> {
                store.remove(key, impl);
                notify(List.of(name), source);
            });
        }, "ctx.provide(" + name + ")");
    }

    /** Re-checks every registered fiber that injects one of {@code names}. */
    public void notify(List<String> names, Context source) {
        for (PluginRuntime runtime : ctx.registry().values()) {
            for (Fiber fiber : runtime.fibers()) {
                boolean hasUpdate = false;
                for (String name : names) {
                    if (!fiber.inject().containsKey(name)) continue;
                    if (!Objects.equals(fiber.ctx().isolateKey(name), source.isolateKey(name))) continue;
                    hasUpdate = true;
                    fiber.checkImpl(name);
                }
                if (!hasUpdate) continue;
                fiber.refresh();
            }
        }
        for (String name : names) {
            Impl impl = getImpl(name, false, source);
            source.events().emit(source, "internal/service", name, impl == null ? null : impl.value());
        }
    }

    /** Declares mixin accessor keys for a service. */
    public void mixin(String source, List<String> keys) {
        for (String key : keys) {
            props.put(key, "accessor");
        }
    }

    /** All impls provided by the given fiber (for ACTIVE-state notifications). */
    public List<Impl> providedBy(Fiber fiber) {
        return store.values().stream().filter(impl -> impl.fiber() == fiber).toList();
    }
}