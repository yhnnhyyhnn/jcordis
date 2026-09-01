package io.jcordis.examples.hmr;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;

/**
 * A stateless demo plugin whose behavior is driven entirely by config.
 *
 * <p>In the hot-reload demo this plugin is registered as a builtin; editing
 * {@code greeting} in the config file makes {@link Hmr} re-apply the entry and
 * the plugin re-runs with the new value — no application restart needed.
 */
public final class GreeterPlugin implements Plugin {

    @Override
    public Object apply(Context ctx, Object config) {
        String greeting = "hello";
        if (config instanceof java.util.Map<?, ?> map && map.get("greeting") instanceof String g) {
            greeting = g;
        }
        ctx.logger("greeter").info("greeting: %s", greeting);
        return null;
    }
}
