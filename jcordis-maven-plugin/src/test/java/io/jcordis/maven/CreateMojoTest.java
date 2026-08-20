package io.jcordis.maven;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.Test;

/** Verifies the create goal scaffolds a project through the cli {@code Scaffolder}. */
class CreateMojoTest {

    @Test
    void execute_shouldScaffoldProjectFiles() throws Exception {
        Path target = Files.createTempDirectory("jcordis-mojo");

        CreateMojo mojo = new CreateMojo();
        mojo.setLog(new SystemStreamLog());
        inject(mojo, "name", "demo-app");
        inject(mojo, "target", target.toFile());
        mojo.execute();

        Path dir = target.resolve("demo-app");
        assertThat(dir.resolve("pom.xml")).exists();
        assertThat(dir.resolve("jcordis.yml")).exists();
        assertThat(dir.resolve("src/main/java/demo/app/Index.java")).exists();
        assertThat(dir.resolve("src/main/java/demo/app/SamplePlugin.java")).exists();
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = CreateMojo.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
