package io.jcordis.core.event;

import io.jcordis.core.context.Context;

/**
 * A registered event listener, mirroring Cordis's {@code Hook}.
 *
 * <p>Removal sets the {@code removed} flag instead of relying on the hook list
 * alone: a dispatch that already snapshotted the list skips a member disposed
 * while the dispatch was in flight (an asynchronous serial/parallel stage, or
 * a waterfall chain) — mirroring Cordis #242.
 */
public final class Hook {

    private final Context ctx;
    private final EventHandler callback;

    @SuppressWarnings("unused")
    private final boolean prepend;

    private final boolean global;

    /** Set by unregistration; checked by every dispatch mode against its snapshot. */
    volatile boolean removed;

    Hook(Context ctx, EventHandler callback, boolean prepend, boolean global) {
        this.ctx = ctx;
        this.callback = callback;
        this.prepend = prepend;
        this.global = global;
    }

    /** The context the listener was registered on (used for thisArg filtering). */
    public Context ctx() {
        return ctx;
    }

    public EventHandler callback() {
        return callback;
    }

    /** Whether the hook bypasses thisArg filtering. */
    public boolean global() {
        return global;
    }
}
