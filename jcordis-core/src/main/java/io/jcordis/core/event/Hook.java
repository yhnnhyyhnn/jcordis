package io.jcordis.core.event;

import io.jcordis.core.context.Context;

/**
 * A registered event listener, mirroring Cordis's {@code Hook}.
 *
 * @param ctx the context the listener was registered on (used for thisArg filtering)
 * @param callback the listener itself
 * @param prepend whether the hook sits at the front of the hook list
 * @param global whether the hook bypasses thisArg filtering
 */
public record Hook(Context ctx, EventHandler callback, boolean prepend, boolean global) {}