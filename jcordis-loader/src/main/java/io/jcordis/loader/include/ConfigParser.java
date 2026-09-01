package io.jcordis.loader.include;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.jcordis.loader.EntryOptions;
import java.io.IOException;
import java.util.List;

/**
 * Strategy for serializing/deserializing {@link EntryOptions} config lists.
 *
 * <p>The {@code Include} plugin selects a parser by file extension, keeping the
 * format handling swappable (strategy pattern). YAML and JSON are supported.
 */
public interface ConfigParser {

    ObjectMapper mapper();

    /** Parses config file content into an entry list. */
    default List<EntryOptions> read(String content) throws IOException {
        List<EntryOptions> entries = mapper().readValue(content, new TypeReference<List<EntryOptions>>() {});
        normalizeGroups(entries);
        return entries;
    }

    /**
     * Recursively converts group {@code config} lists into {@link EntryOptions}
     * trees: Jackson only knows the top-level element type, so nested group
     * configs come back as raw maps and must be normalized here.
     */
    private void normalizeGroups(List<EntryOptions> entries) {
        for (EntryOptions entry : entries) {
            if (Boolean.TRUE.equals(entry.group) && entry.config instanceof List<?> list) {
                List<EntryOptions> children = new java.util.ArrayList<>();
                for (Object item : list) {
                    if (item instanceof EntryOptions child) {
                        children.add(child);
                    } else {
                        children.add(mapper().convertValue(item, EntryOptions.class));
                    }
                }
                entry.config = children;
                normalizeGroups(children);
            }
        }
    }

    /** Serializes an entry list to config file content. */
    default String write(List<EntryOptions> options) throws IOException {
        return mapper().writeValueAsString(options);
    }

    /** YAML parser. */
    ConfigParser YAML = new ConfigParser() {
        @Override
        public ObjectMapper mapper() {
            return new ObjectMapper(new YAMLFactory());
        }
    };

    /** JSON parser with pretty printing. */
    ConfigParser JSON = new ConfigParser() {
        @Override
        public ObjectMapper mapper() {
            return new ObjectMapper();
        }

        @Override
        public String write(List<EntryOptions> options) throws IOException {
            return mapper().writerWithDefaultPrettyPrinter().writeValueAsString(options);
        }
    };

    /** Selects a parser by file extension (falls back to JSON). */
    static ConfigParser forPath(String filename) {
        if (filename.endsWith(".yml") || filename.endsWith(".yaml")) {
            return YAML;
        }
        return JSON;
    }
}
