package io.jcordis.loader.include;

import io.jcordis.loader.EntryChange;
import io.jcordis.loader.EntryOptions;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Tree-side changes that have not reached the config file yet, mirroring
 * Cordis's {@code journal.ts}.
 *
 * <p>The journal is what makes the file and the tree stay in sync in both
 * directions: a runtime change (a plugin disabling itself, an entry update)
 * is recorded here and written back to the file, while a file edit is
 * reconciled against it so that the file always wins a conflict.
 */
public final class Journal {

    private Journal() {}

    /** Entry option keys a journal change can carry (mirrors JS object keys). */
    private static final List<String> FIELDS =
            List.of("name", "config", "group", "disabled", "inject", "intercept", "isolate");

    /**
     * One pending mutation for a single entry.
     *
     * <p>{@link #removed} means the entry was removed; otherwise it is an
     * upsert. {@link #hasParent} distinguishes "not relocated" from
     * "relocated to the root" ({@code parent == null}).
     */
    public static final class Record {
        public boolean removed;
        /** The entry did not exist when the tree first reported it. */
        public boolean created;

        public boolean hasParent;
        /** Group id the entry lives in, or {@code null} for the root. */
        public String parent;
        /** Index within {@code parent}; only meaningful alongside {@code hasParent}. */
        public int position;
        /** Per-key delta relative to the options at report time (null deletes). */
        public Map<String, Object> changes = new LinkedHashMap<>();
    }

    /** A flattened view of one entry inside a config list. */
    public static final class Flat {
        public final String parent;
        public final int position;
        public final EntryOptions options;
        public final List<EntryOptions> list;

        Flat(String parent, int position, EntryOptions options, List<EntryOptions> list) {
            this.parent = parent;
            this.position = position;
            this.options = options;
            this.list = list;
        }
    }

    /** A conflict that reconciliation resolved in favor of the file. */
    public record Conflict(String id, String reason) {}

    /** Warn sink used when a change cannot be placed (missing group). */
    public interface Warn {
        void warn(String message);
    }

    // ---------------------------------------------------------------- recording

    /** Folds a later record into an earlier one for the same entry. */
    public static Record mergeRecords(Record older, Record newer) {
        if (older == null) return newer;
        if (newer.removed) {
            // an entry created and removed before ever being written leaves no trace
            return older.created && !older.removed ? null : newer;
        }
        if (older.removed) {
            newer.created = false;
            return newer;
        }
        boolean relocated = newer.hasParent;
        Record merged = new Record();
        merged.created = older.created;
        merged.hasParent = relocated;
        merged.parent = relocated ? newer.parent : older.parent;
        merged.position = relocated ? newer.position : older.position;
        merged.changes.putAll(older.changes);
        merged.changes.putAll(newer.changes);
        return merged;
    }

    /** Merges a record into the journal for {@code id}. */
    public static void merge(Map<String, Record> journal, String id, Record record) {
        Record merged = mergeRecords(journal.get(id), record);
        if (merged != null) {
            journal.put(id, merged);
        } else {
            journal.remove(id);
        }
    }

    /** Records a tree-side change; {@code parent} is the group id (null = root). */
    public static void record(Map<String, Record> journal, EntryChange change, String parent) {
        if (change.options == null) {
            Record removal = new Record();
            removal.removed = true;
            merge(journal, change.id, removal);
            return;
        }
        Record record = new Record();
        record.position = change.group.data.indexOf(change.options);
        if (change.legacy == null) {
            record.created = true;
            record.hasParent = true;
            record.parent = parent;
            for (String field : FIELDS) {
                record.changes.put(field, field(change.options, field));
            }
            merge(journal, change.id, record);
            return;
        }
        record.created = false;
        if (change.from != null) {
            record.hasParent = true;
            record.parent = parent;
        }
        record.changes = diff(change.legacy, change.options);
        merge(journal, change.id, record);
    }

    // ------------------------------------------------------------ option access

    /** The value of a persistable option field. */
    public static Object field(EntryOptions options, String key) {
        return switch (key) {
            case "name" -> options.name;
            case "config" -> options.config;
            case "group" -> options.group;
            case "disabled" -> options.disabled;
            case "inject" -> options.inject;
            case "intercept" -> options.intercept;
            case "isolate" -> options.isolate;
            default -> null;
        };
    }

