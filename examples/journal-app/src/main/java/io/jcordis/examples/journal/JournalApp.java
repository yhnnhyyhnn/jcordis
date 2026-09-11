package io.jcordis.examples.journal;

import io.jcordis.core.context.Context;
import io.jcordis.core.logger.ConsoleExporter;
import io.jcordis.core.registry.Plugin;
import io.jcordis.loader.EntryOptions;
import io.jcordis.loader.Loader;
import io.jcordis.loader.include.Include;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Runnable demo of two-way configuration sync (the {@code Include} journal):
 * the YAML file and the running tree never overwrite each other.
 *
 * <ol>
 *   <li><b>runtime → file</b>: {@code loader.update()} on an entry owned by the
 *       file is written back — a plugin that disables itself or changes its own
 *       config survives the next restart;</li>
 *   <li><b>file → tree</b>: editing the file (in production through
 *       {@code Hmr.watch()}, see {@code examples/hmr-app}) re-applies it;</li>
 *   <li><b>conflict → file wins</b>: when both sides changed the same entry,
 *       the file is kept and the conflict is reported.</li>
 * </ol>
 *
 * <p>Run with {@code mvn -pl examples/journal-app exec:java}.
 */
public final class JournalApp {

    private JournalApp() {}

    public static void main(String[] args) throws IOException {
        Context root = Context.create();
        new ConsoleExporter(root);
        // the demo works on a throwaway copy, so the checked-in example file
        // stays untouched and every run starts from the same state
        Path config = Path.of(System.getProperty("journal.config", "target/journal-app.yml"));
        prepare(config);

        Loader loader = new Loader(root);
        loader.builtin("greeter-plugin", Plugin.constructor(GreeterPlugin.class));

        Include include = run(loader, config);
        System.out.println("--- final file content (" + config + "):\n" + Files.readString(config));
        include.stop();
        root.fiber().disposeAsync().join();
    }

    /** Copies the bundled template to the demo file (creating parent directories). */
    private static void prepare(Path config) throws IOException {
        Files.createDirectories(config.toAbsolutePath().getParent());
        try (InputStream template = JournalApp.class.getResourceAsStream("/app.yml")) {
            if (template == null) throw new IllegalStateException("bundled app.yml template not found");
            Files.write(config, template.readAllBytes());
        }
    }

    /**
     * Wires the include onto {@code config} and walks the three sync directions.
     * The returned include owns the file and must be stopped by the caller.
     */
    public static Include run(Loader loader, Path config) throws IOException {
        Map<String, Object> includeConfig = Map.of("path", config.toString());
        Include include = new Include(loader.ctx(), includeConfig);
        include.apply(loader.ctx(), includeConfig);
        include.flush();
        System.out.println("loaded from app.yml: " + name(loader));

        System.out.println("--- 1. a runtime change is written back to the file");
        EntryOptions updated = new EntryOptions();
        updated.config = Map.of("name", "runtime");
        loader.update("greeter", updated, null);
        include.flush();
        System.out.println("app.yml now holds: " + readName(config));

        System.out.println("--- 2. editing the file reaches the tree");
        Files.writeString(config, INITIAL.replace("world", "from-file"), StandardCharsets.UTF_8);
        include.refresh();
        include.flush();
        System.out.println("plugin reloaded with: " + name(loader));

        System.out.println("--- 3. conflicting edit: the file wins");
        Files.writeString(config, INITIAL.replace("world", "file-wins"), StandardCharsets.UTF_8);
        EntryOptions conflicting = new EntryOptions();
        conflicting.config = Map.of("name", "runtime-loses");
        loader.update("greeter", conflicting, null);
        include.flush();
        System.out.println("app.yml kept: " + readName(config) + " (the runtime update was discarded)");
        return include;
    }

    /** The greeter entry's current config value, straight from the tree. */
    private static String name(Loader loader) {
        Object config = loader.resolve("greeter").options.config;
        return config instanceof Map<?, ?> map ? String.valueOf(map.get("name")) : "?";
    }

    private static String readName(Path config) throws IOException {
        String content = Files.readString(config);
        // the entry's config value is the last `name:` in the file
        int index = content.lastIndexOf("name:");
        if (index < 0) return content.trim();
        return content.substring(index + "name:".length()).trim().split("\\s")[0];
    }

    private static final String INITIAL =
            """
            - id: greeter
              name: greeter-plugin
              config:
                name: world
            """;
}
