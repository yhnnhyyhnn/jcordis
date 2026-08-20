package io.jcordis.core.registry;

import io.jcordis.core.context.Context;
import io.jcordis.core.fiber.Fiber;
import io.jcordis.core.fiber.FiberImpl;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Plugin registry of a context tree, mirroring Cordis's {@code RegistryService}.
 *
 * <p>Each distinct plugin body maps to one {@link PluginRuntime} holding every
 * live fiber created from it. Fibers are disposed (and the runtime removed)
 * when the runtime is deleted or the owning fiber is torn down.
 */
public final class RegistryService {

    private final Context ctx;
    private final Map<Plugin, PluginRuntime> internal = new ConcurrentHashMap<>();
    private int counter;

    public RegistryService(Context ctx) {
        this.ctx = ctx;
    }

    /** Assigns a unique id for each new plugin fiber. */
    public int counter() {
        return ++counter;
    }

    public int size() {
        return internal.size();
    }

    public boolean has(Plugin plugin) {
        return internal.containsKey(plugin);
    }

    public PluginRuntime get(Plugin plugin) {
        return internal.get(plugin);
    }

    /** Removes the runtime and disposes every fiber created from the plugin. */
    public PluginRuntime delete(Plugin plugin) {
        PluginRuntime runtime = internal.remove(plugin);
        if (runtime == null) return null;
        for (Fiber fiber : List.copyOf(runtime.fibers())) {
            fiber.disposeAsync().join();
        }
        return runtime;
    }

    public Set<Plugin> keys() {
        return internal.keySet();
    }

    public Collection<PluginRuntime> values() {
        return internal.values();
    }

    public Set<Map.Entry<Plugin, PluginRuntime>> entries() {
        return internal.entrySet();
    }

    public void forEach(BiConsumer<Plugin, PluginRuntime> callback) {
        internal.forEach(callback);
    }

    /** Registers a dependency-injected plugin on the given context. */
    public Fiber inject(Context ctx, List<String> names, Plugin callback) {
        Map<String, Object> inject = new java.util.HashMap<>();
        for (String name : names) {
            inject.put(name, null);
        }
        return inject(ctx, inject, callback);
    }

    /** Registers a dependency-injected plugin with explicit configs. */
    public Fiber inject(Context ctx, Map<String, Object> inject, Plugin callback) {
        return plugin(ctx, Plugin.object(null, inject, callback), null);
    }

    /** Registers a plugin on the given context, creating a new plugin fiber. */
    public Fiber plugin(Context ctx, Plugin plugin, Object config) {
        ctx.fiber().assertActive();
        PluginRuntime runtime = internal.computeIfAbsent(plugin, key -> new PluginRuntime(key.name(), key));
        return new FiberImpl(ctx, config, plugin.inject(), runtime);
    }

    /** Registers a plugin on the current context. */
    public Fiber plugin(Plugin plugin, Object config) {
        return plugin(ctx, plugin, config);
    }
}