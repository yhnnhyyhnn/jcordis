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
 * Generates a new jcordis application scaffold, mirroring {@code create-cordis}.
 *
 * <p>Facade over {@link Scaffolder}: runs without a project context, so it can
 * be invoked anywhere. Usage:
 * <pre>{@code mvn io.jcordis:jcordis-maven-plugin:create -Dname=my-app [-Dtarget=.]}</pre>
 */
@Mojo(name = "create", requiresProject = false)
public class CreateMojo extends AbstractMojo {

    /** The project name (also the artifactId and package name). */
    @Parameter(property = "name", required = true)
    private String name;

    /** The parent directory under which the project is created. */
    @Parameter(property = "target", defaultValue = ".")
    private File target;

    @Override
    public void execute() throws MojoExecutionException {
        Path dir;
        try {
            dir = Scaffolder.create(name, target.toPath());
        } catch (IOException e) {
            throw new MojoExecutionException("failed to scaffold project " + name, e);
        }
        getLog().info("created project at " + dir);
    }
}
