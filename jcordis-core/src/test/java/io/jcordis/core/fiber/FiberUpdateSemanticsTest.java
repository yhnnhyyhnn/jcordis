package io.jcordis.core.fiber;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import io.jcordis.core.event.EventOptions;
import io.jcordis.core.util.Disposable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Translates the upstream fiber update semantics (Cordis #189, #134 and #56):
 * reload revisions keep a superseded asynchronous load from failing or
 * activating the newer one; config is resolved through {@code internal/config}
 * on every reload while {@code internal/commit} carries persistence; and
 * fiber-local {@code internal/update} hooks are disposed with the fiber so
 * they do not accumulate across reloads.
 */
class FiberUpdateSemanticsTest {

    @Test
    void supersededFailureShouldNotFailTheLatestLoad() {
        Context root = Context.create();
        CompletableFuture<Object> gate = new CompletableFuture<>();
        List<Integer> applied = new ArrayList<>();
        Fiber fiber = root.plugin(
                (c, config) -> {
                    applied.add((Integer) config);
                    return (Integer) config == 1 ? gate : null;
                },
                0);
        assertThat(fiber.state()).isEqualTo(FiberState.ACTIVE);

        fiber.update(1);
        assertThat(fiber.state()).isEqualTo(FiberState.LOADING);

        // a second update supersedes the hanging load and settles
        fiber.update(2);
        assertThat(fiber.state()).isEqualTo(FiberState.ACTIVE);

        // the superseded body failing must only be reported, not fail the fiber
        gate.completeExceptionally(new IllegalStateException("superseded load"));
        assertThat(fiber.state()).isEqualTo(FiberState.ACTIVE);
        assertThat(fiber.await().isCompletedExceptionally()).isFalse();
        assertThat(applied).containsExactly(0, 1, 2);
    }

    @Test
    void supersededDisposableShouldBeDisposedImmediately() {
        Context root = Context.create();
        CompletableFuture<Object> gate = new CompletableFuture<>();
        AtomicInteger disposed = new AtomicInteger();
        Fiber fiber = root.plugin((c, config) -> "one".equals(config) ? gate : null, "zero");

        fiber.update("one");
        fiber.update("two");
        assertThat(fiber.state()).isEqualTo(FiberState.ACTIVE);

        // the late-completing superseded body must not leak its disposable
        gate.complete((Disposable) disposed::incrementAndGet);
        assertThat(disposed).hasValue(1);
        assertThat(fiber.effectCount()).isZero();
    }

    @Test
    void supersededLoadShouldNotClearTheNewerInertia() throws Exception {
        Context root = Context.create();
        CompletableFuture<Object> hanging = new CompletableFuture<>();
        CompletableFuture<Object> finishing = new CompletableFuture<>();
        Fiber fiber = root.plugin((c, config) -> "hang".equals(config) ? hanging : finishing, "hang");

        fiber.update("hang");
        fiber.update("finish");
        finishing.complete(null);
        // the newer task settles even though the superseded one never does
        fiber.await().get();
        assertThat(fiber.state()).isEqualTo(FiberState.ACTIVE);
        hanging.complete(null);
        assertThat(fiber.state()).isEqualTo(FiberState.ACTIVE);
    }

    @Test
    void updateToUnchangedConfigShouldStillReload() {
        Context root = Context.create();
        List<Object> applied = new ArrayList<>();
        Object config = Map.of("v", 1);
        Fiber fiber = root.plugin(
                (c, cfg) -> {
                    applied.add(cfg);
                    return null;
                },
                config);

        fiber.update(config);
        assertThat(applied).hasSize(2);

        fiber.restart().join();
        assertThat(applied).hasSize(3);
    }

    @Test
    void updateShouldEmitInternalCommitUnlessNoSave() {
        Context root = Context.create();
        List<Object[]> commits = new ArrayList<>();
        root.on(
                "internal/commit",
                (thisArg, args) -> {
                    commits.add(new Object[] {thisArg, args.length > 0 ? args[0] : null});
                    return null;
                },
                EventOptions.of(false, true));
        Fiber fiber = root.plugin((c, cfg) -> null, Map.of("v", 1));

        fiber.update(Map.of("v", 2), true);
        assertThat(commits).isEmpty();

        fiber.update(Map.of("v", 3), false);
        assertThat(commits).hasSize(1);
        assertThat(commits.get(0)[0]).isSameAs(fiber);
        assertThat(commits.get(0)[1]).isEqualTo(Map.of("v", 3));
        // the fiber keeps the source config, not a resolved one
        assertThat(fiber.config()).isEqualTo(Map.of("v", 3));
    }

    @Test
    void unloadedFiberUpdateShouldSkipHooksButStillCommit() {
        Context root = Context.create();
        AtomicInteger updateHooks = new AtomicInteger();
        AtomicInteger commits = new AtomicInteger();
        root.on(
                "internal/update",
                (thisArg, args) -> {
                    updateHooks.incrementAndGet();
                    Supplier<Object> next = (Supplier<Object>) args[1];
                    return next.get();
                },
                EventOptions.of(false, true));
        root.on(
                "internal/commit",
                (thisArg, args) -> {
                    commits.incrementAndGet();
                    return null;
                },
                EventOptions.of(false, true));

        List<Object> applied = new ArrayList<>();
        Fiber fiber = root.registry()
                .plugin(
                        root,
                        io.jcordis.core.registry.Plugin.object(null, Map.of("missing", Map.of()), (c, cfg) -> {
                            applied.add(cfg);
                            return null;
                        }),
                        0);
        assertThat(fiber.state()).isEqualTo(FiberState.PENDING);

        fiber.update(1);
        assertThat(fiber.state()).as("dependency still missing").isEqualTo(FiberState.PENDING);
        assertThat(updateHooks).as("update hooks assume a loaded body").hasValue(0);
        assertThat(commits).as("the config still reaches persistence").hasValue(1);
        assertThat(applied).isEmpty();
    }

    @Test
    void configShouldBeResolvedOnEveryReload() {
        Context root = Context.create();
        root.on(
                "internal/config",
                (thisArg, args) -> {
                    @SuppressWarnings("unchecked")
                    Supplier<Object> next = (Supplier<Object>) args[0];
                    @SuppressWarnings("unchecked")
                    Map<String, Object> source = (Map<String, Object>) next.get();
                    Map<String, Object> resolved = new java.util.HashMap<>(source);
                    resolved.put("resolved", true);
                    return resolved;
                },
                EventOptions.of(false, true));
        List<Object> seen = new ArrayList<>();
        Fiber fiber = root.plugin(
                (c, cfg) -> {
                    seen.add(cfg);
                    return null;
                },
                Map.of("v", 1));

        assertThat(seen).containsExactly(Map.of("v", 1, "resolved", true));
        assertThat(fiber.config()).as("the fiber keeps the source config").isEqualTo(Map.of("v", 1));

        fiber.update(Map.of("v", 2));
        assertThat(seen).hasSize(2);
        assertThat(seen.get(1)).isEqualTo(Map.of("v", 2, "resolved", true));
        assertThat(fiber.config()).isEqualTo(Map.of("v", 2));
    }

    @Test
    void updateHooksShouldReceiveTheResolvedConfig() {
        Context root = Context.create();
        root.on(
                "internal/config",
                (thisArg, args) -> {
                    @SuppressWarnings("unchecked")
                    Supplier<Object> next = (Supplier<Object>) args[0];
                    return next.get() + "!";
                },
                EventOptions.of(false, true));
        List<Object> hooked = new ArrayList<>();
        root.on(
                "internal/update",
                (thisArg, args) -> {
                    hooked.add(args[0]);
                    Supplier<Object> next = (Supplier<Object>) args[1];
                    return next.get();
                },
                EventOptions.of(false, true));
        Fiber fiber = root.plugin((c, cfg) -> null, "src");

        fiber.update("cfg");
        assertThat(hooked).containsExactly("cfg!");
    }

    @Test
    void fiberLocalUpdateHooksShouldNotAccumulateAcrossReloads() {
        Context root = Context.create();
        List<Integer> calls = new ArrayList<>();
        Fiber fiber = root.plugin(
                (c, cfg) -> {
                    c.on("internal/update", (thisArg, args) -> {
                        calls.add((Integer) args[0]);
                        @SuppressWarnings("unchecked")
                        Supplier<Object> next = (Supplier<Object>) args[1];
                        return next.get();
                    });
                    return null;
                },
                0);

        fiber.update(1);
        fiber.update(2);
        fiber.update(3);

        assertThat(calls).containsExactly(1, 2, 3);
    }

    @Test
    void fiberLocalUpdateHookCanBeDisposedExplicitly() {
        Context root = Context.create();
        List<Integer> calls = new ArrayList<>();
        java.util.concurrent.atomic.AtomicReference<Disposable> ref =
                new java.util.concurrent.atomic.AtomicReference<>();
        Fiber fiber = root.plugin(
                (c, cfg) -> {
                    ref.set(c.on("internal/update", (thisArg, args) -> {
                        calls.add((Integer) args[0]);
                        @SuppressWarnings("unchecked")
                        Supplier<Object> next = (Supplier<Object>) args[1];
                        return next.get();
                    }));
                    return null;
                },
                0);

        fiber.update(1);
        assertThat(calls).containsExactly(1);

        ref.get().dispose();
        fiber.update(2);
        assertThat(calls).containsExactly(1);
    }
}
