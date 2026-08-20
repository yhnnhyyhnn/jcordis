package io.jcordis.loader.fixture;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import java.util.concurrent.atomic.AtomicInteger;

/** Test plugin discovered from a jar via SPI. */
public class SamplePlugin implements Plugin {

    public static final AtomicInteger calls = new AtomicInteger();

    @Override
    public Object apply(Context ctx, Object config) {
        calls.incrementAndGet();
        return null;
    }
}
