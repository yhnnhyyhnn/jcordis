package io.jcordis.fixture;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;

/**
 * Plugin class that exists only inside the fixture jar — never on the host
 * classpath. Its body records its own class loader name so tests can prove the
 * class was loaded by a {@code PluginClassLoader}.
 */
public class IsolatedPlugin implements Plugin {

    public static final String PROBE = "jcordis.probe.loader";

    @Override
    public Object apply(Context ctx, Object config) {
        System.setProperty(PROBE, getClass().getClassLoader().getClass().getName());
        return null;
    }
}
