package io.jcordis.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Generates a jcordis application scaffold, mirroring {@code create-cordis}.
 *
 * <p>Creates {@code pom.xml}, a {@code jcordis.yml} config, an entry class and
 * a sample plugin under the target directory. All templates are embedded and
 * rendered by placeholder substitution ({@code {{name}}}, {@code {{pkg}}}).
 */
public final class Scaffolder {

    private Scaffolder() {}

    /** Creates a new application scaffold in {@code target}/{@code name}. */
    public static Path create(String name, Path target) throws IOException {
        String pkg = toPackage(name);
        Path dir = target.resolve(name);
        Files.createDirectories(dir);
        Files.createDirectories(dir.resolve("src/main/java").resolve(pkg.replace('.', '/')));
        Files.createDirectories(dir.resolve("src/main/resources"));

        Files.writeString(dir.resolve("pom.xml"), render(POM, name).replace("{{pkg}}", pkg), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("jcordis.yml"), render(CONFIG, name), StandardCharsets.UTF_8);
        Files.writeString(
                dir.resolve("src/main/java").resolve(pkg.replace('.', '/')).resolve("Index.java"),
                render(ENTRY, name).replace("{{pkg}}", pkg),
                StandardCharsets.UTF_8);
        Files.writeString(
                dir.resolve("src/main/java").resolve(pkg.replace('.', '/')).resolve("SamplePlugin.java"),
                render(SAMPLE, name).replace("{{pkg}}", pkg),
                StandardCharsets.UTF_8);
        return dir;
    }

