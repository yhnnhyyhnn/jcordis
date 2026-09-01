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

        String sample =
                Files.readString(dir.resolve("src/main/java/demo/plugin/SamplePlugin.java"), StandardCharsets.UTF_8);
        assertThat(sample).contains("package demo.plugin;");
        assertThat(sample).contains("implements Plugin");

        assertThat(Files.readString(spi, StandardCharsets.UTF_8)).isEqualTo("demo.plugin.SamplePlugin\n");
    }

    @Test
    void generatedApp_shouldCompileAndRun() throws Exception {
        Path dir = Scaffolder.create("test-app", tempDir);
        Path classes = dir.resolve("target/classes");
        Files.createDirectories(classes);

        String classpath = System.getProperty("java.class.path");
        String javac = Path.of(System.getProperty("java.home"), "bin", "javac").toString();
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();

        // compile the generated sources against the current test classpath
        Process compile = new ProcessBuilder(
                        javac,
                        "-cp",
                        classpath,
                        "-d",
                        classes.toString(),
                        dir.resolve("src/main/java/test/app/Index.java").toString(),
                        dir.resolve("src/main/java/test/app/SamplePlugin.java").toString())
                .redirectErrorStream(true)
                .start();
        String compileOut = new String(compile.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(compile.waitFor()).as("javac output: " + compileOut).isEqualTo(0);

        // run the generated app: it must boot, load the sample plugin and exit cleanly
        Process run = new ProcessBuilder(
                        javaBin, "-cp", classes + System.getProperty("path.separator") + classpath, "test.app.Index")
                .redirectErrorStream(true)
                .start();
        String runOut = new String(run.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(run.waitFor()).as("app run output: " + runOut).isEqualTo(0);
        assertThat(runOut).contains("sample plugin loaded");
    }
}
