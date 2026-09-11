package io.jcordis.loader;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A tree of entries, mirroring Cordis's {@code EntryTree}.
 *
 * <p>Owns the tree context, the root {@link EntryGroup}, and the id-to-entry
 * store. Subclasses provide {@link #importPlugin} (plugin resolution) and may
 * override {@link #persist} (config persistence). Every mutation is reported to
 * {@link #commit(EntryChange)}, which forwards it to the listeners registered
 * on the root tree — this is how a persistence layer such as {@code Include}
 * learns about runtime changes.
 */
public abstract class EntryTree {

    public static final String SEP = ":";

    /** Notified synchronously after a tree-side mutation has been applied. */
    public interface CommitListener {
        void onCommit(EntryChange change);
    }

    private final Context treeCtx;
    private volatile Loader loader;
    private final EntryTree parentTree;
    private final List<CommitListener> commitListeners = new CopyOnWriteArrayList<>();
    public final EntryGroup root;
    public final Map<String, Entry> store = new ConcurrentHashMap<>();
    public boolean enableLogs;

    protected EntryTree(Context ctx, Loader loader) {
        this(ctx, loader, null);
    }

    protected EntryTree(Context ctx, Loader loader, EntryTree parentTree) {
        this.treeCtx = ctx.extend();
        this.loader = loader;
        this.parentTree = parentTree;
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
        String id = group.tree.ensureId(options);
        group.data.add(options);
        group.tree.commit(EntryChange.created(id, group, options));
        return group.create(options);
    }

    public void remove(String id) {
        Entry entry = resolve(id);
        EntryGroup group = entry.parent;
        EntryOptions legacy = entry.options;
        group.remove(id);
        group.tree.commit(EntryChange.removed(id, group, legacy));
    }

    /** Updates an entry, optionally moving it to another parent group. */
    public void update(String id, EntryOptions options, String parent) {
        Entry entry = resolve(id);
        EntryGroup source = entry.parent;
        EntryOptions legacy = entry.options.copy();
        if (parent != null) {
            EntryGroup target = resolveGroup(parent);
            source.unlink(entry.options);
            target.data.add(entry.options);
            entry.parent = target;
        }
        // `Entry.update` assigns the new options before it returns, so the
        // change is fully visible to `commit()` (mirrors Cordis's ordering)
        entry.update(options, false, true);
        EntryGroup group = entry.parent;
        if (group.tree != source.tree) {
            source.tree.commit(EntryChange.removed(legacy.id, source, legacy));
        }
        group.tree.commit(
                EntryChange.updated(legacy.id, group, group == source ? null : source, entry.options, legacy));
    }

    /**
     * Moves the entry to another group ({@code null} = root), reloading it
     * under the target group's context. Mirrors Cordis's
     * {@code EntryTree.update(id, options, parent)} with an explicit parent.
     */
    public void transfer(String id, String parent) {
        Entry entry = resolve(id);
        EntryGroup source = entry.parent;
        EntryGroup target = parent == null ? root : resolveGroup(parent);
        EntryOptions legacy = entry.options.copy();
        source.unlink(entry.options);
        target.data.add(entry.options);
        entry.parent = target;
        entry.update(new EntryOptions(), false, true);
        if (target.tree != source.tree) {
            source.tree.commit(EntryChange.removed(legacy.id, source, legacy));
        }
        target.tree.commit(
                EntryChange.updated(legacy.id, target, target == source ? null : source, entry.options, legacy));
    }

    /** Resolves the plugin body for the given entry name. */
    public abstract Plugin importPlugin(String name);

    /**
     * Reports a tree-side mutation: runs the subclass persistence hook, then
     * notifies every listener registered on the root tree. Mirrors Cordis's
     * {@code EntryTree.commit}.
     */
    public final void commit(EntryChange change) {
        persist(change);
        for (CommitListener listener : rootTree().commitListeners) {
            try {
                listener.onCommit(change);
            } catch (Throwable error) {
                treeCtx.logger("loader").warn("loader commit listener failed for entry " + change.id + ": " + error);
            }
        }
    }

    /** Persistence hook for subclasses (the root tree is in-memory only). */
    protected void persist(EntryChange change) {
        // no-op by default
    }

    /** Registers a commit listener on the root tree. */
    public void addCommitListener(CommitListener listener) {
        rootTree().commitListeners.add(listener);
    }

    /** Removes a previously registered commit listener. */
    public void removeCommitListener(CommitListener listener) {
        rootTree().commitListeners.remove(listener);
    }

    private EntryTree rootTree() {
        EntryTree tree = this;
        while (tree.parentTree != null) {
            tree = tree.parentTree;
        }
        return tree;
    }

    /** Pending entry tasks (initialization or in-flight fiber work). */
    public List<Object> getTasks() {
        List<Object> tasks = new ArrayList<>();
        for (Entry entry : entries()) {
            if (entry._initTask != null) {
                tasks.add(entry._initTask);
            }
            if (entry.fiber != null && entry.fiber.inertia() != null) {
                tasks.add(entry.fiber.inertia());
            }
        }
        return tasks;
    }

    /** Waits for all pending entry tasks to settle. */
    public void await() {
        while (true) {
            List<Object> tasks = getTasks();
            if (tasks.isEmpty()) return;
            CompletableFuture<?>[] futures = tasks.stream()
                    .map(task -> task instanceof CompletableFuture<?> future
                            ? future
                            : CompletableFuture.completedFuture(null))
                    .toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(futures).handle((ignored, error) -> null).join();
        }
    }
}
