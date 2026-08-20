package io.jcordis.loader;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A tree of entries, mirroring Cordis's {@code EntryTree}.
 *
 * <p>Owns the tree context, the root {@link EntryGroup}, and the id-to-entry
 * store. Subclasses provide {@link #importPlugin} (plugin resolution) and
 * {@link #write} (config persistence).
 */
public abstract class EntryTree {

    public static final String SEP = ":";

    private final Context treeCtx;
    private volatile Loader loader;
    public final EntryGroup root;
    public final Map<String, Entry> store = new ConcurrentHashMap<>();
    public boolean enableLogs;

    protected EntryTree(Context ctx, Loader loader) {
        this.treeCtx = ctx.extend();
        this.loader = loader;
        this.root = new EntryGroup(treeCtx, this);
    }

    /** Binds the loader after construction (needed for the root tree). */
    protected void bindLoader(Loader loader) {
        this.loader = loader;
    }

    public Context treeContext() {
        return treeCtx;
    }

    public Loader loader() {
        return loader;
    }

    public List<Entry> entries() {
        List<Entry> result = new ArrayList<>();
        collect(this, result);
        return result;
    }

    private void collect(EntryTree tree, List<Entry> result) {
        for (Entry entry : tree.store.values()) {
            result.add(entry);
            if (entry.subtree != null) {
                collect(entry.subtree, result);
            }
        }
    }

    public String ensureId(EntryOptions options) {
        if (options.id == null) {
            do {
                options.id = Long.toHexString((long) (Math.random() * 0xffffffffL));
            } while (store.containsKey(options.id));
        }
        return options.id;
    }

    public Entry resolve(String id) {
        String[] parts = id.split(SEP);
        EntryTree tree = this;
        for (int i = 0; i < parts.length - 1; i++) {
            Entry entry = tree.store.get(parts[i]);
            if (entry == null || entry.subtree == null) {
                throw new IllegalArgumentException("cannot resolve entry " + id);
            }
            tree = entry.subtree;
        }
        Entry entry = tree.store.get(parts[parts.length - 1]);
        if (entry == null) {
            throw new IllegalArgumentException("cannot resolve entry " + id);
        }
        return entry;
    }

    public EntryGroup resolveGroup(String id) {
        if (id == null) return root;
        Entry entry = resolve(id);
        if (entry.subgroup == null) {
            throw new IllegalArgumentException("entry " + id + " is not a group");
        }
        return entry.subgroup;
    }

    /** Executes a tree command (command pattern). */
    public void execute(Command command) {
        command.execute(this);
    }

    /** Creates an entry under the given parent group. */
    public String create(EntryOptions options, String parent) {
        EntryGroup group = resolveGroup(parent);
        group.data.add(options);
        write();
        return group.create(options);
    }

    public void remove(String id) {
        Entry entry = resolve(id);
        entry.parent.remove(id);
        entry.parent.tree.write();
    }

    /** Updates an entry, optionally moving it to another parent group. */
    public void update(String id, EntryOptions options, String parent) {
        Entry entry = resolve(id);
        EntryGroup source = entry.parent;
        if (parent != null) {
            EntryGroup target = resolveGroup(parent);
            source.unlink(entry.options);
            target.data.add(entry.options);
            target.tree.write();
            entry.parent = target;
        }
        source.tree.write();
        entry.update(options, false, true);
    }

    /** Resolves the plugin body for the given entry name. */
    public abstract Plugin importPlugin(String name);

    /** Persists the current tree state. */
    public abstract void write();

    /** Waits for all pending entry tasks to settle. */
    public void await() {
        // entries load synchronously in this port
    }
}