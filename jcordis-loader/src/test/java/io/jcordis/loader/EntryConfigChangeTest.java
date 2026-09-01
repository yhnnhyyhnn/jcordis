package io.jcordis.loader;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Regression: a config-value change pushed through {@code loader.read()}
 * (config-file / HMR path) must restart the plugin body. The previous
 * comparison ran after {@code copyInto} had already assigned the new config to
 * both sides, so a changed config never triggered {@code fiber.update()}.
 */
class EntryConfigChangeTest {

    @Test
    void configChangeShouldRestartPlugin() {
        AtomicInteger calls = new AtomicInteger();
        Context root = Context.create();
        Loader loader = new Loader(root);
        loader.builtin("demo", (ctx, config) -> {
            calls.incrementAndGet();
            return null;
        });

        EntryOptions o1 = new EntryOptions();
        o1.id = "a";
        o1.name = "demo";
        o1.config = Map.of("v", 1);
        loader.read(java.util.List.of(o1));
        assertThat(calls.get()).as("initial load").isEqualTo(1);

        EntryOptions o2 = new EntryOptions();
        o2.id = "a";
        o2.name = "demo";
        o2.config = Map.of("v", 2);
        loader.read(java.util.List.of(o2));
        assertThat(calls.get()).as("restart on config change").isEqualTo(2);
    }

    @Test
    void unchangedConfigShouldNotRestartPlugin() {
        AtomicInteger calls = new AtomicInteger();
        Context root = Context.create();
        Loader loader = new Loader(root);
        loader.builtin("demo", (ctx, config) -> {
            calls.incrementAndGet();
            return null;
        });

        EntryOptions o1 = new EntryOptions();
        o1.id = "a";
        o1.name = "demo";
        o1.config = Map.of("v", 1);
        loader.read(java.util.List.of(o1));
        assertThat(calls.get()).isEqualTo(1);

        // same content, different instance: must not reload
        EntryOptions o2 = new EntryOptions();
        o2.id = "a";
        o2.name = "demo";
        o2.config = Map.of("v", 1);
        loader.read(java.util.List.of(o2));
        assertThat(calls.get()).as("no restart when config unchanged").isEqualTo(1);
    }
}
