package io.jcordis.maven;

import io.jcordis.cli.Scaffolder;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Generates a new jcordis plugin project, embedding the plugin contract: the
 * produced pom declares jcordis dependencies as {@code provided}, ships an SPI
 * manifest, and binds the {@code check} goal to {@code verify}.
 *
 * <p>Facade over {@link Scaffolder#createPlugin}. Usage:
 * <pre>{@code mvn io.jcordis:jcordis-maven-plugin:create-plugin -Dname=demo-plugin}</pre>
 */
@Mojo(name = "create-plugin", requiresProject = false)
public class CreatePluginMojo extends AbstractMojo {

    /** The plugin name (also the artifactId and package name). */
    @Parameter(property = "name", required = true)
    private String name;

    /** The parent directory under which the plugin project is created. */
    @Parameter(property = "target", defaultValue = ".")
    private File target;

    @Override
    public void execute() throws MojoExecutionException {
        Path dir;
        try {
            dir = Scaffolder.createPlugin(name, target.toPath());
        } catch (IOException e) {
            throw new MojoExecutionException("failed to scaffold plugin " + name, e);
        }
        getLog().info("created plugin project at " + dir);
    }
}
