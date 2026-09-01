package io.jcordis.examples.config;

import io.jcordis.core.context.Context;
import io.jcordis.core.logger.ConsoleExporter;
import io.jcordis.core.registry.Plugin;
import io.jcordis.loader.EntryOptions;
import io.jcordis.loader.Loader;
import io.jcordis.loader.include.Include;
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

    public static void main(String[] args) {
        Context root = Context.create();
        new ConsoleExporter(root);
        Loader loader = new Loader(root);

        loader.builtin("feature-plugin", Plugin.constructor(FeaturePlugin.class));
        loader.builtin("group-plugin", Loader.GROUP_PLUGIN);
        loader.builtin("@cordisjs/plugin-include", (ctx, config) -> {
            Include include = new Include(ctx, (Map<String, Object>) config);
            return include.apply(ctx, config);
        });

        EntryOptions include = new EntryOptions();
        include.id = "config";
        include.name = "@cordisjs/plugin-include";
        include.config = Map.of("path", "src/main/resources/app.yml");
        loader.read(List.of(include));

        // both feature entries (root-level and nested in the group) are active
        System.out.println("entries loaded: " + loader.entries().size() + " (feature-a + group + nested-feature)");
        root.fiber().disposeAsync().join();
    }

    private ConfigApp() {}
}
