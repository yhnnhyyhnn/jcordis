package io.jcordis.core;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Calibrated JMH run — disabled by default (slow, minutes). Enable with
 * {@code mvn -Pbenchmark -pl jcordis-core test -Dtest=JmhRunnerTest}.
 */
class JmhRunnerTest {

    @Test
    @Disabled("run explicitly with -Pbenchmark -Dtest=JmhRunnerTest")
    void runAllBenchmarks() throws Exception {
        Options options = new OptionsBuilder()
                .include(".*JmhBenchmarks.*")
                .shouldFailOnError(true)
                .build();
        new Runner(options).run();
    }
}
