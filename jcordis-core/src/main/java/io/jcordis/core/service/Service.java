package io.jcordis.core.service;

import io.jcordis.core.context.Context;
import io.jcordis.core.event.EventFilter;
import io.jcordis.core.registry.Initializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Base class for provided services, mirroring Cordis's {@code Service}.
 *
 * <p>Construction registers the instance under {@code name} on the current
 * context. Because a service is scoped to its context's isolation realm, it
 * also acts as an {@link EventFilter}: an event dispatched with the service as
 * {@code thisArg} only reaches hooks whose context shares the same isolation
 * key.
 */
public abstract class Service implements EventFilter, Initializable {

    protected final Context ctx;
    public final String name;

    protected Service(Context ctx, String name) {
        this.ctx = ctx;
        this.name = name;
        ctx.reflect().provide(name, this, ctx);
    }

    @Override
    public Object init() {
        return null;
    }

    @Override
    public boolean test(Object context) {
        Context hookCtx = (Context) context;
        return Objects.equals(hookCtx.isolateKey(name), ctx.isolateKey(name));
    }

    /**
     * Merges this service's configuration: the {@code intercept} chain (outer
     * intercepts first) plus an optional base and head, mirroring Cordis's
     * {@code Service[Service.resolveConfig]}. Later configs win for shared keys.
     *
     * <p>A layer is collected whenever an intercept exists on the level — even
     * with a {@code null} value (mirrors Cordis's {@code Object.hasOwn} check);
     * null layers contribute nothing to the merge, exactly like
     * {@code Object.assign} ignoring them. When more than one layer survives,
     * {@link Map} layers merge key-by-key (later wins); a non-Map layer cannot
     * be key-merged in Java, so it replaces the accumulated value.
     */
    public Object resolveConfig(Object base, Object head) {
        List<Object> configs = new ArrayList<>();
        Context cursor = ctx;
        while (cursor != null) {
            if (cursor.hasIntercept(name)) {
                configs.add(0, cursor.interceptConfig(name));
            }
            cursor = cursor.parent();
        }
        if (base != null) {
            configs.add(0, base);
        }
        if (head != null) {
            configs.add(configs.size(), head);
        }
        if (configs.isEmpty()) return null;
        if (configs.size() == 1) return configs.get(0);
        Object acc = null;
        for (Object config : configs) {
            if (config == null) continue;
            if (acc == null) {
                acc = config;
            } else if (acc instanceof Map<?, ?> accMap && config instanceof Map<?, ?> map) {
                Map<String, Object> merged = new java.util.LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : accMap.entrySet()) {
                    merged.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    merged.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                acc = merged;
            } else {
                acc = config;
            }
        }
        return acc;
    }
}
