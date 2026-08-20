package io.jcordis.core.reflect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jcordis.core.context.Context;
import io.jcordis.core.fiber.Fiber;
import io.jcordis.core.util.Disposable;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Translates Cordis reflect.spec.ts: context identity, access checks, injection. */
class ReflectTest {

    @Test
    void contextIs() {
        Context root = Context.create();
        assertThat(Context.is(root)).isTrue();
        assertThat(Context.is("not a context")).isFalse();
    }

    @Test
    void accessCheck() {
        Context root = Context.create();

        root.plugin((ctx, config) -> {
            assertThat(ctx.<Object>get("bar")).isNull();
            assertThatThrownBy(() -> ctx.set("bar", 0)).hasMessageContaining("cannot set property \"bar\" without provide");
            return null;
        }).await().join();

        root.plugin((ctx, config) -> {
            assertThatThrownBy(() -> ctx.set("foo", 0)).hasMessageContaining("cannot set property \"foo\" without provide");
            ctx.provide("foo", null);
            assertThatThrownBy(() -> ctx.provide("foo", null))
                    .hasMessageContaining("service \"foo\" has been registered");
            ctx.set("foo", 0);
            return null;
        }).await().join();
    }

    @Test
    void serviceInjection() {
        Context root = Context.create();
        root.mixin("foo", List.of("bar"));
        root.provide("foo", null);
        root.set("foo", Map.of("bar", 1));

        assertThat(root.<Object>get("foo")).isNotNull();
        assertThat(root.<Object>get("bar")).isNull();
        assertThat(root.<Object>get("root")).isNull();

        root.inject(List.of("foo"), (ctx, config) -> {
            ctx.extend(Map.of("baz", 2)).plugin((child, cfg) -> {
                assertThat(child.<Object>get("baz")).isEqualTo(2);
                return null;
            });
            return null;
        }).await().join();
    }

    @Test
    void serviceInjectLeak() {
        Context root = Context.create();
        root.provide("foo", Map.of("bar", 1));
        Fiber fiber = root.inject(List.of("foo"), (ctx, config) -> null);
        fiber.await().join();

        assertThat(fiber.ctx().<Object>get("foo")).isNotNull();
        fiber.disposeAsync().join();
        assertThatThrownBy(() -> fiber.ctx().get("foo"))
                .hasMessageContaining("cannot get required service \"foo\" in inactive context");
    }
}