package io.jcordis.loader;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Translates Cordis loader index.spec.ts: basic support and intercept config. */
class LoaderBasicTest {

    private static EntryOptions entry(String id, String name) {
        EntryOptions options = new EntryOptions();
        options.id = id;
        options.name = name;
        return options;
    }

    private static EntryOptions entry(String id, String name, Object config) {
        EntryOptions options = entry(id, name);
        options.config = config;
        return options;
    }

    @Test
    void partialDispose_shouldEmitWithLegacyOptions() {
        Context root = Context.create();
        Loader loader = new Loader(root);
        java.util.List<Object[]> events = new java.util.ArrayList<>();
        root.on("loader/partial-dispose", (thisArg, args) -> {
            events.add(new Object[] {args[0], args[1], args[2]});
            return null;
        });
        loader.mock("foo", (ctx, config) -> null);

        EntryOptions options = entry("a", "foo");
        options.config = java.util.Map.of("v", 1);
        loader.create(options, null);
        assertThat(events).isEmpty();

        // config update on a live fiber emits the event with the legacy options
        EntryOptions updated = entry("a", "foo");
        updated.config = java.util.Map.of("v", 2);
        loader.update("a", updated, null);

        assertThat(events).hasSize(1);
        Entry entry = (Entry) events.get(0)[0];
        EntryOptions.Snapshot legacy = (EntryOptions.Snapshot) events.get(0)[1];
        assertThat(events.get(0)[2]).isEqualTo(true);
        assertThat(entry.options.config).isEqualTo(java.util.Map.of("v", 2));
        assertThat(legacy).isNotNull();
    }

    @Test
    void locate_shouldFindEntryIdOfFiber() {
        Context root = Context.create();
        Loader loader = new Loader(root);
        loader.mock("foo", (ctx, config) -> null);

        EntryOptions options = entry("a", "foo");
        loader.create(options, null);
        io.jcordis.core.fiber.Fiber fiber = loader.expectFiber("a");

        assertThat(loader.locate(fiber)).isEqualTo("a");
        assertThat(loader.locate(fiber.ctx().fiber())).isEqualTo("a");
        assertThat(loader.locate(root.fiber())).isNull();
    }

    @Test
    void groupPluginIdentity_shouldMarkEntryAsGroup() {
        Context root = Context.create();
        Loader loader = new Loader(root);
        AtomicInteger childCalls = new AtomicInteger();
        loader.mock("child", (ctx, config) -> {
            childCalls.incrementAndGet();
            return null;
        });

        // a group entry referencing the group plugin itself (no `group: true`)
        EntryOptions groupEntry = new EntryOptions();
        groupEntry.id = "outer";
        groupEntry.name = "@cordisjs/plugin-group";
        groupEntry.config = List.of(entry("c", "child"));
        loader.create(groupEntry, null);

        assertThat(childCalls).hasValue(1);
        assertThat(loader.entries()).hasSize(2);
    }

    @Test
    void globalRealm_shouldBeGarbageCollectedWhenUnreferenced() {
        Context root = Context.create();
        Loader loader = new Loader(root);
        loader.mock("a", (ctx, config) -> null);
        loader.mock("b", (ctx, config) -> null);

        EntryOptions a = entry("a", "a");
        a.isolate = Map.of("db", "shared");
        loader.create(a, null);
        EntryOptions b = entry("b", "b");
        b.isolate = Map.of("db", "shared");
        loader.create(b, null);
        assertThat(loader.realms.get("shared")).isNotNull();
        assertThat(loader.realms.get("shared").size()).isEqualTo(1);

        // a moves to a different label; b still references @shared → keep
        EntryOptions a2 = entry("a", "a");
        a2.isolate = Map.of("db", "other");
        loader.update("a", a2, null);
        assertThat(loader.realms.get("shared").size()).isEqualTo(1);

        // b leaves @shared too → no entry references it → realm garbage-collected
        EntryOptions b2 = entry("b", "b");
        b2.isolate = Map.of("db", "other");
        loader.update("b", b2, null);
        assertThat(loader.realms).doesNotContainKey("shared");
    }

