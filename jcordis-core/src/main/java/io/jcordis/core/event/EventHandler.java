package io.jcordis.core.event;

/**
 * Event listener, mirroring Cordis's event listener signature.
 *
 * <p>Receives the dispatch {@code thisArg} (the object an event was emitted
 * with, or {@code null}) plus the event arguments. Internal handlers rely on
 * {@code thisArg} to know which context/fiber dispatched the event.
 */
@FunctionalInterface
public interface EventHandler {

    Object invoke(Object thisArg, Object... args);
}