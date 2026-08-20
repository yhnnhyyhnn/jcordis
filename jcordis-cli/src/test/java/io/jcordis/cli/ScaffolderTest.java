package io.jcordis.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies the generated scaffold structure and placeholder rendering. */
class ScaffolderTest {

    @TempDir
    Path tempDir;

    @Test
    void createsExpectedStructure() throws IOException {
        Path dir = Scaffolder.create("my-app", tempDir);

        assertThat(dir.resolve("pom.xml")).exists();
        assertThat(dir.resolve("jcordis.yml")).exists();
        assertThat(dir.resolve("src/main/java/my/app/Index.java")).exists();
        assertThat(dir.resolve("src/main/java/my/app/SamplePlugin.java")).exists();
    }

    @Test
    void rendersPlaceholders() throws IOException {
        Path dir = Scaffolder.create("demo", tempDir);

        String pom = Files.readString(dir.resolve("pom.xml"), StandardCharsets.UTF_8);
        assertThat(pom).contains("<artifactId>demo</artifactId>");
        assertThat(pom).contains("demo.Index");

        String entry = Files.readString(dir.resolve("src/main/java/demo/Index.java"), StandardCharsets.UTF_8);
        assertThat(entry).contains("package demo;");
        assertThat(entry).contains("logger(\"demo\")");

        String sample = Files.readString(dir.resolve("src/main/java/demo/SamplePlugin.java"), StandardCharsets.UTF_8);
        assertThat(sample).contains("package demo;");
        assertThat(sample).contains("sample plugin loaded");
    }

    @Test
    void convertsNameToPackage() {
        assertThat(Scaffolder.toPackage("my-app")).isEqualTo("my.app");
        assertThat(Scaffolder.toPackage("HelloWorld")).isEqualTo("helloworld");
        assertThat(Scaffolder.toPackage("a.b")).isEqualTo("a.b");
    }

    @Test
    void cliCreateCommand() {
        int code = Cli.run(new String[] {"create", "test-app", tempDir.toString()});
        assertThat(code).isZero();
        assertThat(tempDir.resolve("test-app/pom.xml")).exists();
    }

    @Test
    void cliRejectsInvalidUsage() {
        assertThat(Cli.run(new String[] {})).isEqualTo(1);
        assertThat(Cli.run(new String[] {"bogus", "x"})).isEqualTo(1);
        assertThat(Cli.run(new String[] {"create", "bad name!"})).isEqualTo(1);
    }

    @Test
    void createPlugin_shouldEmbedProvidedContract() throws IOException {
        Path dir = Scaffolder.createPlugin("demo-plugin", tempDir);

        assertThat(dir.resolve("pom.xml")).exists();
        assertThat(dir.resolve("src/main/java/demo/plugin/SamplePlugin.java")).exists();
        Path spi = dir.resolve("src/main/resources/META-INF/services/io.jcordis.core.registry.Plugin");
        assertThat(spi).exists();

        String pom = Files.readString(dir.resolve("pom.xml"), StandardCharsets.UTF_8);
        assertThat(pom).contains("<artifactId>demo-plugin</artifactId>");
        assertThat(pom).contains("<scope>provided</scope>");
        assertThat(pom).contains("<goal>check</goal>");

        String sample = Files.readString(dir.resolve("src/main/java/demo/plugin/SamplePlugin.java"), StandardCharsets.UTF_8);
        assertThat(sample).contains("package demo.plugin;");
        assertThat(sample).contains("implements Plugin");

        assertThat(Files.readString(spi, StandardCharsets.UTF_8)).isEqualTo("demo.plugin.SamplePlugin\n");
    }
}