package io.jcordis.core.util;

/**
 * A reversible side effect handler, mirroring Cordis's {@code Disposable} concept
 * (the inverse of an effect). Implementations are collected by {@code Fiber}
 * and invoked in reverse registration order on teardown (temporal composability).
 */
@FunctionalInterface
public interface Disposable {

    /** Revert the side effect. Must be idempotent-safe for callers. */
    void dispose();

    /** A no-op disposable. */
    static Disposable noop() {
        return () -> {};
    }
}