    /** Assigns a persistable option field; a null value clears it. */
    public static void setField(EntryOptions options, String key, Object value) {
        switch (key) {
            case "name" -> options.name = (String) value;
            case "config" -> options.config = value;
            case "group" -> options.group = (Boolean) value;
            case "disabled" -> options.disabled = (Boolean) value;
            case "inject" -> options.inject = asMap(value);
            case "intercept" -> options.intercept = asMap(value);
            case "isolate" -> options.isolate = asMap(value);
            default -> {
                // unknown keys are ignored
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    /** Per-key delta between two option sets (key order: field declaration order). */
    public static Map<String, Object> diff(EntryOptions legacy, EntryOptions options) {
        Map<String, Object> changes = new LinkedHashMap<>();
        for (String key : FIELDS) {
            Object before = field(legacy, key);
            Object after = field(options, key);
            if (!equals(before, after)) {
                changes.put(key, after);
            }
        }
        return changes;
    }

    // ------------------------------------------------------------ list surgery

    /** Indexes every entry (including nested group members) by id. */
    public static Map<String, Flat> flatten(List<EntryOptions> data) {
        Map<String, Flat> result = new LinkedHashMap<>();
        flatten(data, null, result);
        return result;
    }

    private static void flatten(List<EntryOptions> data, String parent, Map<String, Flat> result) {
        for (int position = 0; position < data.size(); position++) {
            EntryOptions options = data.get(position);
            if (options.id != null) {
                result.put(options.id, new Flat(parent, position, options, data));
            }
            if (Boolean.TRUE.equals(options.group) && options.config instanceof List<?> nested) {
                List<EntryOptions> children = new ArrayList<>();
                for (Object item : nested) {
                    if (item instanceof EntryOptions child) children.add(child);
                }
                flatten(children, options.id, result);
            }
        }
    }

    /** Removes an entry (wherever it is nested) from the config list. */
    public static void detach(List<EntryOptions> data, String id) {
        Flat flat = flatten(data).get(id);
        if (flat == null) return;
        flat.list.remove(flat.options);
    }

    /**
     * Inserts {@code options} under {@code parent} (a group id, or {@code null}
     * for the root) at {@code position}, clamped to the target list's length.
     * Returns false when the parent group does not exist.
     */
    @SuppressWarnings("unchecked")
    public static boolean place(List<EntryOptions> data, EntryOptions options, String parent, int position) {
        List<EntryOptions> list = data;
        if (parent != null) {
            Flat group = flatten(data).get(parent);
            if (group == null || !Boolean.TRUE.equals(group.options.group)) return false;
            if (!(group.options.config instanceof List<?>)) {
                group.options.config = new ArrayList<EntryOptions>();
            }
            list = (List<EntryOptions>) group.options.config;
        }
        list.add(Math.min(Math.max(position, 0), list.size()), options);
        return true;
    }

    /** Applies a key delta onto an entry's options (a null value clears the key). */
    public static void applyChanges(EntryOptions options, Map<String, Object> changes) {
        for (Map.Entry<String, Object> change : changes.entrySet()) {
            setField(options, change.getKey(), change.getValue());
        }
    }

    /**
     * Overlays a journal onto {@code data} in place. {@code filter(id, key)}
     * restricts which changes apply — {@code key} is {@code null} when asking
     * about the entry as a whole (creation, removal, relocation).
     */
    public static List<EntryOptions> apply(
            List<EntryOptions> data, Map<String, Record> journal, Filter filter, Warn warn) {
        for (Map.Entry<String, Record> entry : journal.entrySet()) {
            String id = entry.getKey();
            Record record = entry.getValue();
            if (!filter.owned(id, null)) continue;
            if (record.removed) {
                detach(data, id);
                continue;
            }
            Flat flat = flatten(data).get(id);
            if (flat == null) {
                EntryOptions options = new EntryOptions();
                options.id = id;
                for (Map.Entry<String, Object> change : record.changes.entrySet()) {
                    if (filter.owned(id, change.getKey())) {
                        setField(options, change.getKey(), change.getValue());
                    }
                }
                String parent = record.hasParent ? record.parent : null;
                if (!place(data, options, parent, record.position)) {
                    if (warn != null) warn.warn("cannot place entry " + id + ": group " + parent + " not found");
                }
                continue;
            }
            for (Map.Entry<String, Object> change : record.changes.entrySet()) {
                if (filter.owned(id, change.getKey())) {
                    setField(flat.options, change.getKey(), change.getValue());
                }
            }
            if (record.hasParent && !Objects.equals(flat.parent, record.parent)) {
                detach(data, id);
                if (!place(data, flat.options, record.parent, record.position)) {
                    if (warn != null) {
                        warn.warn("cannot move entry " + id + ": group " + record.parent + " not found");
                    }
                    place(data, flat.options, flat.parent, flat.position);
                }
            }
        }
        return data;
    }

    /** Which changes the file is allowed to own. */
    public interface Filter {
        boolean owned(String id, String key);
    }

    /**
     * Three-way reconciliation of a journal against a new file state. The file
     * wins every conflict: conflicting keys are dropped from the journal so the
     * tree follows the file, and each drop is reported.
     */
    public static List<Conflict> reconcile(
            Map<String, Record> journal, Map<String, Flat> base, Map<String, Flat> theirs, Filter fileOwned) {
        List<Conflict> conflicts = new ArrayList<>();
        List<String> resolved = new ArrayList<>();
        for (Map.Entry<String, Record> entry : journal.entrySet()) {
            String id = entry.getKey();
            Record record = entry.getValue();
            if (!fileOwned.owned(id, null)) continue;
            Flat before = base.get(id);
            Flat now = theirs.get(id);
            if (record.removed) {
                if (now == null) {
                    resolved.add(id);
                } else if (before == null || !equals(now.options, before.options)) {
                    conflicts.add(new Conflict(
                            id,
                            before != null
                                    ? "modified in file, removed at runtime"
                                    : "added in file, removed at runtime"));
                    resolved.add(id);
                }
                continue;
            }
            if (now == null) {
                if (before != null) {
                    conflicts.add(new Conflict(id, "removed in file, modified at runtime"));
                    resolved.add(id);
                }
                // else: created at runtime, nothing in the file to conflict with
                continue;
            }
            record.created = false;
            for (String key : new ArrayList<>(record.changes.keySet())) {
                if (!fileOwned.owned(id, key)) continue;
                Object beforeValue = before == null ? null : field(before.options, key);
                Object nowValue = field(now.options, key);
                if (equals(beforeValue, nowValue) || equals(nowValue, record.changes.get(key))) continue;
                conflicts.add(new Conflict(id, "key \"" + key + "\" modified both in file and at runtime"));
                record.changes.remove(key);
            }
            boolean moved = record.hasParent && !Objects.equals(record.parent, now.parent);
            if (record.changes.isEmpty() && !moved) {
                resolved.add(id);
            }
        }
        for (String id : resolved) {
            journal.remove(id);
        }
        return conflicts;
    }

    // ------------------------------------------------------------------ equality

    /** Deep value equality across maps, collections and arrays. */
    public static boolean equals(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a instanceof Map<?, ?> left && b instanceof Map<?, ?> right) {
            if (left.size() != right.size()) return false;
            for (Map.Entry<?, ?> entry : left.entrySet()) {
                if (!right.containsKey(entry.getKey())) return false;
                if (!equals(entry.getValue(), right.get(entry.getKey()))) return false;
            }
            return true;
        }
        if (a instanceof Collection<?> left && b instanceof Collection<?> right) {
            if (left.size() != right.size()) return false;
            var i = left.iterator();
            var j = right.iterator();
            while (i.hasNext()) {
                if (!equals(i.next(), j.next())) return false;
            }
            return true;
        }
        if (a.getClass().isArray() && b.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(a);
            if (length != java.lang.reflect.Array.getLength(b)) return false;
            for (int i = 0; i < length; i++) {
                if (!equals(java.lang.reflect.Array.get(a, i), java.lang.reflect.Array.get(b, i))) return false;
            }
            return true;
        }
        // numbers arriving from YAML/JSON may differ in type (1 vs 1L)
        if (a instanceof Number left && b instanceof Number right) {
            return left.doubleValue() == right.doubleValue();
        }
        return a.equals(b);
    }

    /** Deep equality of two option lists (ids ignored). */
    public static boolean equalsOptionsList(List<EntryOptions> a, List<EntryOptions> b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!equals(a.get(i), b.get(i))) return false;
        }
        return true;
    }

    /** Deep equality of two option sets, ignoring the id. */
    public static boolean equals(EntryOptions a, EntryOptions b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        for (String key : FIELDS) {
            if (!equals(field(a, key), field(b, key))) return false;
        }
        return true;
    }
}
