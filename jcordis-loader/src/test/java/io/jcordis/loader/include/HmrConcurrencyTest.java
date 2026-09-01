package io.jcordis.loader.include;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import io.jcordis.loader.EntryOptions;
import io.jcordis.loader.Loader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Stress: the Hmr polling thread rewrites the tree while an app thread mutates it. */
class HmrConcurrencyTest {

    @TempDir
    Path tempDir;

    private static EntryOptions entry(String id) {
        EntryOptions options = new EntryOptions();
        options.id = id;
        options.name = "plugin-" + id;
        return options;
    }

    @Test
    void pollingThread_shouldNotCorruptTreeUnderAppTraffic() throws Exception {
        Path config = tempDir.resolve("app.yml");
        Files.writeString(config, "- id: a\n  name: plugin-a\n", StandardCharsets.UTF_8);
        Context root = Context.create();
        Loader loader = new Loader(root);
        loader.mock("plugin-a", (ctx, cfg) -> null);
        loader.mock("plugin-b", (ctx, cfg) -> null);
        Hmr hmr = new Hmr(root, loader, Map.of("path", config.toString(), "interval", 20));
        hmr.start();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        CompletableFuture<Void> app = CompletableFuture.runAsync(() -> {
            try {
                for (int i = 0; i < 300; i++) {
                    loader.entries();
                    String id = loader.create(entry("x" + (i % 3)), null);
                    try {
                        loader.remove(id);
                    } catch (IllegalArgumentException e) {
                        // logical race: the polling rewrite removed it first
                    }
                    // the "user" edits the config file while the app mutates the tree
                    if (i % 50 == 0) {
                        Files.writeString(
                                config,
                                i % 100 == 0 ? "- id: a\n  name: plugin-a\n" : "- id: b\n  name: plugin-b\n",
                                StandardCharsets.UTF_8);
                    }
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        app.join();
        hmr.stop();

        // any data-corruption exception (CME / AIOOBE / NPE / ConcurrentModification)
        // fails the test; logical not-found races are expected
        assertThat(failure).hasValue(null);
    }
}
