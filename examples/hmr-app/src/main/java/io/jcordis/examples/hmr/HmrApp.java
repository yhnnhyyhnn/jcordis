package io.jcordis.examples.hmr;

import io.jcordis.core.context.Context;
import io.jcordis.loader.Loader;
import io.jcordis.loader.include.Hmr;
import io.jcordis.loader.include.JarWatcher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Dev-mode application demonstrating jcordis hot reload:
 *
 * <ul>
 *   <li><b>config hot reload</b> — {@link Hmr} polls {@code jcordis.yml} and
 *       diff-updates the loader tree on change (edit the file, the greeter
 *       plugin re-runs with the new greeting);</li>
 *   <li><b>plugin jar hot-swap</b> — {@link JarWatcher} watches the
 *       {@code plugins/} directory; dropping or replacing a plugin jar
 *       registers / atomically swaps the plugin.</li>
 * </ul>
 *
 * <p>Run from the module directory with {@code mvn compile exec:java} (or an
 * IDE main run), then edit {@code jcordis.yml} / {@code plugins/} and watch
 * the logs.
 */
public final class HmrApp {

    public static void main(String[] args) throws Exception {
        Path configPath = Path.of("jcordis.yml");
        Path pluginsDir = Path.of("plugins");

        // bootstrap: create an initial config file on first run
        if (!Files.exists(configPath)) {
            Files.writeString(
                    configPath,
                    """
                    - id: greeter
                      name: greeter-plugin
                      config:
                        greeting: hello
                    """);
        }

        Context root = Context.create();
        Loader loader = new Loader(root);
        loader.enableLogs = true;
        loader.builtin("greeter-plugin", new GreeterPlugin());

        Hmr hmr = new Hmr(root, loader, Map.of("path", configPath.toString()));
        hmr.start();
        JarWatcher watcher = new JarWatcher(loader, pluginsDir);
        watcher.start();

        System.out.println("[hmr-app] watching " + configPath.toAbsolutePath() + " (config) and "
                + pluginsDir.toAbsolutePath() + " (plugin jars)");
        System.out.println("[hmr-app] edit jcordis.yml or swap plugins/*.jar to see hot reload");
        Thread.currentThread().join();
    }

    private HmrApp() {}
}
