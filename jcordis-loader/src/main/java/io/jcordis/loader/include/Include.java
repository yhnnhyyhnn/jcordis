package io.jcordis.loader.include;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import io.jcordis.loader.Entry;
import io.jcordis.loader.EntryOptions;
import io.jcordis.loader.Loader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Include plugin, mirroring {@code @cordisjs/plugin-include}.
 *
 * <p>Loads an {@link EntryOptions} list from a YAML/JSON config file and feeds
 * it into the loader tree. {@code patches} can insert/override/disable entries
 * by id. Writes are atomic (tmp file + {@code ATOMIC_MOVE}).
 */
public class Include implements Plugin {

    private final Path path;
    private final List<EntryOptions> initial;
    private final List<Map<String, Object>> patches;

    private List<EntryOptions> data;
    private Loader loader;

    public Include(Context ctx, Map<String, Object> config) {
        this.path = Paths.get((String) config.get("path"));
        this.initial = toEntryOptions(config.get("initial"));
        this.patches = config.get("patches") instanceof List<?> list
                ? castMaps(list)
                : List.of();
    }

    @Override
    public Object apply(Context ctx, Object config) {
        this.loader = (Loader) ctx.get("loader");
        if (loader == null) {
            throw new IllegalStateException("include requires the loader service");
        }
        try {
            if (!Files.exists(path)) {
                if (initial == null) {
                    throw new IllegalStateException("config file not found: " + path);
                }
                writeFile(initial);
            }
            data = read();
        } catch (IOException e) {
            throw new IllegalStateException("cannot read config file: " + path, e);
        }
        List<EntryOptions> merged = applyPatches(new ArrayList<>(data));
        loader.read(merged);
        return (io.jcordis.core.util.Disposable) () -> loader.ctx().registry().delete(this);
    }

    private List<EntryOptions> read() throws IOException {
        String content = Files.readString(path, StandardCharsets.UTF_8);
        return ConfigParser.forPath(path.toString()).read(content);
    }

    private void writeFile(List<EntryOptions> options) throws IOException {
        ConfigParser parser = ConfigParser.forPath(path.toString());
        String content = parser.write(options);
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private List<EntryOptions> applyPatches(List<EntryOptions> entries) {
        if (patches.isEmpty()) return entries;
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
                        loader.ctx().logger("loader").warn("patch insert: entry " + id + " not found or not a group");
                        continue;
                    }
                    @SuppressWarnings("unchecked")
                    List<EntryOptions> targetConfig = target.config instanceof List<?> l
                            ? (List<EntryOptions>) l
                            : new ArrayList<>();
                    targetConfig.addAll(inserted);
                    target.config = targetConfig;
                } else {
                    entries.addAll(inserted);
                }
                continue;
            }

            if (id == null) {
                loader.ctx().logger("loader").warn("patch: id is required for non-insert patches");
                continue;
            }
            EntryOptions target = entryMap.get(id);
            if (target == null) {
                loader.ctx().logger("loader").warn("patch: entry " + id + " not found");
                continue;
            }
            if (name != null && !name.equals(target.name)) {
                loader.ctx().logger("loader").warn("patch: name mismatch for " + id + " (expected " + target.name + ", got " + name + ")");
                continue;
            }
            for (Map.Entry<String, Object> kv : patch.entrySet()) {
                if (kv.getKey().equals("id") || kv.getKey().equals("name") || kv.getKey().equals("insert")) continue;
                applyOverride(target, kv.getKey(), kv.getValue());
            }
        }
        return entries;
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