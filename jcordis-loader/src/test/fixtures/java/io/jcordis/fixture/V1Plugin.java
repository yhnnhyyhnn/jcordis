package io.jcordis.fixture;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;

/**
 * Version 1 test plugin for jar hot-swap, discovered from a jar via SPI.
 * Records version "1" in a probe property so tests can verify a v1→v2 swap.
 */
public class V1Plugin implements Plugin {

    @Override
    public Object apply(Context ctx, Object config) {
        System.setProperty("jcordis.probe.version", "1");
        return null;
    }
}