    /** Converts a project name into a Java package ({@code my-app} → {@code my.app}). */
    public static String toPackage(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9.]+", ".");
    }

    /**
     * Creates a new plugin project in {@code target}/{@code name}. The
     * generated pom embeds the plugin contract: jcordis dependencies are
     * {@code provided} (the host supplies the framework), the jar stays clean
     * (plugin classes plus an SPI manifest), and the {@code check} goal is
     * bound to {@code verify}.
     */
    public static Path createPlugin(String name, Path target) throws IOException {
        String pkg = toPackage(name);
        Path dir = target.resolve(name);
        Files.createDirectories(dir);
        Files.createDirectories(dir.resolve("src/main/java").resolve(pkg.replace('.', '/')));
        Files.createDirectories(dir.resolve("src/main/resources/META-INF/services"));

        Files.writeString(
                dir.resolve("pom.xml"),
                render(PLUGIN_POM, name).replace("{{pkg}}", pkg),
                StandardCharsets.UTF_8);
        Files.writeString(
                dir.resolve("src/main/java").resolve(pkg.replace('.', '/')).resolve("SamplePlugin.java"),
                render(PLUGIN_SAMPLE, name).replace("{{pkg}}", pkg),
                StandardCharsets.UTF_8);
        Files.writeString(
                dir.resolve("src/main/resources/META-INF/services/io.jcordis.core.registry.Plugin"),
                pkg + ".SamplePlugin\n",
                StandardCharsets.UTF_8);
        return dir;
    }

    private static String render(String template, String name) {
        return template.replace("{{name}}", name);
    }

    private static final String PLUGIN_POM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
              <modelVersion>4.0.0</modelVersion>

              <groupId>io.jcordis.plugins</groupId>
              <artifactId>{{name}}</artifactId>
              <version>0.1.0-SNAPSHOT</version>
              <name>{{name}}</name>

              <properties>
                <maven.compiler.release>21</maven.compiler.release>
                <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
              </properties>

              <dependencies>
                <!-- jcordis API is provided: the host supplies the framework at runtime.
                     Third-party libraries must also be declared scope=provided so the
                     produced jar contains only this plugin's own classes. -->
                <dependency>
                  <groupId>io.jcordis</groupId>
                  <artifactId>jcordis-core</artifactId>
                  <version>0.1.0-SNAPSHOT</version>
                  <scope>provided</scope>
                </dependency>
                <dependency>
                  <groupId>io.jcordis</groupId>
                  <artifactId>jcordis-loader</artifactId>
                  <version>0.1.0-SNAPSHOT</version>
                  <scope>provided</scope>
                </dependency>
              </dependencies>

              <build>
                <plugins>
                  <!-- enforce the clean-jar contract at verify time -->
                  <plugin>
                    <groupId>io.jcordis</groupId>
                    <artifactId>jcordis-maven-plugin</artifactId>
                    <version>0.1.0-SNAPSHOT</version>
                    <executions>
                      <execution>
                        <goals>
                          <goal>check</goal>
                        </goals>
                      </execution>
                    </executions>
                  </plugin>
                </plugins>
              </build>
            </project>
            """;

    private static final String PLUGIN_SAMPLE = """
            package {{pkg}};

            import io.jcordis.core.context.Context;
            import io.jcordis.core.registry.Plugin;
            import io.jcordis.core.util.Disposable;

            /** Sample jcordis plugin: logs on load and unload. */
            public final class SamplePlugin implements Plugin {

                @Override
                public Object apply(Context ctx, Object config) {
                    ctx.logger("sample").info("sample plugin loaded");
                    return (Disposable) () -> ctx.logger("sample").info("sample plugin unloaded");
                }
            }
            """;

    private static final String POM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
              <modelVersion>4.0.0</modelVersion>

              <groupId>io.jcordis.app</groupId>
              <artifactId>{{name}}</artifactId>
              <version>0.1.0-SNAPSHOT</version>
              <name>{{name}}</name>

              <properties>
                <maven.compiler.release>21</maven.compiler.release>
                <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
              </properties>

              <dependencies>
                <dependency>
                  <groupId>io.jcordis</groupId>
                  <artifactId>jcordis-core</artifactId>
                  <version>0.1.0-SNAPSHOT</version>
                </dependency>
                <dependency>
                  <groupId>io.jcordis</groupId>
                  <artifactId>jcordis-loader</artifactId>
                  <version>0.1.0-SNAPSHOT</version>
                </dependency>
                <dependency>
                  <groupId>io.jcordis</groupId>
                  <artifactId>jcordis-plugin-timer</artifactId>
                  <version>0.1.0-SNAPSHOT</version>
                </dependency>
                <dependency>
                  <groupId>io.jcordis</groupId>
                  <artifactId>jcordis-plugin-logger-console</artifactId>
                  <version>0.1.0-SNAPSHOT</version>
                </dependency>
              </dependencies>

              <build>
                <plugins>
                  <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-shade-plugin</artifactId>
                    <version>3.5.2</version>
                    <executions>
                      <execution>
                        <phase>package</phase>
                        <goals><goal>shade</goal></goals>
                        <configuration>
                          <transformers>
                            <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                              <mainClass>{{pkg}}.Index</mainClass>
                            </transformer>
                          </transformers>
                        </configuration>
                      </execution>
                    </executions>
                  </plugin>
                </plugins>
              </build>
            </project>
            """;

    private static final String CONFIG = """
            # {{name}} application configuration
            # Loader entries: each entry loads a plugin by name from the classpath.
            plugins:
              - id: logger
                name: @cordisjs/plugin-logger-console
              - id: timer
                name: @cordisjs/plugin-timer
              - id: sample
                name: ./sample-plugin
            """;

    private static final String ENTRY = """
            package {{pkg}};

            import io.jcordis.core.context.Context;
            import io.jcordis.loader.Loader;

            /** Application entry point: boots the context tree and loader. */
            public final class Index {

                public static void main(String[] args) {
                    Context root = Context.create();
                    Loader loader = new Loader(root);
                    root.provide("loader", loader);
                    loader.builtin("@cordisjs/plugin-timer", (ctx, config) -> {
                        new io.jcordis.timer.TimerService(ctx);
                        return null;
                    });
                    loader.builtin("@cordisjs/plugin-logger-console", (ctx, config) -> {
                        new io.jcordis.logger.console.ConsoleExporter(ctx);
                        return null;
                    });
                    loader.mock("./sample-plugin", new {{pkg}}.SamplePlugin());

                    loader.read(java.util.List.of(loaderEntry("sample", "./sample-plugin")));
                    root.logger("{{name}}").info("started");
                }

                static io.jcordis.loader.EntryOptions loaderEntry(String id, String name) {
                    io.jcordis.loader.EntryOptions options = new io.jcordis.loader.EntryOptions();
                    options.id = id;
                    options.name = name;
                    return options;
                }
            }
            """;

    private static final String SAMPLE = """
            package {{pkg}};

            import io.jcordis.core.context.Context;
            import io.jcordis.core.registry.Plugin;
            import io.jcordis.core.util.Disposable;

            /** Sample plugin: logs on load and unload. */
            public final class SamplePlugin implements Plugin {

                @Override
                public Object apply(Context ctx, Object config) {
                    ctx.logger("sample").info("sample plugin loaded");
                    return (Disposable) () -> ctx.logger("sample").info("sample plugin unloaded");
                }
            }
            """;
}
