package io.jcordis.examples.journal;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import java.util.Map;

/** Sample plugin whose greeting comes from its (persisted) config. */
public final class GreeterPlugin implements Plugin {

    @Override
    public Object apply(Context ctx, Object config) {
        String name =
                config instanceof Map<?, ?> map && map.get("name") != null ? String.valueOf(map.get("name")) : "world";
        System.out.println("greeter loaded: {\"name\": \"" + name + "\"}");
        return null;
    }
}
