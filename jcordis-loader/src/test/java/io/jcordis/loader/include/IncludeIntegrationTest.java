package io.jcordis.loader.include;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import io.jcordis.core.fiber.Fiber;
import io.jcordis.core.logger.ConsoleExporter;
import io.jcordis.core.timer.TimerService;
import io.jcordis.loader.EntryOptions;
import io.jcordis.loader.Loader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Integration: include config file drives the loader, loading timer/logger plugins. */
class IncludeIntegrationTest {

    @TempDir
    Path tempDir;

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void configFileDrivesLoaderWithTimerAndLogger() throws IOException {
        Path config = tempDir.resolve("app.yml");
        Files.writeString(
                config,
                """
                - id: timer
                  name: timer-plugin
                - id: logger
                  name: logger-plugin
                """,
                StandardCharsets.UTF_8);

        Context root = Context.create();
        Loader loader = new Loader(root);
        AtomicInteger timerReady = new AtomicInteger();
        loader.mock("@cordisjs/plugin-include", (ctx, cfg) -> {
            Include include = new Include(ctx, (Map<String, Object>) cfg);
            return include.apply(ctx, cfg);
        });
        loader.mock("timer-plugin", (ctx, cfg) -> {
            new TimerService(ctx);
            timerReady.incrementAndGet();
            return null;
        });
        loader.mock("logger-plugin", (ctx, cfg) -> {
            new ConsoleExporter(ctx);
            return null;
        });

        loader.read(List.of(entry("inc", "@cordisjs/plugin-include", Map.of("path", config.toString()))));

        assertThat(timerReady).hasValue(1);
        assertThat(root.<Object>get("timer")).isInstanceOf(TimerService.class);

        // timer actually fires within the loaded context
        TimerService timer = (TimerService) root.get("timer");
        AtomicInteger ticks = new AtomicInteger();
        timer.timeout(ticks::incrementAndGet, 30);
        sleep(100);
        assertThat(ticks).hasValue(1);

        // fiber teardown cancels timers
        Fiber fiber = loader.expectFiber("timer");
        assertThat(fiber).isNotNull();
        fiber.disposeAsync().join();
        sleep(100);
        assertThat(ticks).hasValue(1);
    }

    @Test
    void includeWithHmr_shouldReloadOnConfigChange() throws IOException, InterruptedException {
        Path config = tempDir.resolve("app.yml");
        Files.writeString(
                config, "- id: feature\n  name: feature-plugin\n  config:\n    n: 1\n", StandardCharsets.UTF_8);

        Context root = Context.create();
        Loader loader = new Loader(root);
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger seen = new AtomicInteger();
        loader.mock("@cordisjs/plugin-include", (ctx, cfg) -> {
            Include include = new Include(ctx, (Map<String, Object>) cfg);
            return include.apply(ctx, cfg);
        });
        loader.mock("feature-plugin", (ctx, cfg) -> {
            calls.incrementAndGet();
            if (cfg instanceof Map<?, ?> map && map.get("n") instanceof Integer n) {
                seen.set(n);
            }
            return null;
        });

        // generic hmr service, no convenience `path`: the include owns its file
        Hmr hmr = new Hmr(root, loader, Map.of("interval", 30));
        hmr.start();
        try {
            loader.read(List.of(entry("inc", "@cordisjs/plugin-include", Map.of("path", config.toString()))));
            waitFor(() -> calls.get() == 1 && seen.get() == 1);
            assertThat(hmr.isWatching(config))
                    .as("include registered its own watch")
                    .isTrue();

            // editing the file re-applies it through the registered watch
            Files.writeString(
                    config, "- id: feature\n  name: feature-plugin\n  config:\n    n: 2\n", StandardCharsets.UTF_8);
            waitFor(() -> calls.get() == 2 && seen.get() == 2);
        } finally {
            hmr.stop();
        }
    }

    @Test
    void includeWithConvenienceHmr_shouldBothWorkIndependently() throws IOException, InterruptedException {
        // real-world layout: hmr watches the loader config, the include entry
        // inside it points at the include's *own* file (which the include owns)
        Path includeConfig = tempDir.resolve("include.yml");
        Files.writeString(
                includeConfig, "- id: feature\n  name: feature-plugin\n  config:\n    n: 1\n", StandardCharsets.UTF_8);
        Path loaderConfig = tempDir.resolve("jcordis.yml");
        Files.writeString(
                loaderConfig,
                "- id: inc\n  name: '@cordisjs/plugin-include'\n  config:\n    path: '" + includeConfig + "'\n",
                StandardCharsets.UTF_8);

        Context root = Context.create();
        Loader loader = new Loader(root);
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger seen = new AtomicInteger();
        loader.mock("@cordisjs/plugin-include", (ctx, cfg) -> {
            Include include = new Include(ctx, (Map<String, Object>) cfg);
            return include.apply(ctx, cfg);
        });
        loader.mock("feature-plugin", (ctx, cfg) -> {
            calls.incrementAndGet();
            if (cfg instanceof Map<?, ?> map && map.get("n") instanceof Integer n) {
                seen.set(n);
            }
            return null;
        });

        Hmr hmr = new Hmr(root, loader, Map.of("path", loaderConfig.toString(), "interval", 30));
        hmr.start();
        try {
            waitFor(() -> calls.get() == 1 && seen.get() == 1);
            assertThat(hmr.isWatching(loaderConfig))
                    .as("hmr watches the loader config")
                    .isTrue();
            assertThat(hmr.isWatching(includeConfig))
                    .as("include registered its own file")
                    .isTrue();

            // editing the include's own file reloads through its watch
            Files.writeString(
                    includeConfig,
                    "- id: feature\n  name: feature-plugin\n  config:\n    n: 2\n",
                    StandardCharsets.UTF_8);
            waitFor(() -> calls.get() == 2 && seen.get() == 2);
        } finally {
            hmr.stop();
        }
    }

    private static void waitFor(Supplier<Boolean> condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.get()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within 5s");
            }
            Thread.sleep(20);
        }
    }

    private static EntryOptions entry(String id, String name, Object config) {
        EntryOptions options = new EntryOptions();
        options.id = id;
        options.name = name;
        options.config = config;
        return options;
    }
}
