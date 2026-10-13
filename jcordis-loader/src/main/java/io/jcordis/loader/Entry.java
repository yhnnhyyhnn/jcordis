package io.jcordis.loader;

import io.jcordis.core.context.Context;
import io.jcordis.core.fiber.Fiber;
import io.jcordis.core.registry.Plugin;
import java.util.Map;

/**
 * A single entry in the loader tree, mirroring Cordis's {@code Entry}.
 *
 * <p>An entry owns a context (child of its group's tree context), an optional
 * fiber (once its plugin is loaded), and configuration options. Updates
 * propagate to the fiber via {@code internal/update}; disabling an entry
 * disposes its fiber; enabling re-initializes it.
 */
public final class Entry {

    private final EntryTree tree;
    private volatile Context ctx;
    public final EntryOptions options = new EntryOptions();
    public EntryGroup parent;
    public EntryGroup subgroup;
    public EntryTree subtree;

    public volatile Fiber fiber;
    public volatile boolean loaded;

    /**
     * Whether {@link #init()} is currently running on this entry. A tree
     * update that re-enters while the entry is still initializing is ignored
     * — the outer init owns it (an include's initial re-apply walks the whole
     * root tree and would otherwise construct a second plugin instance, or
     * recurse without bound; mirrors Cordis guarding concurrent inits with
     * {@code _initTask}).
     */
    private volatile boolean initializing;

    /** Pending initialization task (entries load synchronously, so usually null). */
    public volatile Object _initTask;

    private volatile Realm localRealm;

    Entry(EntryGroup parent) {
        this.tree = parent.tree;
        this.parent = parent;
        this.ctx = parent.ctx.extend();
        tree.loader().emitEntryInit(this);
    }

    public Context ctx() {
        return ctx;
    }

    public String id() {
        // unconditional parent prefix (mirrors Cordis): a child sharing its
        // group's id must still be addressable as "group:child" — dropping
        // the prefix would collide with the group entry itself
        String id = options.id;
        if (parent.ctx.fiber().entry() instanceof Entry parentEntryObj) {
            id = parentEntryObj.id() + EntryTree.SEP + id;
        }
        return id;
    }

    public boolean disabled() {
        if (Boolean.TRUE.equals(options.group)) return false;
        Entry entry = this;
        while (entry != null) {
            if (Boolean.TRUE.equals(entry.options.disabled)) return true;
            if (entry.parent != null && entry.parent.ctx.fiber().entry() instanceof Entry parentEntry) {
                entry = parentEntry;
            } else {
                entry = null;
            }
        }
        return false;
    }

    /** Applies new options, reinitializing or updating the fiber as needed. */
    public void update(EntryOptions source, boolean create, boolean force) {
        if (initializing) {
            // re-entrant update during init: the outer init owns this entry
            return;
        }
        // capture the config before it is overwritten so a restart-on-change
        // comparison can detect actual differences (copyInto assigns the same
        // reference to both fields, making a post-copy comparison tautological)
        EntryOptions.Snapshot legacy = options.snapshot();
        Object legacyConfig = options.config;
        Map<String, Object> legacyIsolate = options.isolate;
        Map<String, Object> legacyIntercept = options.intercept;
        Map<String, Object> legacyInject = options.inject;
        if (create) {
            copyInto(source, options);
        } else {
            options.merge(source);
        }

        // groups are always enabled (mirrors Cordis's Entry.disabled): a
        // disabled flag on the group itself is ignored, though it still
        // disables its children via the ancestor chain
        if (!Boolean.TRUE.equals(options.group) && (Boolean.TRUE.equals(options.disabled) || disabledByAncestor())) {
            if (fiber != null) {
                fiber.disposeAsync().join();
                fiber = null;
                loaded = false;
            }
            if (subgroup != null) {
                subgroup.disposeAll();
            }
            return;
        }

        if (fiber == null) {
            init();
            return;
        }
        // diff per option (mirrors Cordis's changed-key diff): only an actual
        // config change restarts the body — `force` merely bypasses the
        // early-return so the context rebuild still runs, and an intercept or
        // inject edit reaches the running fiber without a restart
        boolean configChanged = !java.util.Objects.equals(options.config, legacyConfig);
        boolean isolateChanged = isolateChanged(legacyIsolate, options);
        boolean interceptChanged = !java.util.Objects.equals(options.intercept, legacyIntercept);
        boolean injectChanged = !java.util.Objects.equals(options.inject, legacyInject);
        if (!force && !configChanged && !isolateChanged && !interceptChanged && !injectChanged) {
            return;
        }
        // mirror Cordis: emit the partial-dispose event before reloading
        ctx.events().emit((Object) null, "loader/partial-dispose", this, legacy, true);
        rebuildCtx();
        // propagate the rebuilt intercept/isolate context onto the running
        // fiber before restart (mirrors Cordis's Object.setPrototypeOf patch)
        fiber.rebindContext(ctx);
        // config changes restart the body; group entries always do (their
        // config list is compared by reference and a restart re-evaluates the
        // children under the current disabled state — mirrors Cordis's
        // `diff.includes('config') || this.options.group`); isolate moves
        // restart too — jcordis has no in-place impl migration (services
        // injected by the body come from the old realm until it re-runs; see
        // compatibility.md). An intercept or inject edit only re-keys the
        // context: notify, no restart
        if (configChanged || isolateChanged || Boolean.TRUE.equals(options.group)) {
            fiber.update(resolveConfig(), true);
        }
        // isolate/intercept changes re-key or re-configure services: notify
        // the changed names so dependents re-resolve under their (rebound)
        // realms — mirrors Cordis's patch-context swap + notify
        java.util.Set<String> changed = changedIsolateNames(legacyIsolate, options);
        changed.addAll(changedMapKeys(legacyIntercept, options.intercept));
        if (!changed.isEmpty()) {
            ctx.reflect().notify(java.util.List.copyOf(changed), ctx);
        }
    }

