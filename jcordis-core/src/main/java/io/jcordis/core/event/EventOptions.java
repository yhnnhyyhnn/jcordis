package io.jcordis.core.event;

/**
 * Registration options for an event listener, mirroring Cordis's
 * {@code EventOptions}.
 *
 * @param prepend whether the listener is inserted at the front of the hook list
 * @param global whether the listener bypasses thisArg filtering on dispatch
 */
public record EventOptions(boolean prepend, boolean global) {

    /** Default options: append, filtered by thisArg. */
    public static EventOptions of() {
        return new EventOptions(false, false);
    }

    public static EventOptions of(boolean prepend) {
        return new EventOptions(prepend, false);
    }

    public static EventOptions of(boolean prepend, boolean global) {
        return new EventOptions(prepend, global);
    }
}