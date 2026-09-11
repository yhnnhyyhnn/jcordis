package io.jcordis.fixture;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;

/**
 * Test plugin that counts how often its body ran (in a system property), so
 * tests can detect duplicate rebuilds during a partial jar reload.
 */
public class CountingPlugin implements Plugin {

    @Override
    public Object apply(Context ctx, Object config) {
        synchronized (CountingPlugin.class) {
            int count = Integer.parseInt(System.getProperty("jcordis.probe.count", "0"));
            System.setProperty("jcordis.probe.count", String.valueOf(count + 1));
        }
        return null;
    }
}