    /** Names whose map-valued option actually changed (for post-reload notification). */
    private static java.util.Set<String> changedMapKeys(Map<String, Object> legacy, Map<String, Object> next) {
        java.util.Set<String> changed = new java.util.LinkedHashSet<>();
        if (legacy != null) {
            changed.addAll(legacy.keySet());
        }
        if (next != null) {
            changed.addAll(next.keySet());
        }
        changed.removeIf(key ->
                java.util.Objects.equals(legacy != null ? legacy.get(key) : null, next != null ? next.get(key) : null));
        return changed;
    }

    /** Whether the isolate option map changed (label/local-realm moves). */
    private static boolean isolateChanged(Map<String, Object> legacy, EntryOptions options) {
        if (legacy == null) {
            return options.isolate != null && !options.isolate.isEmpty();
        }
        if (options.isolate == null) {
            return !legacy.isEmpty();
        }
        return !legacy.equals(options.isolate);
    }

    /** Names whose isolate label actually changed (for post-reload notification). */
    private static java.util.Set<String> changedIsolateNames(Map<String, Object> legacy, EntryOptions options) {
        java.util.Set<String> changed = new java.util.LinkedHashSet<>();
        if (legacy != null) {
            changed.addAll(legacy.keySet());
        }
        if (options.isolate != null) {
            changed.addAll(options.isolate.keySet());
        }
        changed.removeIf(name -> java.util.Objects.equals(
                legacy != null ? legacy.get(name) : null, options.isolate != null ? options.isolate.get(name) : null));
        return changed;
    }

    /** Whether an ancestor entry is disabled (groups are always enabled). */
    private boolean disabledByAncestor() {
        if (Boolean.TRUE.equals(options.group)) return false;
        Entry entry = this;
        while (entry != null) {
            if (Boolean.TRUE.equals(entry.options.disabled)) return true;
            if (entry.parent != null && entry.parent.ctx.fiber().entry() instanceof Entry parentEntry) {
                entry = parentEntry;
            } else {
                entry = null;
            }
        }
        return false;
    }

    private void copyInto(EntryOptions source, EntryOptions target) {
        target.id = source.id;
        target.name = source.name;
        target.config = source.config;
        target.group = source.group;
        target.disabled = source.disabled;
        target.inject = source.inject;
        target.intercept = source.intercept;
        target.isolate = source.isolate;
    }

    private Object resolveConfig() {
        return options.config;
    }

