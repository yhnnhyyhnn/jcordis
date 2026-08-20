package io.jcordis.maven;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies the check goal enforces the clean-jar contract. */
class CheckMojoTest {

    @TempDir
    Path tempDir;

    @Test
    void cleanJar_shouldPass() throws Exception {
        Path jar = tempDir.resolve("demo-plugin-0.1.0.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("demo/plugin/SamplePlugin.class"));
            out.write(new byte[] {1, 2});
            out.closeEntry();
        }

        CheckMojo mojo = newMojo("demo-plugin-0.1.0.jar");
        mojo.execute(); // must not throw
    }

    @Test
    void jarWithThirdPartyClass_shouldFail() throws Exception {
        Path jar = tempDir.resolve("demo-plugin-0.1.0.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("demo/plugin/SamplePlugin.class"));
            out.write(new byte[] {1, 2});
            out.closeEntry();
            out.putNextEntry(new JarEntry("com/fasterxml/jackson/core/JsonParser.class"));
            out.write(new byte[] {1, 2});
            out.closeEntry();
        }

        CheckMojo mojo = newMojo("demo-plugin-0.1.0.jar");
        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("com/fasterxml/jackson/core/JsonParser.class");
    }

    @Test
    void jarWithFrameworkClass_shouldFail() throws Exception {
        Path jar = tempDir.resolve("demo-plugin-0.1.0.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("io/jcordis/core/context/Context.class"));
            out.write(new byte[] {1, 2});
            out.closeEntry();
        }

        CheckMojo mojo = newMojo("demo-plugin-0.1.0.jar");
        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("io/jcordis/core/context/Context.class");
    }

    @Test
    void missingJar_shouldFail() throws Exception {
        CheckMojo mojo = newMojo("nonexistent.jar");
        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("not found");
    }

    private CheckMojo newMojo(String jarName) throws Exception {
        CheckMojo mojo = new CheckMojo();
        mojo.setLog(new SystemStreamLog());
        inject(mojo, "buildDirectory", tempDir.toFile());
        inject(mojo, "finalName", jarName.substring(0, jarName.length() - ".jar".length()));
        return mojo;
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = CheckMojo.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
