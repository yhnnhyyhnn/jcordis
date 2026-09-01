package io.jcordis.core.fiber;

/** Lifecycle states of a {@link Fiber}, mirroring Cordis's {@code FiberState}. */
public enum FiberState {
    PENDING,
    LOADING,
    ACTIVE,
    FAILED,
    DISPOSED,
    UNLOADING,
}