    @Test
    void globalRealm_shouldKeepIsolatedNamesInUse() {
        Context root = Context.create();
        Loader loader = new Loader(root);
        loader.mock("a", (ctx, config) -> null);
        loader.mock("b", (ctx, config) -> null);

        EntryOptions a = entry("a", "a");
        a.isolate = Map.of("db", "shared", "cache", "shared");
        loader.create(a, null);
        EntryOptions b = entry("b", "b");
        b.isolate = Map.of("cache", "shared");
        loader.create(b, null);

        // a drops both isolated names; 'cache' stays because b references it
        EntryOptions a2 = entry("a", "a");
        a2.isolate = Map.of();
        loader.update("a", a2, null);
        assertThat(loader.realms.get("shared").size()).isEqualTo(1);

        // b drops 'cache' too → realm empty and removed
        EntryOptions b2 = entry("b", "b");
        b2.isolate = Map.of();
        loader.update("b", b2, null);
        assertThat(loader.realms).doesNotContainKey("shared");
    }

    @Test
    void loaderLogs_shouldConsumeInternalStatus() {
        Context root = Context.create();
        Loader loader = new Loader(root);
        loader.enableLogs = true;
        loader.mock("foo", (ctx, config) -> null);

        EntryOptions options = entry("a", "foo");
        String id = loader.create(options, null);
        // the initial ACTIVE transition fires before the fiber is linked to its
        // entry; dispose afterwards so the listener can attribute the transition
        loader.expectFiber(id).disposeAsync().join();

        // the loader consumed internal/status and logged the unload
        java.util.List<io.jcordis.core.logger.Message> buffer =
                root.loggerService().buffer();
        io.jcordis.core.logger.Message unload = buffer.stream()
                .filter(m -> "unload".equals(m.args()[1]))
                .findFirst()
                .orElseThrow();
        assertThat(unload.args()[0]).isEqualTo("%s plugin %C");
        assertThat(unload.args()[2]).isEqualTo("foo");
    }

    @Test
    void loaderAwait_shouldGateDependentsUntilTasksSettle() {
        Context root = Context.create().intercept("loader", java.util.Map.of("await", true));
        Loader loader = new Loader(root);
        AtomicInteger aCalls = new AtomicInteger();
        AtomicInteger bCalls = new AtomicInteger();
        CompletableFuture<Object> gate = new CompletableFuture<>();
        loader.mock("a", (ctx, config) -> {
            aCalls.incrementAndGet();
            return gate;
        });
        loader.mock("b", (ctx, config) -> {
            bCalls.incrementAndGet();
            return null;
        });

        EntryOptions a = entry("a", "a");
        EntryOptions b = entry("b", "b");
        b.inject = new java.util.HashMap<>();
        b.inject.put("loader", null);
        loader.read(List.of(a, b));

        // a is still loading → loader check fails → b must stay unloaded
        assertThat(aCalls).hasValue(1);
        assertThat(bCalls).as("b gated by await config").hasValue(0);

        gate.complete(null);
        loader.await();

        // once a's load settles, 'loader' is re-notified and b resolves
        assertThat(bCalls).as("b loads after tasks settle").hasValue(1);
    }

    @Test
    void loaderInitiate() {
        Context root = Context.create();
        Loader loader = new Loader(root);
        AtomicInteger fooCalls = new AtomicInteger();
        AtomicInteger barCalls = new AtomicInteger();
        AtomicInteger quxCalls = new AtomicInteger();

        Plugin foo = loader.mock("foo", (ctx, config) -> {
            fooCalls.incrementAndGet();
            return null;
        });
        Plugin bar = loader.mock("bar", (ctx, config) -> {
            barCalls.incrementAndGet();
            return null;
        });
        Plugin qux = loader.mock("qux", (ctx, config) -> {
            quxCalls.incrementAndGet();
            return null;
        });
        loader.mock("@cordisjs/plugin-group", (ctx, config) -> null);

        List<EntryOptions> config = new ArrayList<>();
        config.add(entry("1", "foo"));
        EntryOptions groupEntry = entry("2", "@cordisjs/plugin-group");
        groupEntry.group = true;
        groupEntry.config = List.of(entry("3", "bar", java.util.Map.of("a", 1)), entry("4", "qux"));
        ((List<EntryOptions>) groupEntry.config).forEach(o -> {
            if (o.id.equals("4")) o.disabled = true;
        });
        config.add(groupEntry);
        loader.read(config);

        assertThat(root.registry().has(foo)).isTrue();
        assertThat(root.registry().has(bar)).isTrue();
        assertThat(root.registry().has(qux)).isFalse();
        assertThat(fooCalls).hasValue(1);
        assertThat(barCalls).hasValue(1);
        assertThat(quxCalls).hasValue(0);
    }

