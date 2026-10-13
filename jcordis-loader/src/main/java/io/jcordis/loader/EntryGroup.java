package io.jcordis.loader;

import io.jcordis.core.context.Context;
import io.jcordis.core.fiber.Fiber;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A group of entries under a tree node, mirroring Cordis's {@code EntryGroup}.
 */
public final class EntryGroup {

    public final Context ctx;
    public final EntryTree tree;
    public final List<EntryOptions> data = new CopyOnWriteArrayList<>();

    public EntryGroup(Context ctx, EntryTree tree) {
        this.ctx = ctx;
        this.tree = tree;
    }

    /** Creates or updates the entry for the given options, returning its id. */
    public String create(EntryOptions options) {
        String id = tree.ensureId(options);
        Entry entry = tree.store.computeIfAbsent(id, key -> new Entry(this));
        entry.parent = this;
        entry.update(options, true, true);
        return entry.id();
    }

    public void unlink(EntryOptions options) {
        data.remove(options);
    }

    /** Removes the entry by id, disposing its fiber and clearing the store. */
    public void remove(String id) {
        Entry entry = tree.store.get(id);
        if (entry == null) return;
        // Unregister before disposing: the loader's `internal/plugin` handler
        // distinguishes "removed by the loader" from "disposed itself" by
        // checking whether the entry is still in the store (mirrors Cordis's
        // `EntryGroup.remove`)
        tree.store.remove(id);
        if (entry.fiber != null) {
            entry.fiber.disposeAsync().join();
        }
        // mirror Cordis: emit the partial-dispose event (legacy = current options)
        ctx.events().emit((Object) null, "loader/partial-dispose", entry, entry.options, false);
    }

    /** Disposes the entry's fiber but keeps the entry (used by group stop). */
    public void dispose(String id) {
        Entry entry = tree.store.get(id);
        if (entry == null) return;
        if (entry.fiber != null) {
            entry.fiber.disposeAsync().join();
            entry.fiber = null;
            entry.loaded = false;
        }
    }

    /** Applies a new config list, creating/removing inner entries. */
    public void update(List<EntryOptions> config) {
        List<EntryOptions> oldConfig = new ArrayList<>(data);
        data.clear();
        data.addAll(config);
        java.util.Map<String, EntryOptions> oldMap = new java.util.HashMap<>();
        for (EntryOptions options : oldConfig) {
            if (options.id != null) {
                oldMap.put(options.id, options);
            }
        }
        // each anonymous entry gets its own slot (mirrors Cordis's per-entry
        // Symbol keys) — a shared "anonymous" sentinel would fold them into one
        java.util.Map<String, EntryOptions> newMap = new java.util.LinkedHashMap<>();
        java.util.List<EntryOptions> anonymous = new java.util.ArrayList<>();
        for (EntryOptions options : config) {
            if (options.id != null) {
                newMap.put(options.id, options);
            } else {
                anonymous.add(options);
            }
        }
        // insertion-ordered union (old keys first, then new) — mirrors the
        // reference's {@code {...oldMap, ...newMap}} key order
        java.util.Set<String> ids = new java.util.LinkedHashSet<>(oldMap.keySet());
        ids.addAll(newMap.keySet());
        for (String id : ids) {
            if (newMap.containsKey(id)) {
                // one bad entry (e.g. an id containing the tree separator)
                // must not abort the rest of the batch (mirrors Cordis's
                // `create(...).catch(error => ctx.logger.error(error))`)
                try {
                    EntryOptions options = newMap.get(id);
                    Entry entry = tree.store.get(id);
                    if (entry != null) {
                        // existing entry: replace options in full, restart only on config change
                        entry.update(options, true, false);
                    } else {
                        create(options);
                    }
                } catch (RuntimeException | Error error) {
                    Context logger = tree.loader() != null ? tree.loader().ctx() : tree.treeContext();
                    logger.logger("loader").error(error);
                }
            } else {
                remove(id);
            }
        }
        for (EntryOptions options : anonymous) {
            try {
                create(options);
            } catch (RuntimeException | Error error) {
                Context logger = tree.loader() != null ? tree.loader().ctx() : tree.treeContext();
                logger.logger("loader").error(error);
            }
        }
    }

    /** Disposes every entry's fiber in the group, keeping the entries. */
    public void stop() {
        for (EntryOptions options : List.copyOf(data)) {
            dispose(options.id);
        }
    }

    /** Disposes every child entry fiber (group teardown helper). */
    public void disposeAll() {
        for (Entry entry : List.copyOf(tree.store.values())) {
            dispose(entry.options.id);
        }
    }

    /** The current fiber of this group's tree context. */
    public Fiber fiber() {
        return ctx.fiber();
    }
}
