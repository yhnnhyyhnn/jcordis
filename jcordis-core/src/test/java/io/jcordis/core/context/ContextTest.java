package io.jcordis.core.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jcordis.core.fiber.FiberState;
import io.jcordis.core.service.ServiceKey;
import io.jcordis.core.util.Disposable;
import org.junit.jupiter.api.Test;

/** Translates Cordis core context semantics: creation, extend, isolate, intercept, provide/get. */
class ContextTest {

    @Test
    void create_shouldBuildRootWithActiveRootFiber() {
        Context ctx = Context.create();

        assertThat(ctx.root()).isSameAs(ctx);
        assertThat(ctx.parent()).isNull();
        assertThat(ctx.fiber().uid()).isZero();
        assertThat(ctx.fiber().state()).isEqualTo(FiberState.ACTIVE);
        assertThat(ctx.fiber().name()).isEqualTo("root");
        assertThat(ctx.baseUrl()).isNull();
    }

    @Test
    void extend_shouldInheritIsolationAndShareStore() {
        Context root = Context.create();
        Context child = root.extend();

        assertThat(child.root()).isSameAs(root);
        assertThat(child.parent()).isSameAs(root);
        assertThat(child.fiber()).isSameAs(root.fiber());
        assertThat(child.<String>get("nonexistent")).isNull();
    }

    @Test
    void isolate_shouldCreateDistinctServiceRealms() {
        Context root = Context.create();
        Context a = root.isolate("svc");
        Context b = root.isolate("svc");

        a.provide("svc", "value-a");
        b.provide("svc", "value-b");
        root.provide("svc", "value-root");

        assertThat(root.<String>get("svc")).isEqualTo("value-root");
        assertThat(a.<String>get("svc")).isEqualTo("value-a");
        assertThat(b.<String>get("svc")).isEqualTo("value-b");
    }

    @Test
    void getByExplicitKey_shouldBypassIsolationMapping() {
        Context root = Context.create();
        ServiceKey<String> key = ServiceKey.of("direct");

        root.provide("direct", "via-name");
        assertThat(root.get(key)).isEqualTo("via-name");

        Context isolated = root.isolate("direct");
        isolated.provide("direct", "via-isolated");
        assertThat(isolated.<String>get("direct")).isEqualTo("via-isolated");
        assertThat(isolated.get(key)).isEqualTo("via-name");
    }

    @Test
    void provide_shouldRejectDuplicateRegistrationInSameRealm() {
        Context ctx = Context.create();
        ctx.provide("svc", "first");

        assertThatThrownBy(() -> ctx.provide("svc", "second"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has been registered");
    }

    @Test
    void provideDisposable_shouldUnregisterService() {
        Context ctx = Context.create();
        Disposable dispose = ctx.provide("svc", "value");

        assertThat(ctx.<String>get("svc")).isEqualTo("value");
        dispose.dispose();
        assertThat(ctx.<String>get("svc")).isNull();
    }

    @Test
    void get_shouldReturnNullForMissingService() {
        Context ctx = Context.create();
        assertThat(ctx.<String>get("missing")).isNull();
    }

    @Test
    void intercept_shouldOnlyAffectChildContext() {
        Context root = Context.create();
        Context intercepted = root.intercept("logger", "custom");

        assertThat(root.interceptConfig("logger")).isNull();
        assertThat(intercepted.interceptConfig("logger")).isEqualTo("custom");
    }

    @Test
    void extend_shouldCopyInterceptValuesForDescendants() {
        Context root = Context.create();
        Context intercepted = root.intercept("svc", 1);
        Context grandchild = intercepted.extend();

        assertThat(grandchild.interceptConfig("svc")).isEqualTo(1);
    }

    @Test
    void baseUrl_shouldBeSettable() {
        Context ctx = Context.create();
        ctx.setBaseUrl("file:///app/");
        assertThat(ctx.baseUrl()).isEqualTo("file:///app/");
    }
}