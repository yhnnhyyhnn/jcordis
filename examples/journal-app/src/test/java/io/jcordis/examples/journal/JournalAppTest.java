package io.jcordis.examples.journal;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import io.jcordis.loader.EntryOptions;
import io.jcordis.loader.Loader;
import io.jcordis.loader.include.Include;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end coverage for the journal-app example: a runtime change is written
 * back to the file, a file edit reaches the tree, and a conflict keeps the file.
 */
class JournalAppTest {

    @TempDir
    Path tempDir;

    private static final String INITIAL =
            """
            - id: greeter
              name: greeter-plugin
              config:
                name: world
            """;

    private Path configFile() throws IOException {
        Path config = tempDir.resolve("app.yml");
        Files.writeString(config, INITIAL, StandardCharsets.UTF_8);
        return config;
    }

    private static Loader loader() {
        Context root = Context.create();
        Loader loader = new Loader(root);
        loader.builtin("greeter-plugin", new GreeterPlugin());
        return loader;
    }

    private static String treeName(Loader loader) {
        Object config = loader.resolve("greeter").options.config;
        return config instanceof Map<?, ?> map ? String.valueOf(map.get("name")) : "?";
    }

    @Test
    void runtimeChangeShouldBeWrittenBackToTheFile() throws IOException {
        Path config = configFile();
        Loader loader = loader();
        Include include = new Include(loader.ctx(), Map.of("path", config.toString()));
        include.apply(loader.ctx(), Map.of("path", config.toString()));
        assertThat(treeName(loader)).isEqualTo("world");

        EntryOptions updated = new EntryOptions();
        updated.config = Map.of("name", "runtime");
        loader.update("greeter", updated, null);
        include.flush();

        assertThat(Files.readString(config)).as("runtime change persisted").contains("runtime");
    }

    @Test
    void editingTheFileShouldReachTheTree() throws IOException {
        Path config = configFile();
        Loader loader = loader();
        Include include = new Include(loader.ctx(), Map.of("path", config.toString()));
        include.apply(loader.ctx(), Map.of("path", config.toString()));

        Files.writeString(config, INITIAL.replace("world", "from-file"), StandardCharsets.UTF_8);
        include.refresh();
        include.flush();

        assertThat(treeName(loader)).as("the tree followed the file").isEqualTo("from-file");
    }

    @Test
    void conflictingEditShouldKeepTheFile() throws IOException {
        Path config = configFile();
        Loader loader = loader();
        Include include = new Include(loader.ctx(), Map.of("path", config.toString()));
        include.apply(loader.ctx(), Map.of("path", config.toString()));

        // the file is edited without telling the include...
        Files.writeString(config, INITIAL.replace("world", "file-wins"), StandardCharsets.UTF_8);
        // ...and the same key changes at runtime
        EntryOptions conflicting = new EntryOptions();
        conflicting.config = Map.of("name", "runtime-loses");
        loader.update("greeter", conflicting, null);
        include.flush();

        assertThat(Files.readString(config)).as("file wins the conflict").contains("file-wins");
        assertThat(treeName(loader)).as("the tree follows the file").isEqualTo("file-wins");
        assertThat(include.pendingChanges()).isZero();
    }
}
