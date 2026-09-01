package io.jcordis.core.context;

import io.jcordis.core.event.EventBus;
import io.jcordis.core.fiber.EffectRunner;
import io.jcordis.core.fiber.Fiber;
import io.jcordis.core.fiber.FiberImpl;
import io.jcordis.core.fiber.FiberState;
import io.jcordis.core.logger.LoggerService;
import io.jcordis.core.reflect.ReflectService;
import io.jcordis.core.registry.RegistryService;
import io.jcordis.core.service.ServiceKey;
import io.jcordis.core.util.Disposable;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Default {@link Context} implementation.
 *
 * <p>Replaces Cordis's prototype-chain context with explicit parent links and
 * per-context isolation/interception maps. The service store, event bus and
 * registries are shared across the whole context tree (owned by the root),
 * while {@code isolateMap}, {@code interceptMap}, {@code props} and
 * {@code filter} are copied per child context — mirroring Cordis's
 * {@code Object.create(...)} inheritance semantics without prototype chains.
 */
public final class ContextImpl implements Context {

    private final ContextImpl root;
    private final ContextImpl parent;
    private final Map<String, ServiceKey<?>> isolateMap;
    private final Map<String, Object> interceptMap;
    private final Map<String, Object> props;
    private final Predicate<Object> filter;

    private volatile ReflectService reflect;
    private volatile RegistryService registry;
    private volatile EventBus events;
    private volatile LoggerService loggerService;
    private volatile Fiber fiber;
    private volatile String baseUrl;

    private ContextImpl(
            ContextImpl root,
            ContextImpl parent,
            Map<String, ServiceKey<?>> isolateMap,
            Map<String, Object> interceptMap,
            Map<String, Object> props,
            Predicate<Object> filter,
            Fiber fiber) {
        this.root = root;
        this.parent = parent;
        this.isolateMap = isolateMap;
        this.interceptMap = interceptMap;
        this.props = props;
        this.filter = filter;
        this.fiber = fiber;
    }

    /** Creates a new root context tree. */
    static Context create() {
        ContextImpl ctx = new ContextImpl(null, null, new HashMap<>(), new HashMap<>(), new HashMap<>(), null, null);
        ctx.fiber = FiberImpl.root(ctx);
        ctx.events = new EventBus(ctx);
        ctx.reflect = new ReflectService(ctx);
        ctx.registry = new RegistryService(ctx);
        ctx.loggerService = new LoggerService(ctx);
        ((FiberImpl) ctx.fiber).clearEffects();
        return ctx;
    }

    @Override
    public Context root() {
        return root == null ? this : root;
    }

    private ContextImpl actualRoot() {
        return root == null ? this : root;
    }

    @Override
    public Context parent() {
        return parent;
    }

    @Override
    public Fiber fiber() {
        return fiber;
    }

    @Override
    public String baseUrl() {
        return baseUrl;
    }

    @Override
    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    @Override
    public ReflectService reflect() {
        return actualRoot().reflect;
    }

    @Override
    public RegistryService registry() {
        return actualRoot().registry;
    }

    @Override
    public EventBus events() {
        return actualRoot().events;
    }

    @Override
    public LoggerService loggerService() {
        return actualRoot().loggerService;
    }

    @Override
    public ServiceKey<?> isolateKey(String name) {
        return isolateMap.get(name);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(String name) {
        Object prop = props.get(name);
        if (prop != null) {
            return (T) prop;
        }
        // service access goes through the internal/get waterfall (mirrors
        // Cordis's proxy handler); the default tail resolves from the store
        return (T) events().waterfall((Object) null, "internal/get", new Object[] {this, name}, args -> {
            if (fiber().state() == FiberState.DISPOSED && fiber().inject().containsKey(name)) {
                throw new IllegalStateException("cannot get required service \"" + name + "\" in inactive context");
            }
            Object value = reflect().get(name, this);
            return value != null ? value : null;
        });
    }

    @Override
    public <T> T get(ServiceKey<T> key) {
        return reflect().get(key);
    }

    @Override
    public <T> void set(ServiceKey<T> key, T value) {
        reflect().set(key, value);
    }

    @Override
    public <T> void set(String name, T value) {
        events().waterfall((Object) null, "internal/set", new Object[] {this, name, value}, args -> {
            reflect().set(name, value, this);
            return null;
        });
    }

    @Override
    public <T> Disposable provide(String name, T value) {
        return reflect().provide(name, value, this);
    }

    @Override
    public Context extend() {
        return extend(Map.of());
    }

    @Override
    public Context extend(Map<String, Object> meta) {
        Map<String, Object> merged = new HashMap<>(props);
        merged.putAll(meta);
        return new ContextImpl(
                actualRoot(), this, new HashMap<>(isolateMap), new HashMap<>(interceptMap), merged, filter, fiber);
    }

    @Override
    public Context extend(Predicate<Object> filter) {
        return new ContextImpl(
                actualRoot(), this, new HashMap<>(isolateMap), new HashMap<>(interceptMap), props, filter, fiber);
    }

    @Override
    public Predicate<Object> filter() {
        return filter;
    }

    @Override
    public Context isolate(String name) {
        return isolate(name, null);
    }

    @Override
    public Context isolate(String name, ServiceKey<?> key) {
        Map<String, ServiceKey<?>> map = new HashMap<>(isolateMap);
        map.put(name, key != null ? key : ServiceKey.unique(name));
        return new ContextImpl(actualRoot(), this, map, interceptMap, props, filter, fiber);
    }

    @Override
    public Context child(Fiber fiber) {
        return new ContextImpl(
                actualRoot(), this, new HashMap<>(isolateMap), new HashMap<>(interceptMap), props, filter, fiber);
    }

    @Override
    public Context intercept(String name, Object config) {
        Map<String, Object> map = new HashMap<>(interceptMap);
        map.put(name, config);
        return new ContextImpl(actualRoot(), this, isolateMap, map, props, filter, fiber);
    }

    @Override
    public Object interceptConfig(String name) {
        return interceptMap.get(name);
    }

    @Override
    public Disposable effect(EffectRunner runner, String label) {
        return fiber.effect(runner, label);
    }

    @Override
    public String toString() {
        return "Context <" + fiber.name() + ">";
    }
}