    /**
     * Rebuilds the entry context from its parent's tree context, applying the
     * current {@code intercept} and {@code isolate} options exactly once.
     *
     * <p>Rebuilding (rather than layering {@code ctx.intercept(...)} onto the
     * previous context) keeps the context chain from growing on every update
     * and lets the loader propagate option changes onto the running fiber.
     */
    private void rebuildCtx() {
        ctx = parent.ctx.extend();
        if (options.intercept != null) {
            for (Map.Entry<String, Object> entry : options.intercept.entrySet()) {
                ctx = ctx.intercept(entry.getKey(), entry.getValue());
            }
        }
        if (options.isolate != null) {
            for (Map.Entry<String, Object> entry : options.isolate.entrySet()) {
                Object label = entry.getValue();
                io.jcordis.core.service.ServiceKey<?> key;
                if (Boolean.TRUE.equals(label)) {
                    if (localRealm == null) {
                        localRealm = new LocalRealm(this);
                    }
                    key = localRealm.access(entry.getKey(), true);
                } else if (label instanceof String s) {
                    key = tree.loader().realm(s).access(entry.getKey(), true);
                } else {
                    continue;
                }
                ctx = ctx.isolate(entry.getKey(), key);
            }
        }
    }

    private void init() {
        if (disabled()) return;
        initializing = true;
        try {
            rebuildCtx();
            Plugin plugin = tree.importPlugin(options.name);
            // a group entry is either declared `group: true` or resolves to the
            // group plugin itself (identity check, mirroring Cordis's
            // `plugin[EntryGroup.key]` marker — not a name match)
            boolean isGroup = Boolean.TRUE.equals(options.group) || plugin == Loader.GROUP_PLUGIN;
            if (isGroup) {
                plugin = Loader.GROUP_PLUGIN;
            }
            if (plugin == null) {
                tree.loader().ctx().logger().error("cannot resolve plugin " + options.name);
                return;
            }
            tree.loader().showLog(this, "apply");
            Fiber f = ctx.registry()
                    .plugin(ctx, plugin, resolveConfig(), options.inject != null ? options.inject : Map.of());
            f.setEntry(this);
            fiber = f;
            if (isGroup) {
                if (subgroup != null) {
                    subgroup.disposeAll();
                }
                subgroup = null;
                subtree = null;
                initGroupInternal();
                // stop the subgroup when the group fiber is disposed
                f.effect(
                        runner -> io.jcordis.core.fiber.EffectResult.of(() -> {
                            if (subgroup != null) {
                                subgroup.stop();
                            }
                        }),
                        "group.dispose()");
            }
            loaded = true;
            // mirror Cordis's Entry.init tail: once the fiber's async load work
            // settles (and no other task is pending), re-notify 'loader' so
            // dependents gated by the await config are re-checked
            f.await().whenComplete((ignored, error) -> {
                if (tree.getTasks().isEmpty()) {
                    ctx.reflect().notify(java.util.List.of("loader"), ctx);
                }
            });
        } finally {
            initializing = false;
        }
    }

    /** Initializes the subgroup of a group entry (called by the group plugin). */
    public void initGroupInternal() {
        Context groupCtx = fiber != null ? fiber.ctx() : ctx;
        if (subgroup == null) {
            EntryTree parentTree = parent != null ? parent.tree : tree;
            subtree = new EntryTree(groupCtx, parentTree.loader(), parentTree) {
                @Override
                public Plugin importPlugin(String name) {
                    return tree.importPlugin(name);
                }

                @Override
                protected void persist(EntryChange change) {
                    tree.persist(change);
                }
            };
            subgroup = subtree.root;
        }
        if (options.config instanceof java.util.List<?> list) {
            for (Object item : list) {
                if (item instanceof EntryOptions entryOptions) {
                    if (entryOptions.id != null
                            && subtree.store.get(entryOptions.id) != null
                            && subtree.store.get(entryOptions.id).fiber == null) {
                        subtree.store.get(entryOptions.id).update(entryOptions, false, true);
                    } else {
                        subtree.create(entryOptions, null);
                    }
                }
            }
        }
    }

    /** Re-initializes the entry if it became disabled or was never loaded. */
    public void refresh() {
        if (fiber == null) {
            init();
        } else if (disabled()) {
            fiber.disposeAsync().join();
            fiber = null;
            loaded = false;
        }
    }

    /** Whether this entry is a group container. */
    public boolean isGroup() {
        return Boolean.TRUE.equals(options.group);
    }
}
