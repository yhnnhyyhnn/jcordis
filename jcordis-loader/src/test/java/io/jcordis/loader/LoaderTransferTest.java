package io.jcordis.loader;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Translates Cordis group.spec.ts 'Group: transfer': moving an entry between
 * groups re-parents it (its {@code id()} gains/loses the group prefix), the
 * disabled-ancestor chain applies, and options are carried along.
 */
class LoaderTransferTest {

    private static EntryOptions entry(String id, String name) {
        EntryOptions options = new EntryOptions();
        options.id = id;
        options.name = name;
        return options;
    }

    private static EntryOptions group(String id, List<EntryOptions> config) {
        EntryOptions options = new EntryOptions();
        options.id = id;
        options.name = "@cordisjs/plugin-group";
        options.group = true;
        options.config = config;
        return options;
    }

    @Test
    void transferIntoGroup_shouldReparentAndReload() {
        Context root = Context.create();
        Loader loader = new Loader(root);
        AtomicInteger calls = new AtomicInteger();
        loader.mock("foo", (ctx, config) -> {
            calls.incrementAndGet();
            return null;
        });
        loader.create(group("outer", List.of()), null);
        loader.create(entry("foo", "foo"), null);
        assertThat(calls).hasValue(1);

        loader.transfer("foo", "outer");

        // the entry is re-parented; its id now carries the group prefix
        Entry moved = loader.store.get("foo");
        assertThat(moved.parent).isSameAs(loader.resolveGroup("outer"));
        assertThat(moved.id()).isEqualTo("outer:foo");
        assertThat(loader.locate(moved.fiber)).isEqualTo("outer:foo");
        // jcordis restarts the body on transfer (force reload)
        assertThat(calls).hasValue(2);
    }

    @Test
    void transferIntoDisabledGroup_shouldDisposeViaAncestorChain() {
        Context root = Context.create();
        Loader loader = new Loader(root);
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger disposes = new AtomicInteger();
        loader.mock("foo", (ctx, config) -> {
            calls.incrementAndGet();
            return (io.jcordis.core.util.Disposable) disposes::incrementAndGet;
        });
        loader.create(entry("foo", "foo"), null);
        EntryOptions disabledGroup = group("g2", List.of());
        disabledGroup.disabled = true;
        loader.create(disabledGroup, null);
        assertThat(disposes).hasValue(0);

        // moving under a disabled group disposes the entry's fiber
        loader.transfer("foo", "g2");
        assertThat(disposes).hasValue(1);
        assertThat(loader.store.get("foo").fiber).isNull();

        // moving it back to the root re-enables it
        loader.transfer("foo", null);
        assertThat(calls).hasValue(2);
        assertThat(loader.store.get("foo").id()).isEqualTo("foo");
    }

    @Test
    void transfer_shouldCarryConfigAndIsolate() {
        Context root = Context.create();
        Loader loader = new Loader(root);
        AtomicReference<Object> seen = new AtomicReference<>();
        loader.mock("svc", (ctx, config) -> {
            seen.set(config);
            return null;
        });
        loader.create(group("outer", List.of()), null);

        EntryOptions svc = entry("svc", "svc");
        svc.config = Map.of("v", 1);
        svc.isolate = Map.of("db", "shared");
        loader.create(svc, null);
        assertThat(seen.get()).isEqualTo(Map.of("v", 1));

        // moving the entry keeps its config and isolate options
        loader.transfer("svc", "outer");
        assertThat(seen.get()).isEqualTo(Map.of("v", 1));
        assertThat(loader.store.get("svc").options.isolate).isEqualTo(Map.of("db", "shared"));
        assertThat(loader.store.get("svc").options.config).isEqualTo(Map.of("v", 1));
    }
}
