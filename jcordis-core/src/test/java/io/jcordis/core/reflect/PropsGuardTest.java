package io.jcordis.core.reflect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jcordis.core.context.Context;
import io.jcordis.core.service.ServiceKey;
import org.junit.jupiter.api.Test;

/**
 * Regressions for the property-model alignment with Cordis: a name declared as
 * an accessor cannot double as a service and vice versa (the props guard), and
 * canonical service keys are value-comparable without a global instance cache.
 */
class PropsGuardTest {

    @Test
    void provideAfterAccessorDeclarationShouldThrow() {
        Context root = Context.create();
        root.reflect().mixin("svc", java.util.List.of("foo"));

        assertThatThrownBy(() -> root.provide("foo", new Object()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already declared as accessor");
    }

    @Test
    void mixinOverAProvidedServiceShouldThrow() {
        Context root = Context.create();
        root.provide("foo", new Object());

        assertThatThrownBy(() -> root.reflect().mixin("svc", java.util.List.of("foo")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already declared as service");
    }

    @Test
    void sameKindRedeclarationShouldBeIdempotent() {
        Context root = Context.create();
        root.reflect().mixin("svc", java.util.List.of("foo", "bar"));
        assertThatCode(() -> root.reflect().mixin("svc", java.util.List.of("foo")))
                .doesNotThrowAnyException();
    }

    @Test
    void canonicalKeysShouldBeValueComparableWithoutACache() {
        ServiceKey<Object> first = ServiceKey.of("timer");
        ServiceKey<Object> second = ServiceKey.of("timer");
        ServiceKey<Object> unique = ServiceKey.unique("timer");
        ServiceKey<Object> uniqueAgain = ServiceKey.unique("timer");

        assertThat(first).isEqualTo(second);
        assertThat(first).isNotSameAs(second);
        assertThat(first.hashCode()).isEqualTo(second.hashCode());
        assertThat(unique).isNotEqualTo(uniqueAgain);
        assertThat(unique).isNotEqualTo(first);
        assertThat(ServiceKey.of("other")).isNotEqualTo(first);
    }
}
