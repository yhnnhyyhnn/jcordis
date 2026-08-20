package io.jcordis.core.fiber;

import io.jcordis.core.context.Context;
import io.jcordis.core.event.EventHandler;
import io.jcordis.core.reflect.Impl;
import io.jcordis.core.registry.PluginRuntime;
import io.jcordis.core.util.Disposable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Lifecycle carrier of a plugin instance, mirroring Cordis's {@code Fiber}.
 *
 * <p>A fiber owns an ordered collection of effect disposables. On teardown they
 * are invoked in reverse registration order, implementing <em>temporal
 * composability</em>: every side effect a plugin registered is reverted.
 */
public interface Fiber {

    /** Unique id; {@code 0} for the root fiber, {@code -1} once disposed. */
    int uid();

    /** Human-readable name (plugin name, or {@code "root"}). */
    String name();

    /** Current lifecycle state. */
    FiberState state();

    /** The context this fiber belongs to. */
    Context ctx();

    /** The plugin config carried by this fiber. */
    Object config();

    /** The plugin runtime this fiber belongs to, or {@code null} for the root. */
    PluginRuntime runtime();

    /** The loader entry this fiber belongs to, or {@code null}. */
    default Object entry() {
        return null;
    }

    /** Associates this fiber with a loader entry. */
    default void setEntry(Object entry) {}

    /** Resolved inject declarations ({@code name -> config}). */
    Map<String, Object> inject();

    /**
     * Fiber-local event hooks, keyed by event name. Only {@code internal/update}
     * hooks are stored here (via the {@code internal/listener} interception);
     * all other hooks live on the shared {@code EventBus}.
     */
    Map<String, List<EventHandler>> hooks();

    /**
     * Applies a config update by running the {@code internal/update} event
     * chain. The updated config is stored on the fiber.
     *
     * @param noSave whether the update should skip persistence
     */
    void update(Object config, boolean noSave);

    /** Applies a config update ({@code noSave = false}). */
    default void update(Object config) {
        update(config, false);
    }

    /**
     * Registers an effect. The runner executes immediately; the disposables it
     * returns are collected and reverted when the returned {@link Disposable}
     * is invoked or when the fiber itself is torn down.
     *
     * @throws CordisError if the fiber is not active
     */
    Disposable effect(EffectRunner runner, String label);

    /**
     * Throws {@link CordisError} if this fiber is not active (i.e. disposed).
     */
    void assertActive();

    /** Re-resolves the given injected service and refreshes the dependency epoch. */
    void checkImpl(String name);

    /** Recomputes the dependency epoch, reloading or unloading the plugin body. */
    void refresh();

    /**
     * Restarts the plugin body: resets the dependency epoch to inactive, then
     * re-runs {@link #refresh()}, reloading the body if all injected services
     * are available. Mirrors Cordis's {@code Fiber.restart}.
     */
    CompletableFuture<Fiber> restart();

    /** Waits for pending load/unload work to settle. */
    CompletableFuture<Fiber> await();

    /** Number of registered effect disposables. */
    int effectCount();

    /** Tears down the fiber, disposing all effects in reverse order. */
    CompletableFuture<Void> disposeAsync();
}