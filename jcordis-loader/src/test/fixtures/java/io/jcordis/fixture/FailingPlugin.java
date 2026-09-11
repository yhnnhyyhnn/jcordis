package io.jcordis.fixture;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import java.util.Map;

/**
 * Test plugin that fails when its config asks for it, counting successful
 * loads — used to verify that a reload failure stays isolated to its entry.
 */
public class FailingPlugin implements Plugin {

    @Override
    public Object apply(Context ctx, Object config) {
        if (config instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("fail"))) {
            throw new IllegalStateException("intentional fixture failure");
        }
        synchronized (FailingPlugin.class) {
            int count = Integer.parseInt(System.getProperty("jcordis.probe.ok", "0"));
            System.setProperty("jcordis.probe.ok", String.valueOf(count + 1));
        }
        return null;
    }
}
