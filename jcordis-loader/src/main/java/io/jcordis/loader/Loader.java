package io.jcordis.loader;

import io.jcordis.core.context.Context;
import io.jcordis.core.event.EventOptions;
import io.jcordis.core.fiber.Fiber;
import io.jcordis.core.registry.Plugin;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * The loader service, mirroring Cordis's {@code Loader}.
 *
 * <p>Owns an {@link EntryTree} of entries, resolves plugin bodies through the
 * {@code builtins} registry (SPI-style) plus a user {@code modules} registry,
 * and wires loader behavior into the core event system:
 * <ul>
 *   <li>{@code internal/update} — a plugin updating its own config is written
 *       back to the entry and persisted;</li>
 *   <li>{@code internal/plugin} — a plugin fiber disposing itself marks its
 *       entry disabled and persists the change.</li>
 * </ul>
 */
public class Loader extends EntryTree {

    public final Map<String, Plugin> builtins = new ConcurrentHashMap<>();
    public final Map<String, Plugin> modules = new ConcurrentHashMap<>();

    /** Shared isolation realms, keyed by their {@code @label} suffix. */
    private final Map<String, GlobalRealm> realms = new ConcurrentHashMap<>();

    /** Plugin jar class loaders, keyed by plugin name. */
    final Map<String, PluginClassLoader> classLoaders = new ConcurrentHashMap<>();

    private final Context ctx;

    public Loader(Context ctx) {
        super(ctx, null);
        this.ctx = ctx;
        bindLoader(this);
        // the group plugin is built-in and resolvable by name (mirrors Cordis's
        // `@cordisjs/plugin-group` export)
        builtins.put("@cordisjs/plugin-group", GROUP_PLUGIN);
        // mirror Cordis: the loader service carries a check predicate gating
        // dependency readiness when the `await` intercept config is active
        ctx.reflect().provide("loader", this, ctx, ignored -> checkLoader());

        ctx.on(
                "internal/update",
                (thisArg, args) -> {
                    Object config = args[0];
                    Boolean noSave = (Boolean) args[1];
                    if (thisArg instanceof Fiber fiber
                            && fiber.entry() instanceof Entry entry
                            && !Boolean.TRUE.equals(noSave)) {
                        entry.options.config = config;
                        entry.parent.tree.write();
                    }
                    if (args.length > 2 && args[2] instanceof java.util.function.Supplier<?> next) {
                        return next.get();
                    }
                    return null;
                },
                EventOptions.of(true, true));

        ctx.on(
                "internal/plugin",
                (thisArg, args) -> {
                    Object fiberArg = args.length > 0 ? args[0] : null;
                    if (fiberArg instanceof Fiber fiber && fiber.uid() < 0 && fiber.entry() instanceof Entry entry) {
                        boolean cascaded = false;
                        Entry cursor = entry.parent != null
                                ? entry.parent.ctx.fiber().entry() instanceof Entry p ? p : null
                                : null;
                        while (cursor != null) {
                            if (Boolean.TRUE.equals(cursor.options.disabled)) {
                                cascaded = true;
                                break;
                            }
                            cursor = cursor.parent != null
                                            && cursor.parent.ctx.fiber().entry() instanceof Entry p
                                    ? p
                                    : null;
                        }
                        if (!cascaded) {
                            entry.options.disabled = true;
                            entry.parent.tree.write();
                        }
                    }
                    return null;
                },
                EventOptions.of(false, false));
    }

    @Override
    public Plugin importPlugin(String name) {
        if (name.startsWith("cordis:")) {
            return builtins.get(name.substring(7));
        }
        Plugin plugin = builtins.get(name);
        return plugin != null ? plugin : modules.get(name);
    }

    /** Registers a plugin under a name, returning it for assertions. */
    public Plugin mock(String name, Plugin plugin) {
        modules.put(name, plugin);
        return plugin;
    }

    /** Registers a built-in plugin under a name. */
    public void builtin(String name, Plugin plugin) {
        builtins.put(name, plugin);
    }

    /** Returns (creating if needed) the global realm for a shared isolate label. */
    public GlobalRealm realm(String label) {
        return realms.computeIfAbsent(label, GlobalRealm::new);
    }

    /**
     * Loads a plugin from a jar file via SPI discovery and registers it under
     * {@code name}, returning the discovered plugin instance.
     */
    public Plugin loadJar(Path jar, String name) {
        PluginClassLoader previous = classLoaders.get(name);
        if (previous != null) {
            unload(name);
        }
        PluginClassLoader classLoader = new PluginClassLoader(jar, getClass().getClassLoader());
        try {
            Plugin plugin = discover(classLoader, jar);
            classLoaders.put(name, classLoader);
            modules.put(name, plugin);
            return plugin;
        } catch (RuntimeException | Error e) {
            closeQuietly(classLoader);
            throw e;
        }
    }

