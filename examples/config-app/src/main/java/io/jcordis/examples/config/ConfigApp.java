package io.jcordis.examples.config;

import io.jcordis.core.context.Context;
import io.jcordis.core.logger.ConsoleExporter;
import io.jcordis.core.registry.Plugin;
import io.jcordis.loader.EntryOptions;
import io.jcordis.loader.Loader;
import io.jcordis.loader.include.Include;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Runnable demo of YAML-driven configuration: the include plugin reads
 * {@code app.yml} and feeds the loader tree — a feature plugin plus a nested
 * group with its own feature entry.
 *
 * <p>Run with {@code mvn -pl examples/config-app exec:java}.
 */
public final class ConfigApp {

    public static void main(String[] args) throws IOException {
        Context root = Context.create();
        new ConsoleExporter(root);
        Loader loader = new Loader(root);

        // the demo copies the bundled config next to the build output, so it
        // runs from any working directory and leaves the example untouched
        Path config = Path.of(System.getProperty("config.app.file", "target/config-app.yml"));
        Files.createDirectories(config.toAbsolutePath().getParent());
        try (InputStream template = ConfigApp.class.getResourceAsStream("/app.yml")) {
            if (template == null) throw new IllegalStateException("bundled app.yml template not found");
            Files.write(config, template.readAllBytes());
        }

        loader.builtin("feature-plugin", Plugin.constructor(FeaturePlugin.class));
        loader.builtin("group-plugin", Loader.GROUP_PLUGIN);
        loader.builtin("@cordisjs/plugin-include", (ctx, cfg) -> {
            Include include = new Include(ctx, (Map<String, Object>) cfg);
            return include.apply(ctx, cfg);
        });

        EntryOptions include = new EntryOptions();
        include.id = "config";
        include.name = "@cordisjs/plugin-include";
        include.config = Map.of("path", config.toString());
        loader.read(List.of(include));

        // both feature entries (root-level and nested in the group) are active
        System.out.println("entries loaded: " + loader.entries().size() + " (feature-a + group + nested-feature)");
        root.fiber().disposeAsync().join();
    }

    private ConfigApp() {}
}
