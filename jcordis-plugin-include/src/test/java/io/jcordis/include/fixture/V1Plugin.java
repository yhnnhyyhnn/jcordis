package io.jcordis.include.fixture;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import java.util.concurrent.atomic.AtomicInteger;

/** Version 1 test plugin, discovered from a jar via SPI. */
public class V1Plugin implements Plugin {

    public static final AtomicInteger calls = new AtomicInteger();

    @Override
    public Object apply(Context ctx, Object config) {
        calls.incrementAndGet();
        return null;
    }
}
