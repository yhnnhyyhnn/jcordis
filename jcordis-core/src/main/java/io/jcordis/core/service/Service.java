package io.jcordis.core.service;

import io.jcordis.core.context.Context;
import io.jcordis.core.event.EventFilter;
import io.jcordis.core.registry.Initializable;
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
}