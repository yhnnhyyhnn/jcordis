package io.jcordis.core.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jcordis.core.context.Context;
import io.jcordis.core.fiber.Fiber;
import io.jcordis.core.fiber.FiberState;
import io.jcordis.core.util.Disposable;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * In-place plugin replacement (mirrors Cordis's {@code registry.replace}):
 * fibers stay alive and re-execute the new body under the new inject
 * declarations, and the replaced-away reference keeps working through an
 * alias — has/get/delete forward to the replacement.
 */
class RegistryReplaceTest {

    @Test
    void replacedFibersExecuteTheNewBody() {
        Context root = Context.create();
        List<String> applied = new CopyOnWriteArrayList<>();
        io.jcordis.core.registry.Plugin oldPlugin = (ctx, config) -> {
            applied.add("old");
            return null;
        };
        io.jcordis.core.registry.Plugin newPlugin = (ctx, config) -> {
            applied.add("new");
            return null;
        };
        Fiber fiber = root.plugin(oldPlugin);
        assertThat(applied).containsExactly("old");

        List<Fiber> retargeted = root.registry().replace(oldPlugin, newPlugin);

        assertThat(retargeted).containsExactly(fiber);
        assertThat(applied).containsExactly("old", "new");
        assertThat(fiber.state()).isEqualTo(FiberState.ACTIVE);
        // the replacement owns the registry key; the old reference forwards
        assertThat(root.registry().has(newPlugin)).isTrue();
        assertThat(root.registry().has(oldPlugin)).isTrue();
        assertThat(root.registry().get(oldPlugin)).isSameAs(root.registry().get(newPlugin));
    }

    @Test
    void replaceSwapsTheInjectDeclarations() {
        Context root = Context.create();
        root.provide("svc", "value");
        List<Object> seen = new CopyOnWriteArrayList<>();
        java.util.Map<String, Object> inject = new java.util.HashMap<>();
        inject.put("svc", null);
        io.jcordis.core.registry.Plugin oldPlugin =
                io.jcordis.core.registry.Plugin.object("old", inject, (ctx, config) -> {
                    seen.add(ctx.get("svc"));
                    return null;
                });
        io.jcordis.core.registry.Plugin newPlugin = (ctx, config) -> {
            seen.add(ctx.get("svc"));
            return null;
        };
        Fiber fiber = root.plugin(oldPlugin);
        fiber.await().join();
        assertThat(seen).containsExactly("value");

        // the new declaration drops the inject: the rebuilt fiber no longer
        // declares it (and its intercept layer is rebuilt without the config)
        root.registry().replace(oldPlugin, newPlugin);

        assertThat(seen).containsExactly("value", "value");
        assertThat(fiber.inject()).isEmpty();
    }

    @Test
    void replaceEmitsForTheRetargetedFibers() {
        Context root = Context.create();
        List<Fiber> replaced = new CopyOnWriteArrayList<>();
        root.on("internal/replace", (thisArg, args) -> {
            replaced.add((Fiber) args[0]);
            return null;
        });
        io.jcordis.core.registry.Plugin oldPlugin = (ctx, config) -> null;
        io.jcordis.core.registry.Plugin newPlugin = (ctx, config) -> null;
        Fiber fiber = root.plugin(oldPlugin);

        root.registry().replace(oldPlugin, newPlugin);

        assertThat(replaced).containsExactly(fiber);
    }

    @Test
    void deletingThroughTheAliasRemovesTheReplacement() {
        Context root = Context.create();
        List<String> disposed = new CopyOnWriteArrayList<>();
        io.jcordis.core.registry.Plugin oldPlugin = (ctx, config) -> (Disposable) () -> disposed.add("body");
        io.jcordis.core.registry.Plugin newPlugin = (ctx, config) -> (Disposable) () -> disposed.add("body");
        root.plugin(oldPlugin);
        root.registry().replace(oldPlugin, newPlugin);

        // the replaced-away reference forwards to the live runtime
        root.registry().delete(oldPlugin);

        assertThat(root.registry().has(newPlugin)).isFalse();
        assertThat(root.registry().has(oldPlugin)).isFalse();
        // once for the replace (the old body unloads), once for the delete
        assertThat(disposed).containsExactly("body", "body");
    }

    @Test
    void replacingAnUnregisteredPluginThrows() {
        Context root = Context.create();
        assertThatThrownBy(() -> root.registry().replace((ctx, config) -> null, (ctx, config) -> null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not registered");
    }
}
