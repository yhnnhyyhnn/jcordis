package io.jcordis.loader;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Stress: HMR-style tree rewrites racing application-style create/remove. */
class TreeConcurrencyTest {

    private static EntryOptions entry(String id) {
        EntryOptions options = new EntryOptions();
        options.id = id;
        options.name = "plugin-" + id;
        return options;
    }

    @Test
    void concurrentReadAndRewrite_shouldNotCorruptTree() {
        Context root = Context.create();
        Loader loader = new Loader(root);
        loader.mock("plugin-a", (ctx, config) -> null);
        loader.mock("plugin-b", (ctx, config) -> null);
        loader.mock("plugin-c", (ctx, config) -> null);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        // HMR-style writer: repeatedly rewrites the whole tree
        CompletableFuture<Void> writer = CompletableFuture.runAsync(() -> {
            try {
                for (int i = 0; i < 200; i++) {
                    List<EntryOptions> config =
                            i % 2 == 0 ? List.of(entry("a"), entry("b")) : List.of(entry("b"), entry("c"));
                    loader.root.update(config);
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        });

        // application-style readers/writers: create/remove/entries concurrently
        CompletableFuture<Void> reader = CompletableFuture.runAsync(() -> {
            try {
                for (int i = 0; i < 200; i++) {
                    loader.entries();
                    String id = loader.create(entry("x" + (i % 5)), null);
                    try {
                        loader.remove(id);
                    } catch (IllegalArgumentException e) {
                        // logical race: the writer's rewrite removed it first
                    }
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        CompletableFuture.allOf(writer, reader).join();

        // any data-corruption exception (CME / IndexOutOfBounds / AIOOBE)
        // fails the test; logical not-found races are expected
        assertThat(failure).hasValue(null);
    }
}
