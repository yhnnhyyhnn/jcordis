package io.jcordis.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jcordis.core.context.Context;
import io.jcordis.core.fiber.Fiber;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Regressions for the config-resolution and service-access alignment with
 * Cordis: intercept layers are collected by presence (a {@code null} layer is
 * a layer that contributes nothing, mirroring {@code Object.hasOwn} +
 * {@code Object.assign}), multiple map layers merge with later keys winning, a
 * non-Map layer replaces the accumulated value, and a declared-but-unresolved
 * inject fails loudly instead of leaking a null.
 */
class ResolveConfigLayersTest {

    private static Service service(Context ctx) {
        return new Service(ctx, "svc") {};
    }

    @Test
    void mapLayersShouldMergeWithLaterKeysWinning() {
        Context outer = Context.create().intercept("svc", Map.of("a", 1, "b", 2));
        Service service = service(outer.intercept("svc", Map.of("b", 3)));

        Object resolved = service.resolveConfig(null, Map.of("c", 4));

        assertThat(resolved).isEqualTo(Map.of("a", 1, "b", 3, "c", 4));
    }

    @Test
    void nullValuedLayersShouldContributeNothing() {
        // a present-but-null intercept counts as a layer (hasOwn) and is then
        // ignored by the merge, exactly like Object.assign skipping null
        Context outer = Context.create().intercept("svc", null);
        Service service = service(outer.intercept("svc", Map.of("a", 1)));

        assertThat(service.resolveConfig(null, null)).isEqualTo(Map.of("a", 1));
    }

    @Test
    void nonMapLayerShouldReplaceTheAccumulatedValue() {
        Context outer = Context.create().intercept("svc", Map.of("a", 1));
        Service service = service(outer);

        // Java cannot key-merge arbitrary values: the later layer wins whole
        assertThat(service.resolveConfig(null, "raw")).isEqualTo("raw");
    }

    @Test
    void singleLayerShouldBeReturnedAsIs() {
        Context outer = Context.create().intercept("svc", Map.of("a", 1));
        Service service = service(outer);

        Object resolved = service.resolveConfig(null, null);
        assertThat(resolved).isEqualTo(Map.of("a", 1));
    }

    @Test
    void declaredButMissingInjectShouldThrowOnAccess() {
        Context root = Context.create();
        Fiber fiber = root.inject(List.of("missing"), (ctx, cfg) -> null);

        // the body never runs (dependency unsatisfied) — accessing the
        // injected name through the fiber context must fail loudly (mirrors
        // Cordis's required-service walk)
        assertThatThrownBy(() -> fiber.ctx().get("missing"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot get required service \"missing\"");
    }

    @Test
    void unInjectedLookupShouldStayLenient() {
        Context root = Context.create();
        assertThat(root.<Object>get("never-registered")).isNull();
    }
}
