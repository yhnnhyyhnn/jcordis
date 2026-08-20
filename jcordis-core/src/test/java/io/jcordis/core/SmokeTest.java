package io.jcordis.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Smoke test verifying the build pipeline and module structure.
 * Will be replaced by real core tests in Phase 2 (M2).
 */
class SmokeTest {

    @Test
    void classLoadsFromCoreModule() {
        assertThat(SmokeTest.class.getPackageName()).isEqualTo("io.jcordis.core");
    }

    @Test
    void javaVersionIs21() {
        assertThat(Runtime.version().feature()).isEqualTo(21);
    }

    @Test
    void junitAndAssertjAvailable() {
        assertThat("foo").startsWith("f").endsWith("o");
    }
}
