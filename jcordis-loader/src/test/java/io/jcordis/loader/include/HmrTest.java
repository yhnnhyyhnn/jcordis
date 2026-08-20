package io.jcordis.loader.include;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import io.jcordis.loader.EntryOptions;
import io.jcordis.loader.Loader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Translates Cordis hmr.spec.ts config-file core: reload, disable, rollback. */
class HmrTest {

    @TempDir
    Path tempDir;

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void waitFor(AtomicInteger value, int expected, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (value.get() == expected) return;
            sleep(50);
        }
    }

    private String config() {
        return """
                - id: sample
                  name: sample-plugin
                  config:
                    value: initial
                """;
    }

    private String disabledConfig() {
        return """
                - id: sample
                  name: sample-plugin
                  disabled: true
                  config:
                    value: initial
                """;
    }

    @Test
    void reloadsPluginOnConfigChange() throws IOException {
        Path configPath = tempDir.resolve("app.yml");
        Files.writeString(configPath, config(), StandardCharsets.UTF_8);

        Context root = Context.create();
        Loader loader = new Loader(root);
        AtomicInteger calls = new AtomicInteger();
        loader.mock("sample-plugin", (ctx, cfg) -> {
            calls.incrementAndGet();
            return null;
        });
        Hmr hmr = new Hmr(root, loader, Map.of("path", configPath.toString(), "interval", 50));
        hmr.start();
        sleep(150);
        assertThat(calls).hasValue(1);

        // disable the plugin via config: HMR re-parses and diff-updates the tree
        Files.writeString(configPath, disabledConfig(), StandardCharsets.UTF_8);
        waitFor(calls, 1, 2000);
        sleep(300);
        // tree diff disposes the plugin fiber; a subsequent read should not re-run it
        assertThat(loader.entries()).hasSize(1);
        assertThat(loader.store.get("sample").disabled()).isTrue();
        hmr.stop();
    }

    @Test
    void reenablesPluginOnConfigRestore() throws IOException {
        Path configPath = tempDir.resolve("app.yml");
        Files.writeString(configPath, disabledConfig(), StandardCharsets.UTF_8);

        Context root = Context.create();
        Loader loader = new Loader(root);
        AtomicInteger calls = new AtomicInteger();
        loader.mock("sample-plugin", (ctx, cfg) -> {
            calls.incrementAndGet();
            return null;
        });
        Hmr hmr = new Hmr(root, loader, Map.of("path", configPath.toString(), "interval", 50));
        hmr.start();
        sleep(150);
        assertThat(calls).hasValue(0);

        Files.writeString(configPath, config(), StandardCharsets.UTF_8);
        waitFor(calls, 1, 2000);
        assertThat(calls).hasValue(1);
        hmr.stop();
    }

    @Test
    void keepsTreeOnParseFailure() throws IOException {
        Path configPath = tempDir.resolve("app.yml");
        Files.writeString(configPath, config(), StandardCharsets.UTF_8);

        Context root = Context.create();
        Loader loader = new Loader(root);
        AtomicInteger calls = new AtomicInteger();
        loader.mock("sample-plugin", (ctx, cfg) -> {
            calls.incrementAndGet();
            return null;
        });
        Hmr hmr = new Hmr(root, loader, Map.of("path", configPath.toString(), "interval", 50));
        hmr.start();
        sleep(150);
        assertThat(calls).hasValue(1);

        // broken YAML: tree must be preserved
        Files.writeString(configPath, "- id: broken\n  name: [unclosed", StandardCharsets.UTF_8);
        sleep(300);
        assertThat(calls).hasValue(1);
        assertThat(loader.store.get("sample")).isNotNull();
        hmr.stop();
    }

    @Test
    void emitsReloadEvent() throws IOException {
        Path configPath = tempDir.resolve("app.yml");
        Files.writeString(configPath, config(), StandardCharsets.UTF_8);

        Context root = Context.create();
        Loader loader = new Loader(root);
        loader.mock("sample-plugin", (ctx, cfg) -> null);
        Hmr hmr = new Hmr(root, loader, Map.of("path", configPath.toString(), "interval", 50));
        AtomicInteger reloads = new AtomicInteger();
        hmr.onReload(args -> reloads.incrementAndGet());
        hmr.start();
        sleep(150);

        Files.writeString(configPath, disabledConfig(), StandardCharsets.UTF_8);
        waitFor(reloads, 1, 2000);
        assertThat(reloads).hasValue(1);
        hmr.stop();
    }

    @Test
    void stopsPollingOnDispose() throws IOException {
        Path configPath = tempDir.resolve("app.yml");
        Files.writeString(configPath, config(), StandardCharsets.UTF_8);

        Context root = Context.create();
        Loader loader = new Loader(root);
        loader.mock("sample-plugin", (ctx, cfg) -> null);
        Hmr hmr = new Hmr(root, loader, Map.of("path", configPath.toString(), "interval", 30));
        io.jcordis.core.util.Disposable dispose = hmr.start();
        sleep(100);
        dispose.dispose();

        // change the config after dispose: no further reload may occur
        Files.writeString(configPath, disabledConfig(), StandardCharsets.UTF_8);
        sleep(200);
        boolean afterDispose = Boolean.TRUE.equals(loader.store.get("sample").options.disabled);

        // restore and change again: polling must stay stopped
        Files.writeString(configPath, config(), StandardCharsets.UTF_8);
        sleep(200);
        assertThat(Boolean.TRUE.equals(loader.store.get("sample").options.disabled)).isEqualTo(afterDispose);
    }
}