    @Test
    void loaderUpdate() {
        Context root = Context.create();
        Loader loader = new Loader(root);
        AtomicInteger fooCalls = new AtomicInteger();
        AtomicInteger barCalls = new AtomicInteger();
        AtomicInteger quxCalls = new AtomicInteger();

        Plugin foo = loader.mock("foo", (ctx, config) -> {
            fooCalls.incrementAndGet();
            return null;
        });
        Plugin bar = loader.mock("bar", (ctx, config) -> {
            barCalls.incrementAndGet();
            return null;
        });
        Plugin qux = loader.mock("qux", (ctx, config) -> {
            quxCalls.incrementAndGet();
            return null;
        });
        loader.mock("@cordisjs/plugin-group", (ctx, config) -> null);

        List<EntryOptions> first = new ArrayList<>();
        first.add(entry("1", "foo"));
        EntryOptions groupEntry = entry("2", "@cordisjs/plugin-group");
        groupEntry.group = true;
        groupEntry.config = List.of(entry("3", "bar", java.util.Map.of("a", 1)), entry("4", "qux"));
        ((List<EntryOptions>) groupEntry.config).forEach(o -> {
            if (o.id.equals("4")) o.disabled = true;
        });
        first.add(groupEntry);
        loader.read(first);

        fooCalls.set(0);
        barCalls.set(0);
        quxCalls.set(0);

        List<EntryOptions> second = new ArrayList<>();
        second.add(entry("1", "foo"));
        second.add(entry("4", "qux"));
        loader.read(second);

        assertThat(root.registry().has(foo)).isTrue();
        assertThat(root.registry().has(bar)).isFalse();
        assertThat(root.registry().has(qux)).isTrue();
        assertThat(fooCalls).hasValue(0);
        assertThat(barCalls).hasValue(0);
        assertThat(quxCalls).hasValue(1);
    }

    @Test
    void pluginSelfUpdate() {
        Context root = Context.create();
        Loader loader = new Loader(root);
        loader.mock("foo", (ctx, config) -> null);
        loader.read(List.of(entry("1", "foo", java.util.Map.of("a", 1))));

        Entry entry = loader.resolve("1");
        entry.fiber.update(java.util.Map.of("a", 3), false);

        assertThat(entry.options.config).isEqualTo(java.util.Map.of("a", 3));
    }

    @Test
    void pluginSelfDispose() {
        Context root = Context.create();
        Loader loader = new Loader(root);
        loader.mock("foo", (ctx, config) -> null);
        loader.read(List.of(entry("1", "foo", java.util.Map.of("a", 3))));

        Entry entry = loader.resolve("1");
        entry.fiber.disposeAsync().join();

        assertThat(entry.options.disabled).isTrue();
    }

    @Test
    void interceptConfig() {
        Context root = Context.create();
        Loader loader = new Loader(root);
        CompletableFuture<Object> gate = new CompletableFuture<>();
        loader.mock("foo", (ctx, config) -> {
            gate.complete(null);
            return null;
        });
        loader.mock("bar", (ctx, config) -> null);
        loader.mock("qux", (ctx, config) -> null);

        String foo = loader.create(entry("foo", "foo"), null);
        String bar = loader.create(entry("bar", "bar"), null);
        EntryOptions quxOptions = entry("qux", "qux");
        quxOptions.inject = java.util.Map.of("loader", true);
        quxOptions.intercept = java.util.Map.of("loader", java.util.Map.of("await", true));
        String qux = loader.create(quxOptions, null);

        assertThat(loader.expectFiber(foo)).isNotNull();
        assertThat(loader.expectFiber(bar)).isNotNull();
        assertThat(loader.expectFiber(qux)).isNotNull();
    }
}
