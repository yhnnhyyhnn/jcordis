package io.jcordis.fixture;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;

/**
 * Version 2 test plugin for jar hot-swap, discovered from a jar via SPI.
 * Records version "2" in a probe property so tests can verify a v1→v2 swap.
 */
public class V2Plugin implements Plugin {

    @Override
    public Object apply(Context ctx, Object config) {
        System.setProperty("jcordis.probe.version", "2");
        return null;
    }
}
