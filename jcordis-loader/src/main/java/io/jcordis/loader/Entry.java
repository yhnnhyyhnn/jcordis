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
        String id = options.id;
        Object parentEntry = parent.ctx.fiber().entry();
        if (parentEntry instanceof Entry parentEntryObj && !parentEntryObj.options.id.equals(options.id)) {
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
        if (create) {
            copyInto(source, options);
        } else {
            options.merge(source);
        }

        if (Boolean.TRUE.equals(options.disabled) || disabledByAncestor()) {
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
        } else if (force || !java.util.Objects.equals(options.config, source.config)) {
            applyIntercept();
            applyIsolate();
            fiber.update(resolveConfig(), true);
        }
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

    private void applyIntercept() {
        if (options.intercept == null) return;
        for (Map.Entry<String, Object> entry : options.intercept.entrySet()) {
            ctx = ctx.intercept(entry.getKey(), entry.getValue());
        }
    }

    /**
     * Applies the {@code isolate} option: each {@code name} is mapped to a
     * realm key ({@code true} → per-entry {@link LocalRealm}, a string label →
     * shared {@link GlobalRealm}) and the entry context is re-isolated on it.
     */
    private void applyIsolate() {
        if (options.isolate == null) return;
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

    private void init() {
        if (disabled()) return;
        applyIntercept();
        applyIsolate();
        boolean isGroup = Boolean.TRUE.equals(options.group) || isGroupPlugin(options.name);
        Plugin plugin = isGroup ? Loader.GROUP_PLUGIN : tree.importPlugin(options.name);
        if (plugin == null) {
            tree.loader().ctx().logger().error("cannot resolve plugin " + options.name);
            return;
        }
        tree.loader().showLog(this, "apply");
        Fiber f = ctx.registry().plugin(ctx, plugin, resolveConfig());
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
            f.effect(runner -> io.jcordis.core.fiber.EffectResult.of(() -> {
                if (subgroup != null) {
                    subgroup.stop();
                }
            }), "group.dispose()");
        }
        loaded = true;
    }

    private boolean isGroupPlugin(String name) {
        return name != null && name.contains("plugin-group");
    }

    /** Initializes the subgroup of a group entry (called by the group plugin). */
    public void initGroupInternal() {
        Context groupCtx = fiber != null ? fiber.ctx() : ctx;
        if (subgroup == null) {
            EntryTree parentTree = parent != null ? parent.tree : tree;
            subtree = new EntryTree(groupCtx, parentTree.loader()) {
                @Override
                public Plugin importPlugin(String name) {
                    return tree.importPlugin(name);
                }

                @Override
                public void write() {
                    tree.write();
                }
            };
            subgroup = subtree.root;
        }
        if (options.config instanceof java.util.List<?> list) {
            for (Object item : list) {
                if (item instanceof EntryOptions entryOptions) {
                    if (entryOptions.id != null && subtree.store.get(entryOptions.id) != null
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