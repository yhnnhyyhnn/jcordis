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
    /** Replaced-away plugin keys forwarding to their replacement's key. */
    private final Map<Plugin, Plugin> aliases = new ConcurrentHashMap<>();

    private final java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger();

    public RegistryService(Context ctx) {
        this.ctx = ctx;
    }

    /** Assigns a unique id for each new plugin fiber (thread-safe). */
    public int counter() {
        return counter.incrementAndGet();
    }

    public int size() {
        return internal.size();
    }

    /** Follows alias chains (a replaced-away key forwards to its replacement). */
    private Plugin forward(Plugin plugin) {
        Plugin key = plugin;
        Plugin next;
        while ((next = aliases.get(key)) != null) {
            key = next;
        }
        return key;
    }

    public boolean has(Plugin plugin) {
        return internal.containsKey(forward(plugin));
    }

    public PluginRuntime get(Plugin plugin) {
        return internal.get(forward(plugin));
    }

    /** Removes the runtime and disposes every fiber created from the plugin. */
    public PluginRuntime delete(Plugin plugin) {
        PluginRuntime runtime = internal.remove(forward(plugin));
        if (runtime == null) return null;
        // drop aliases pointing at the removed key, and the removed key's own
        aliases.values().removeIf(target -> target == runtime.callback());
        aliases.remove(runtime.callback());
        for (Fiber fiber : List.copyOf(runtime.fibers())) {
            // disposal runs synchronously on the calling thread — no waiting
            // (mirrors Cordis, whose delete never blocks on disposal)
            fiber.disposeAsync();
        }
        return runtime;
    }

    /**
     * Replaces every fiber of {@code oldPlugin} with {@code newPlugin} in
     * place: fibers stay alive and keep their position in the context tree,
     * but execute the new body under the new inject declarations (mirrors
     * Cordis's {@code registry.replace}). The old plugin reference keeps
     * working through an alias — {@code has}/{@code get}/{@code delete} on it
     * forward to the replacement.
     *
     * @return the fibers that were re-targeted
     */
    public List<Fiber> replace(Plugin oldPlugin, Plugin newPlugin) {
        Plugin oldKey = forward(oldPlugin);
        PluginRuntime runtime = internal.get(oldKey);
        if (runtime == null) {
            throw new IllegalArgumentException("cannot replace a plugin that is not registered: " + oldPlugin);
        }
        PluginRuntime target = internal.get(newPlugin);
        // replacing back onto an existing alias target breaks the cycle
        aliases.remove(newPlugin);
        if (oldKey != newPlugin) {
            internal.remove(oldKey);
            aliases.put(oldKey, newPlugin);
        }
        List<Fiber> fibers = List.copyOf(runtime.fibers());
        if (fibers.isEmpty()) return fibers;
        if (target == null) {
            target = new PluginRuntime(newPlugin.name(), newPlugin);
            internal.put(newPlugin, target);
        }
        for (Fiber fiber : fibers) {
            ((io.jcordis.core.fiber.FiberImpl) fiber).replaceRuntime(target, newPlugin.inject());
        }
        return fibers;
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
        return plugin(ctx, plugin, config, Map.of());
    }

    /**
     * Registers a plugin with extra inject declarations (entry-level inject in
     * the loader), merged over the plugin's own declarations before dependency
     * resolution. Mirrors Cordis merging {@code entry.options.inject} into
     * {@code fiber.inject} via the {@code internal/plugin} event.
     */
    public Fiber plugin(Context ctx, Plugin plugin, Object config, Map<String, Object> extraInject) {
        ctx.fiber().assertActive();
        PluginRuntime runtime = internal.computeIfAbsent(plugin, key -> new PluginRuntime(key.name(), key));
        Map<String, Object> inject = new java.util.HashMap<>(plugin.inject());
        if (extraInject != null) {
            inject.putAll(extraInject);
        }
        return new FiberImpl(ctx, config, inject, runtime);
    }

    /** Registers a plugin on the current context. */
    public Fiber plugin(Plugin plugin, Object config) {
        return plugin(ctx, plugin, config);
    }
}
