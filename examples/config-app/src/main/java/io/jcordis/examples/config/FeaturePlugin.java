package io.jcordis.examples.config;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;

/** Feature plugin loaded from the YAML config. */
public final class FeaturePlugin implements Plugin {

    @Override
    public Object apply(Context ctx, Object config) {
        String name = "feature";
        if (config instanceof java.util.Map<?, ?> map && map.get("name") != null) {
            name = String.valueOf(map.get("name"));
        }
        ctx.logger("feature").info("feature " + name + " enabled");
        return null;
    }
}