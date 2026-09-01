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
        return mapper().readValue(content, new TypeReference<List<EntryOptions>>() {});
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
