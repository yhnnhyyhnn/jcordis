package io.jcordis.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Enforces the clean-jar contract: the built plugin jar must contain no
 * third-party or framework classes (jcordis and libraries are supplied by the
 * host at runtime). Bound to {@code verify} in scaffolds created by
 * {@code create-plugin}.
 */
@Mojo(name = "check", defaultPhase = LifecyclePhase.VERIFY, requiresProject = true)
public class CheckMojo extends AbstractMojo {

    /** Class-name prefixes that must never appear inside a plugin jar. */
    private static final String[] FORBIDDEN_PREFIXES = {
        "com/fasterxml/", "org/slf4j/", "org/junit/", "org/assertj/",
        "org/apache/maven/", "io/jcordis/",
    };

    @Parameter(defaultValue = "${project.build.directory}", readonly = true, required = true)
    private File buildDirectory;

    @Parameter(defaultValue = "${project.build.finalName}", readonly = true, required = true)
    private String finalName;

    @Override
    public void execute() throws MojoExecutionException {
        Path jar = buildDirectory.toPath().resolve(finalName + ".jar");
        if (!Files.exists(jar)) {
            throw new MojoExecutionException("plugin jar not found: " + jar + " — run `mvn package` first");
        }
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                for (String prefix : FORBIDDEN_PREFIXES) {
                    if (name.startsWith(prefix) && name.endsWith(".class")) {
                        throw new MojoExecutionException("plugin jar contains third-party class " + name
                                + " — declare it scope=provided so the host supplies it");
                    }
                }
            }
        } catch (IOException e) {
            throw new MojoExecutionException("cannot inspect plugin jar " + jar, e);
        }
        getLog().info("plugin jar is clean: contains no third-party classes");
    }
}
