package io.jcordis.examples.hmr.fixture;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;

/**
 * Version 1 plugin for the jar hot-swap demo: exists only inside the fixture
 * jar (compiled off the host classpath). Records version "1" in a probe
 * property so the test can observe the swap.
 */
public class V1Plugin implements Plugin {

    @Override
    public Object apply(Context ctx, Object config) {
        System.setProperty("jcordis.probe.version", "1");
        return null;
    }
}
