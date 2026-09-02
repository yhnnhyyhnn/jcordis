package io.jcordis.all;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

/**
 * Integration test (runs after {@code package}, when the shaded aggregated jar
 * exists): verifies every runtime module is bundled into {@code jcordis-all}.
 */
class AggregateJarIT {

    @Test
    void aggregatedJar_shouldContainAllModules() throws Exception {
        Path jar = Path.of("target", "jcordis-all-1.0.0.jar");
        assertThat(jar).exists();

        String[] expectedClasses = {
            "io/jcordis/core/context/ContextImpl.class",
            "io/jcordis/core/util/EffectList.class",
            "io/jcordis/core/timer/TimerService.class",
            "io/jcordis/core/logger/ConsoleExporter.class",
            "io/jcordis/loader/Loader.class",
            "io/jcordis/loader/include/Hmr.class",
            "io/jcordis/loader/include/JarWatcher.class",
            "io/jcordis/cli/Scaffolder.class",
        };
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> entries = jarFile.entries();
            StringBuilder all = new StringBuilder();
            while (entries.hasMoreElements()) {
                all.append(entries.nextElement().getName()).append('\n');
            }
            String content = all.toString();
            for (String expected : expectedClasses) {
                assertThat(content).contains(expected);
            }
        }
    }

    @Test
    void aggregatedJar_shouldNotContainThirdPartyClasses() throws Exception {
        Path jar = Path.of("target", "jcordis-all-1.0.0.jar");
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                assertThat(name)
                        .as("aggregated jar must not bundle third-party classes")
                        .doesNotStartWith("com/fasterxml/")
                        .doesNotStartWith("org/slf4j/")
                        .doesNotStartWith("org/apache/maven/");
            }
        }
    }

    @Test
    void minimalContextFlow_shouldWork() {
        Context root = Context.create();
        assertThat(root).isNotNull();
        assertThat(root.fiber().state().name()).isEqualTo("ACTIVE");
    }
}
