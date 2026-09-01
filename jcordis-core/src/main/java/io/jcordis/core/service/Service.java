package io.jcordis.core.service;

import io.jcordis.core.context.Context;
import io.jcordis.core.event.EventFilter;
import io.jcordis.core.registry.Initializable;
import java.util.ArrayList;
import java.util.HashMap;
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
     */
    public Object resolveConfig(Object base, Object head) {
        List<Object> configs = new ArrayList<>();
        Context cursor = ctx;
        while (cursor != null) {
            Object value = cursor.interceptConfig(name);
            if (value != null) {
                configs.add(0, value);
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
        Map<String, Object> merged = new HashMap<>();
        for (Object config : configs) {
            if (config instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    merged.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
        }
        return merged;
    }
}
