package io.jcordis.maven;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.nio.file.Path;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies the create-plugin goal scaffolds a plugin project. */
class CreatePluginMojoTest {

    @TempDir
    Path tempDir;

    @Test
    void execute_shouldScaffoldPluginProject() throws Exception {
        CreatePluginMojo mojo = new CreatePluginMojo();
        mojo.setLog(new SystemStreamLog());
        inject(mojo, "name", "demo-plugin");
        inject(mojo, "target", tempDir.toFile());
        mojo.execute();

        Path dir = tempDir.resolve("demo-plugin");
        assertThat(dir.resolve("pom.xml")).exists();
        assertThat(dir.resolve("src/main/java/demo/plugin/SamplePlugin.java")).exists();
        assertThat(dir.resolve("src/main/resources/META-INF/services/io.jcordis.core.registry.Plugin"))
                .exists();
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = CreatePluginMojo.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
