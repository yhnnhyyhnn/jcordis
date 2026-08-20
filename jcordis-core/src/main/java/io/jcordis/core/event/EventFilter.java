package io.jcordis.core.event;

/**
 * A filter carried by a dispatch {@code thisArg}, mirroring Cordis's
 * {@code Context.filter} symbol protocol.
 *
 * <p>When an event is dispatched with a thisArg implementing this interface,
 * non-global hooks are kept only if {@link #test} accepts the hook's context.
 */
@FunctionalInterface
public interface EventFilter {

    boolean test(Object context);
}