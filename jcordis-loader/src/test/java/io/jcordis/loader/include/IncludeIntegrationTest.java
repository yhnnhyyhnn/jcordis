package io.jcordis.loader.include;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import io.jcordis.core.fiber.Fiber;
import io.jcordis.loader.EntryOptions;
import io.jcordis.loader.Loader;
import io.jcordis.core.logger.ConsoleExporter;
import io.jcordis.core.timer.TimerService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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
        Files.writeString(config, """
                - id: timer
                  name: timer-plugin
                - id: logger
                  name: logger-plugin
                """, StandardCharsets.UTF_8);

        Context root = Context.create();
        Loader loader = new Loader(root);
        AtomicInteger timerReady = new AtomicInteger();
        loader.mock("@cordisjs/plugin-include", (ctx, cfg) -> { Include include = new Include(ctx, (Map<String, Object>) cfg); return include.apply(ctx, cfg); });
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

    private static EntryOptions entry(String id, String name, Object config) {
        EntryOptions options = new EntryOptions();
        options.id = id;
        options.name = name;
        options.config = config;
        return options;
    }
}