    /**
     * Atomically replaces the plugin registered under {@code name} with a fresh
     * load of {@code jar}: the new class loader is validated first, then the
     * registry is swapped, matching entries are reloaded, and the previous
     * class loader is closed. On validation failure the previous plugin is left
     * untouched.
     */
    public Plugin replaceJar(Path jar, String name) {
        PluginClassLoader previous = classLoaders.get(name);
        PluginClassLoader fresh = new PluginClassLoader(jar, getClass().getClassLoader());
        Plugin plugin;
        try {
            plugin = discover(fresh, jar);
        } catch (RuntimeException | Error e) {
            closeQuietly(fresh);
            throw e;
        }
        classLoaders.put(name, fresh);
        modules.put(name, plugin);
        java.util.Set<Entry> reloaded = new java.util.HashSet<>();
        for (Entry entry : entries()) {
            if (name.equals(entry.options.name) && entry.fiber != null) {
                entry.fiber.disposeAsync().join();
                entry.fiber = null;
                entry.loaded = false;
                reloaded.add(entry);
            }
        }
        // disposing a fiber marks its entry disabled (self-dispose semantics);
        // a hot replace must clear that side effect before reloading
        for (Entry entry : reloaded) {
            entry.options.disabled = null;
            entry.refresh();
        }
        if (previous != null) {
            closeQuietly(previous);
        }
        return plugin;
    }

    /**
     * Unloads the plugin registered under {@code name}: disposes the fibers of
     * every entry using it, removes it from the registries, and closes its
     * class loader so its classes become collectable.
     */
    public void unload(String name) {
        for (Entry entry : entries()) {
            if (name.equals(entry.options.name) && entry.fiber != null) {
                entry.fiber.disposeAsync().join();
                entry.fiber = null;
                entry.loaded = false;
            }
        }
        modules.remove(name);
        PluginClassLoader classLoader = classLoaders.remove(name);
        if (classLoader != null) {
            closeQuietly(classLoader);
        }
    }

    /**
     * Discovers the plugin declared by {@code jar} itself. Only providers
     * listed in the jar's own SPI manifest and loaded by its own class loader
     * count: {@code ServiceLoader} would also enumerate the host classpath's
     * manifests (e.g. a plugin jar added as a regular dependency), which could
     * shadow the uploaded plugin.
     */
    private static Plugin discover(PluginClassLoader classLoader, Path jar) {
        String service = "META-INF/services/io.jcordis.core.registry.Plugin";
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            JarEntry entry = jarFile.getJarEntry(service);
            if (entry == null) {
                throw new IllegalArgumentException("no Plugin implementation in " + jar);
            }
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(jarFile.getInputStream(entry), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    Plugin plugin = instantiate(classLoader, line);
                    if (plugin != null) return plugin;
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read SPI manifest of " + jar, e);
        }
        throw new IllegalArgumentException("no Plugin implementation in " + jar);
    }

    private static Plugin instantiate(PluginClassLoader classLoader, String className) {
        try {
            Class<?> clazz = Class.forName(className, false, classLoader);
            if (clazz.getClassLoader() != classLoader) return null;
            Object instance = clazz.getDeclaredConstructor().newInstance();
            return instance instanceof Plugin plugin ? plugin : null;
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    private static void closeQuietly(PluginClassLoader classLoader) {
        try {
            classLoader.close();
        } catch (IOException e) {
            // nothing to recover from a failed jar handle release
        }
    }

    /** The fiber of the entry with the given id, or {@code null}. */
    public io.jcordis.core.fiber.Fiber expectFiber(String id) {
        Entry entry = store.get(id);
        return entry != null ? entry.fiber : null;
    }

    /**
     * Returns the id of the entry containing the given fiber (walking up the
     * context chain), mirroring Cordis's {@code Loader.locate}.
     */
    public String locate(io.jcordis.core.fiber.Fiber fiber) {
        if (fiber == null) {
            fiber = ctx.fiber();
        }
        io.jcordis.core.fiber.Fiber cursor = fiber;
        while (cursor != null) {
            if (cursor.entry() instanceof Entry entry) {
                return entry.id();
            }
            io.jcordis.core.context.Context parentCtx = cursor.ctx().parent();
            if (parentCtx == null) return null;
            io.jcordis.core.fiber.Fiber next = parentCtx.fiber();
            if (next == cursor) return null;
            cursor = next;
        }
        return null;
    }

    /** Applies a full config list, creating/updating/removing entries. */
    public void read(java.util.List<EntryOptions> config) {
        root.update(config);
    }

    @Override
    public void write() {
        // in-memory loader: no-op
    }

    /**
     * Loader availability check (mirrors Cordis's {@code Loader[Service.check]}):
     * when the {@code loader.await} intercept config is set, the loader is only
     * "ready" once every entry task has settled.
     */
    private boolean checkLoader() {
        Object config = null;
        Context cursor = ctx;
        while (cursor != null) {
            Object value = cursor.interceptConfig("loader");
            if (value != null) {
                config = value;
            }
            cursor = cursor.parent();
        }
        boolean await = config instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("await"));
        return !await || getTasks().isEmpty();
    }

    public Context ctx() {
        return ctx;
    }

    public void emitEntryInit(Entry entry) {
        ctx.events().emit((Object) null, "loader/entry-init", entry);
    }

    /** Group plugin: initializes the entry's subgroup from its config list. */
    public static final Plugin GROUP_PLUGIN = (ctx, config) -> {
        if (ctx.fiber().entry() instanceof Entry entry) {
            entry.initGroupInternal();
            return (io.jcordis.core.util.Disposable) () -> {
                if (entry.subgroup != null) {
                    entry.subgroup.stop();
                }
            };
        }
        return null;
    };

    public void showLog(Entry entry, String type) {
        if (entry.options.group != null && Boolean.TRUE.equals(entry.options.group) || !enableLogs) return;
        // %C colors the plugin name with the logger's palette code (mirrors Cordis)
        ctx.logger("loader").info("%s plugin %C", type, entry.options.name);
    }
}
