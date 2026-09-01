package io.jcordis.core.context;

import io.jcordis.core.event.EventBus;
import io.jcordis.core.event.EventHandler;
import io.jcordis.core.event.EventOptions;
import io.jcordis.core.fiber.EffectRunner;
import io.jcordis.core.fiber.Fiber;
import io.jcordis.core.logger.Logger;
import io.jcordis.core.logger.LoggerService;
import io.jcordis.core.reflect.ReflectService;
import io.jcordis.core.registry.Plugin;
import io.jcordis.core.registry.RegistryService;
import io.jcordis.core.service.ServiceKey;
import io.jcordis.core.util.Disposable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The unified context of a component, mirroring Cordis's {@code Context}.
 *
 * <p>A context simultaneously carries:
 * <ul>
 *   <li>the <em>effect context</em> — how to register revertible side effects
 *       ({@link #effect});</li>
 *   <li>the <em>coeffect context</em> — which services are visible here, governed
 *       by isolation ({@link #isolate}) and configuration interception
 *       ({@link #intercept});</li>
 *   <li>the <em>event context</em> — how to observe and emit events
 *       ({@link #on}, {@link #emit}, …).</li>
 *   <li>the <em>plugin context</em> — how to load plugins and inject services
 *       ({@link #plugin}, {@link #inject}).</li>
 * </ul>
 */
public interface Context {

    /** The root context of this context tree. */
    Context root();

    /** The parent context in the extend chain, or {@code null} at the root. */
    Context parent();

    /** The fiber this context belongs to. */
    Fiber fiber();

    /** Base URL for resolving relative resource/config paths. */
    String baseUrl();

    void setBaseUrl(String baseUrl);

    // ----- service access (spatial composability) -----

    /** Resolves the service under the given name within this context's isolation realm. */
    <T> T get(String name);

    /** Resolves the service under an explicit key. */
    <T> T get(ServiceKey<T> key);

    /** Sets the service value for an explicit key. */
    <T> void set(ServiceKey<T> key, T value);

    /** Sets the service value for a provided name. */
    <T> void set(String name, T value);

    /**
     * Registers a service implementation for the given name within this
     * context's isolation realm. Returns a disposable that unregisters it.
     */
    <T> Disposable provide(String name, T value);

    /** The service registry of this context tree. */
    ReflectService reflect();

    /** The plugin registry of this context tree. */
    RegistryService registry();

    /** The isolation key of {@code name} in this context, or {@code null}. */
    ServiceKey<?> isolateKey(String name);

    // ----- context structure -----

    /** Creates a child context inheriting isolation and interception. */
    Context extend();

    /** Creates a child context with additional properties. */
    Context extend(Map<String, Object> meta);

    /** The thisArg filter predicate of this context, or {@code null}. */
    Predicate<Object> filter();

    /** Creates a child context carrying the given thisArg filter predicate. */
    Context extend(Predicate<Object> filter);

    /** Creates a child context with a fresh isolation key for {@code name}. */
    Context isolate(String name);

    /** Creates a child context with a shared isolation key for {@code name}. */
    Context isolate(String name, ServiceKey<?> key);

    /** Creates a child context belonging to the given fiber. */
    Context child(Fiber fiber);

    /** Returns the intercepted config for {@code name} in this context, or {@code null}. */
    Object interceptConfig(String name);

    /** Creates a child context intercepting the config of service {@code name}. */
    Context intercept(String name, Object config);

    // ----- effects (temporal composability) -----

    /** Registers an effect on the current fiber. */
    Disposable effect(EffectRunner runner, String label);

    // ----- events (temporal composability) -----

    /** The event bus shared by this context tree. */
    EventBus events();

    /** The logger service shared by this context tree. */
    default LoggerService loggerService() {
        return ((ContextImpl) root()).loggerService();
    }

    /** Returns a logger named after this context's intercept chain or fiber. */
    default Logger logger() {
        return loggerService().named(this);
    }

    /** Returns a logger with an explicit name. */
    default Logger logger(String name) {
        return loggerService().named(name);
    }

    default Disposable on(String name, EventHandler listener) {
        return on(name, listener, EventOptions.of());
    }

    default Disposable on(String name, EventHandler listener, EventOptions options) {
        return events().on(this, name, listener, options);
    }

    default Disposable once(String name, EventHandler listener) {
        return once(name, listener, EventOptions.of());
    }

    default Disposable once(String name, EventHandler listener, EventOptions options) {
        return events().once(this, name, listener, options);
    }

    default void emit(String name, Object... args) {
        events().emit((Object) null, name, args);
    }

    default void emit(Object thisArg, String name, Object... args) {
        events().emit(thisArg, name, args);
    }

    default CompletableFuture<Void> parallel(String name, Object... args) {
        return events().parallel((Object) null, name, args);
    }

    default CompletableFuture<Void> parallel(Object thisArg, String name, Object... args) {
        return events().parallel(thisArg, name, args);
    }

    default CompletableFuture<Object> serial(String name, Object... args) {
        return events().serial((Object) null, name, args);
    }

    default CompletableFuture<Object> serial(Object thisArg, String name, Object... args) {
        return events().serial(thisArg, name, args);
    }

    default Object bail(String name, Object... args) {
        return events().bail((Object) null, name, args);
    }

    default Object bail(Object thisArg, String name, Object... args) {
        return events().bail(thisArg, name, args);
    }

    default Object waterfall(String name, Object value, Function<Object[], Object> inner) {
        return events().waterfall((Object) null, name, new Object[] {value}, inner);
    }

    default Object waterfall(Object thisArg, String name, Object[] args, Function<Object[], Object> inner) {
        return events().waterfall(thisArg, name, args, inner);
    }

    // ----- plugins -----

    default Fiber plugin(Plugin plugin) {
        return registry().plugin(this, plugin, null);
    }

    default Fiber plugin(Plugin plugin, Object config) {
        return registry().plugin(this, plugin, config);
    }

    default Fiber inject(List<String> names, Plugin callback) {
        return registry().inject(this, names, callback);
    }

    default Fiber inject(Map<String, Object> inject, Plugin callback) {
        return registry().inject(this, inject, callback);
    }

    /** Declares mixin accessor keys on a service. */
    default void mixin(String source, List<String> keys) {
        reflect().mixin(source, keys);
    }

    /** Whether the given object is a Context. */
    static boolean is(Object value) {
        return value instanceof Context;
    }

    /** Creates a new root context tree. */
    static Context create() {
        return ContextImpl.create();
    }
}
