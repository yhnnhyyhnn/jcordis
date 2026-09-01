package io.jcordis.loader;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import io.jcordis.core.service.Service;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Translates Cordis loader isolate.spec.ts core semantics: provider/injector isolation. */
class LoaderIsolateTest {

    private static EntryOptions entry(String name) {
        EntryOptions options = new EntryOptions();
        options.name = name;
        return options;
    }

    /** Builds an inject map (values may be null, unlike {@code Map.of}). */
    private static Map<String, Object> inject(String... names) {
        Map<String, Object> map = new java.util.HashMap<>();
        for (String name : names) {
            map.put(name, null);
        }
        return map;
    }

    @Test
    void providerAndInjectorIsolation() {
        Context root = Context.create();
        Loader loader = new Loader(root);
        AtomicInteger fooCalls = new AtomicInteger();
        AtomicInteger disposes = new AtomicInteger();
        Plugin foo = loader.mock("foo", (ctx, config) -> {
            fooCalls.incrementAndGet();
            return (io.jcordis.core.util.Disposable) disposes::incrementAndGet;
        });
        loader.mock("bar", (ctx, config) -> {
            ctx.provide("bar", config);
            return null;
        });

        // bar provides 'bar' at root level
        loader.create(entry("bar"), null);
        // foo injects 'bar'
        EntryOptions fooOptions = entry("foo");
        fooOptions.inject = inject("bar");
        loader.create(fooOptions, null);
        assertThat(fooCalls).hasValue(1);
        assertThat(disposes).hasValue(0);
        assertThat(root.registry().size()).isEqualTo(2);
    }

    @Test
    void providerFiberServesInjector() {
        Context root = Context.create();
        Loader loader = new Loader(root);
        AtomicInteger fooCalls = new AtomicInteger();
        Plugin foo = loader.mock("foo", (ctx, config) -> {
            fooCalls.incrementAndGet();
            return null;
        });
        loader.mock("bar", (ctx, config) -> {
            ctx.provide("bar", config);
            return null;
        });

        // provider bar loaded first
        String barId = loader.create(entry("bar"), null);
        // injector foo with inject ['bar']
        EntryOptions fooOptions = entry("foo");
        fooOptions.inject = inject("bar");
        String fooId = loader.create(fooOptions, null);

        assertThat(loader.expectFiber(barId)).isNotNull();
        assertThat(loader.expectFiber(fooId)).isNotNull();
        assertThat(fooCalls).hasValue(1);
    }

    @Test
    void serviceConstructorProvides() {
        Context root = Context.create();
        Loader loader = new Loader(root);
        AtomicInteger fooCalls = new AtomicInteger();
        loader.mock("foo", (ctx, config) -> {
            fooCalls.incrementAndGet();
            return null;
        });
        loader.mock("bar", (ctx, config) -> {
            new BarService(ctx);
            return null;
        });

        loader.create(entry("bar"), null);
        EntryOptions fooOptions = entry("foo");
        fooOptions.inject = inject("bar");
        loader.create(fooOptions, null);
        assertThat(fooCalls).hasValue(1);
        assertThat(root.<BarService>get("bar")).isNotNull();
    }

    /** Service that registers itself on construction. */
    static class BarService extends Service {
        BarService(Context ctx) {
            super(ctx, "bar");
        }
    }
}
