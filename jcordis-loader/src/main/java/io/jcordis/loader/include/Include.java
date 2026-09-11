package io.jcordis.loader.include;

import io.jcordis.core.context.Context;
import io.jcordis.core.event.EventOptions;
import io.jcordis.core.registry.Plugin;
import io.jcordis.loader.Entry;
import io.jcordis.loader.EntryChange;
import io.jcordis.loader.EntryGroup;
import io.jcordis.loader.EntryOptions;
import io.jcordis.loader.EntryTree;
import io.jcordis.loader.Loader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Include plugin, mirroring {@code @cordisjs/plugin-include}.
 *
 * <p>Loads an {@link EntryOptions} list from a YAML/JSON config file and feeds
 * it into the loader tree. The file and the tree stay in sync in both
 * directions:
 *
 * <ul>
 *   <li>a file edit is re-read (through {@code Hmr.watch()}) and applied to the
 *       tree — {@link #refresh()};
 *   <li>a runtime change to an entry owned by this file (a plugin disabling
 *       itself, {@code loader.update()}, config updates made by the plugin) is
 *       recorded in a {@link Journal} and written back — mirroring Cordis's
 *       {@code Include.commit(EntryChange)}.
 * </ul>
 *
 * <p>When both sides changed the same entry, the file wins (the conflict is
 * reported). Writes go through a temp file plus an atomic move, so readers never
 * observe a truncated file.
 */
public class Include implements Plugin {

    /** The last file state we read or wrote. */
    private record Snapshot(String content, List<EntryOptions> data) {}

    /** An id we assigned to an entry the file leaves anonymous. */
    private record Anonymous(String parent, EntryOptions options) {}

    private final Path path;
    private final List<EntryOptions> initial;
    private final List<Map<String, Object>> patches;

    private final Map<String, Journal.Record> journal = new LinkedHashMap<>();
    /** Ids we assigned to entries the file leaves anonymous. */
    private final Map<String, Anonymous> anonymous = new LinkedHashMap<>();
    /** Ids owned by patches: their changes belong to the patch, not the file. */
    private final Set<String> patchOwned = new HashSet<>();

    private final Object drainLock = new Object();
    private boolean draining;
    private boolean dirtyRead;
    private boolean dirtyWrite;

    private Snapshot cache;
    private Loader loader;
    private volatile boolean disposed;

    public Include(Context ctx, Map<String, Object> config) {
        this.path = Paths.get((String) config.get("path"));
        this.initial = toEntryOptions(config.get("initial"));
        this.patches = config.get("patches") instanceof List<?> list ? castMaps(list) : List.of();
    }

    @Override
    public Object apply(Context ctx, Object config) {
        this.loader = (Loader) ctx.get("loader");
        if (loader == null) {
            throw new IllegalStateException("include requires the loader service");
        }
        // a plugin updating the include config (matching `path`) re-applies the
        // parsed tree (mirrors Cordis's internal/update listener)
        ctx.on(
                "internal/update",
                (thisArg, args) -> {
                    Object updated = args.length > 0 ? args[0] : null;
                    if (updated instanceof Map<?, ?> map
                            && map.get("path") != null
                            && map.get("path").equals(path.toString())) {
                        refresh();
                        return null;
                    }
                    if (args.length > 2 && args[2] instanceof java.util.function.Supplier<?> next) {
                        return next.get();
                    }
                    return null;
                },
                EventOptions.of(true, true));
        try {
            if (!Files.exists(path)) {
                if (initial == null) {
                    throw new IllegalStateException("config file not found: " + path);
                }
                writeText(dump(initial));
            }
            cache = new Snapshot(Files.readString(path, StandardCharsets.UTF_8), null);
            List<EntryOptions> data = parse(cache.content());
            assignIds(data);
            cache = new Snapshot(cache.content(), data);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read config file: " + path, e);
        }
        // runtime changes to entries owned by this file are written back
        EntryTree.CommitListener listener = this::onCommit;
        loader.addCommitListener(listener);
        applyTree();
        // if the hmr service is available, let this include own its config file:
        // the file is re-read and re-applied on change (mirrors Cordis's
        // `ctx.hmr.watch(filename, refresh)`); registrations are de-duplicated
        Object hmr = ctx.get("hmr");
        if (hmr instanceof Hmr instance && !instance.isWatching(path)) {
            instance.watch(path, this::refresh);
        }
        return (io.jcordis.core.util.Disposable) () -> {
            loader.removeCommitListener(listener);
            stop();
            loader.ctx().registry().delete(this);
        };
    }

    /** Stops the include: flushes pending changes and marks it disposed. */
    public void stop() {
        disposed = true;
        flush();
    }

    /**
     * Re-reads the config file and re-applies the parsed tree, mirroring
     * Cordis's {@code Include.refresh()}. Registered as an hmr watch when the
     * service is available, and callable directly.
     */
    public void refresh() {
        synchronized (drainLock) {
            dirtyRead = true;
        }
        drain();
    }

    /** Waits until every pending read and write has been processed. */
    public void flush() {
        synchronized (drainLock) {
            while (draining) {
                try {
                    drainLock.wait(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    // -------------------------------------------------------------- tree -> file

    /**
     * Records a tree-side change (mirrors Cordis's {@code Include.commit}).
     * Only entries this file owns are journalled; changes to entries that live
     * outside the file (other includes, programmatically created entries under
     * foreign groups) are ignored.
     */
    private void onCommit(EntryChange change) {
        if (disposed || cache == null) return;
        if (!owns(change.id, change.group)) return;
        Journal.record(journal, change, groupId(change.group));
        synchronized (drainLock) {
            dirtyWrite = true;
        }
        drain();
    }

    /** Whether this file owns the entry (an id in the file, or inside one of its groups). */
    private boolean owns(String id, EntryGroup group) {
        if (cache == null) return false;
        Map<String, Journal.Flat> flat = Journal.flatten(cache.data());
        if (flat.containsKey(id)) return true;
        String parent = groupId(group);
        if (parent != null && flat.containsKey(parent)) return true;
        return false;
    }

    /** The file-side parent id of a group (null for the root). */
    private static String groupId(EntryGroup group) {
        Object entry = group.ctx.fiber() == null ? null : group.ctx.fiber().entry();
        return entry instanceof Entry parent ? parent.options.id : null;
    }

    /** Number of changes waiting to reach the file (diagnostics/tests). */
    public int pendingChanges() {
        return journal.size();
    }

    // ------------------------------------------------------------------ draining

    /**
     * Runs the read/write loop until neither side has pending work. Re-entrant
     * calls (a change arriving while the loop runs — for instance from the
     * commit a re-apply produces) return immediately; the running loop picks the
     * work up because {@code draining} is cleared under the same lock that
     * decides to stop.
     */
    private void drain() {
        synchronized (drainLock) {
            if (draining) return;
            draining = true;
        }
        try {
            while (true) {
                boolean read;
                boolean write;
                synchronized (drainLock) {
                    read = dirtyRead;
                    write = dirtyWrite;
                    dirtyRead = false;
                    dirtyWrite = false;
                    if (!read && !write) {
                        draining = false;
                        drainLock.notifyAll();
                        return;
                    }
                }
                if (read) {
                    try {
                        readAndApply();
                    } catch (Throwable error) {
                        warn("failed to apply config file " + path + ": " + error);
                        // leave `dirtyWrite` untouched: writing needs a known file state
                        if (!dirtyRead) continue;
                    }
                }
                if (write) {
                    try {
                        writeBack();
                    } catch (Throwable error) {
                        error("failed to write config file " + path + ", " + journal.size()
                                + " pending change(s) kept in memory: " + error);
                    }
                }
            }
        } catch (Throwable error) {
            synchronized (drainLock) {
                draining = false;
                drainLock.notifyAll();
            }
            throw error;
        }
    }

    /** Re-reads the file, reconciles it with the journal and applies it. */
    private void readAndApply() {
        String content;
        try {
            content = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            warn("cannot read config file " + path + ": " + e);
            return;
        }
        if (cache != null && content.equals(cache.content())) return;

        List<EntryOptions> data;
        try {
            data = parse(content);
        } catch (Exception e) {
            // half-written files are routine while editing, hence a warning only
            warn("cannot parse config file " + path + ": " + e);
            return;
        }
        assignIds(data);
        if (cache != null) {
            for (Journal.Conflict conflict :
                    Journal.reconcile(journal, Journal.flatten(cache.data()), Journal.flatten(data), this::fileOwned)) {
                error("config conflict in " + path + ": entry " + conflict.id() + " " + conflict.reason()
                        + "; file wins");
            }
        }
        cache = new Snapshot(content, data);
        applyTree();
        // the file changed while tree-side changes were pending: write the merge
        if (!journal.isEmpty()) {
            synchronized (drainLock) {
                dirtyWrite = true;
            }
        }
    }

    /** Writes the journalled changes back to the file (idempotent). */
    private void writeBack() {
        if (journal.isEmpty() || cache == null) return;
        String current;
        try {
            current = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            warn("config file " + path + " is missing, " + journal.size() + " pending change(s) kept in memory");
            return;
        }
        if (!current.equals(cache.content())) {
            // someone else wrote the file: merge first, then come back
            synchronized (drainLock) {
                dirtyRead = true;
                dirtyWrite = true;
            }
            return;
        }
        List<EntryOptions> data = copyTree(cache.data());
        Journal.apply(data, journal, this::fileOwned, this::warn);
        if (Journal.equalsOptionsList(data, cache.data())) {
            // nothing for the file itself; the tree already reflects the change
            journal.clear();
            return;
        }
        String text;
        try {
            text = dump(data);
            writeText(text);
        } catch (IOException e) {
            warn("failed to write config file " + path + ", " + journal.size() + " pending change(s) kept in memory: "
                    + e);
            return;
        }
        cache = new Snapshot(text, data);
        journal.clear();
    }

    // --------------------------------------------------------------- tree application

    /** Applies the current file state (patched and journalled) onto the loader tree. */
    private void applyTree() {
        List<EntryOptions> tree = copyTree(cache.data());
        applyPatches(tree);
        Journal.apply(tree, journal, this::fileOwned, this::warn);
        loader.read(tree);
    }

    /** Whether the file owns a change, mirroring Cordis's {@code PatchIndex.fileOwned}. */
    private boolean fileOwned(String id, String key) {
        return !patchOwned.contains(id);
    }

    // -------------------------------------------------------------------- file IO

    private List<EntryOptions> parse(String content) throws IOException {
        return ConfigParser.forPath(path.toString()).read(content);
    }

    private String dump(List<EntryOptions> options) throws IOException {
        return ConfigParser.forPath(path.toString()).write(options);
    }

    private void writeText(String content) throws IOException {
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private void warn(String message) {
        if (loader == null) return;
        loader.ctx().logger("loader").warn(message);
    }

    private void error(String message) {
        if (loader == null) return;
        loader.ctx().logger("loader").error(message);
    }

    // ------------------------------------------------------------- anonymous ids

    /**
     * Entries the file leaves anonymous get an id here, before the loader sees
     * them. One that looks exactly like an entry we named on the previous read
     * keeps that id, so editing a neighbor does not restart it; anything else
     * gets a fresh id. Assigned ids stay in memory until the next write.
     */
    private void assignIds(List<EntryOptions> data) {
        Set<String> used = new HashSet<>();
        List<Object[]> pending = new ArrayList<>();
        walkAnonymous(data, null, used, pending);

        Map<String, Anonymous> previous = new LinkedHashMap<>(anonymous);
        anonymous.clear();
        for (Object[] item : pending) {
            EntryOptions options = (EntryOptions) item[0];
            String parent = (String) item[1];
            String id = null;
            var iterator = previous.entrySet().iterator();
            while (iterator.hasNext()) {
                var candidate = iterator.next();
                Anonymous info = candidate.getValue();
                if (!java.util.Objects.equals(info.parent(), parent) || !Journal.equals(info.options(), options))
                    continue;
                id = candidate.getKey();
                iterator.remove();
                break;
            }
            if (id == null) {
                do {
                    id = Long.toHexString((long) (Math.random() * 0xffffffffL));
                } while (used.contains(id) || loaderPipelineStoreContains(id));
            }
            used.add(id);
            anonymous.put(id, new Anonymous(parent, options.copy()));
            options.id = id;
        }
    }

    private void walkAnonymous(List<EntryOptions> data, String parent, Set<String> used, List<Object[]> pending) {
        for (EntryOptions options : data) {
            if (options.id != null) {
                used.add(options.id);
            } else {
                pending.add(new Object[] {options, parent});
            }
            if (Boolean.TRUE.equals(options.group) && options.config instanceof List<?> nested) {
                List<EntryOptions> children = new ArrayList<>();
                for (Object item : nested) {
                    if (item instanceof EntryOptions child) children.add(child);
                }
                walkAnonymous(children, options.id, used, pending);
            }
        }
    }

    private boolean loaderPipelineStoreContains(String id) {
        return loader != null && loader.store.containsKey(id);
    }

    /** Deep copy of an option list, so patching never mutates the cached file state. */
    private static List<EntryOptions> copyTree(List<EntryOptions> source) {
        List<EntryOptions> copy = new ArrayList<>(source.size());
        for (EntryOptions options : source) {
            EntryOptions next = options.copy();
            if (options.config instanceof List<?> nested) {
                List<EntryOptions> children = new ArrayList<>();
                for (Object item : nested) {
                    if (item instanceof EntryOptions child) children.add(child);
                }
                next.config = copyTree(children);
            }
            copy.add(next);
        }
        return copy;
    }

    // --------------------------------------------------------------------- patches

    private void applyPatches(List<EntryOptions> entries) {
        if (patches.isEmpty()) return;
        Map<String, EntryOptions> entryMap = new HashMap<>();
        buildMap(entries, entryMap);

        for (Map<String, Object> patch : patches) {
            String id = (String) patch.get("id");
            Object insert = patch.get("insert");
            String name = (String) patch.get("name");

            if (insert != null) {
                List<EntryOptions> inserted = toEntryOptions(insert);
                if (id != null) {
                    EntryOptions target = entryMap.get(id);
                    if (target == null || !Boolean.TRUE.equals(target.group)) {
                        warn("patch insert: entry " + id + " not found or not a group");
                        continue;
                    }
                    @SuppressWarnings("unchecked")
                    List<EntryOptions> targetConfig =
                            target.config instanceof List<?> l ? (List<EntryOptions>) l : new ArrayList<>();
                    targetConfig.addAll(inserted);
                    target.config = targetConfig;
                    patchOwned.add(id);
                } else {
                    for (EntryOptions options : inserted) {
                        if (options.id != null) {
                            patchOwned.add(options.id);
                        }
                    }
                    entries.addAll(inserted);
                }
                continue;
            }

            if (id == null) {
                warn("patch: id is required for non-insert patches");
                continue;
            }
            EntryOptions target = entryMap.get(id);
            if (target == null) {
                warn("patch: entry " + id + " not found");
                continue;
            }
            if (name != null && !name.equals(target.name)) {
                warn("patch: name mismatch for " + id + " (expected " + target.name + ", got " + name + ")");
                continue;
            }
            patchOwned.add(id);
            for (Map.Entry<String, Object> kv : patch.entrySet()) {
                if (kv.getKey().equals("id")
                        || kv.getKey().equals("name")
                        || kv.getKey().equals("insert")) continue;
                applyOverride(target, kv.getKey(), kv.getValue());
            }
        }
    }

    private void buildMap(List<EntryOptions> entries, Map<String, EntryOptions> map) {
        for (EntryOptions entry : entries) {
            if (entry.id != null) map.put(entry.id, entry);
            if (Boolean.TRUE.equals(entry.group) && entry.config instanceof List<?> list) {
                List<EntryOptions> children = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof EntryOptions child) children.add(child);
                }
                buildMap(children, map);
            }
        }
    }

    private void applyOverride(EntryOptions target, String key, Object value) {
        switch (key) {
            case "config" -> target.config = value;
            case "disabled" -> target.disabled = value instanceof Boolean b ? b : null;
            case "group" -> target.group = value instanceof Boolean b ? b : null;
            case "inject" -> target.inject = castStringMap(value);
            case "intercept" -> target.intercept = castStringMap(value);
            case "isolate" -> target.isolate = castStringMap(value);
            default -> {
                // unknown keys are ignored
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castMaps(List<?> list) {
        return (List<Map<String, Object>>) (List<?>) list;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castStringMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    @SuppressWarnings("unchecked")
    private static List<EntryOptions> toEntryOptions(Object value) {
        if (value == null) return null;
        if (value instanceof List<?> list) {
            return (List<EntryOptions>) (List<?>) list;
        }
        return null;
    }
}
