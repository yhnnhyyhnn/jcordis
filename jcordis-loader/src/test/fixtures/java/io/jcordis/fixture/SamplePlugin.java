package io.jcordis.fixture;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;

/**
 * Test plugin discovered from a jar via SPI. Exists only inside the fixture
 * jar (compiled to {@code target/test-fixtures-classes}, off the host test
 * classpath); its body records a probe property so tests can verify the jar
 * plugin actually ran.
 */
public class SamplePlugin implements Plugin {

    @Override
    public Object apply(Context ctx, Object config) {
        System.setProperty("jcordis.probe.sample", "loaded");
        return null;
    }
}
