package io.jcordis.all;

/**
 * Marker class of the {@code jcordis-all} aggregate artifact.
 *
 * <p>jcordis-all is a shaded bundle: it repackages the runtime framework —
 * {@code jcordis-core} (context, fiber/effect lifecycle, event bus, registry,
 * logger) and {@code jcordis-loader} (declarative entry tree, isolation
 * realms, plugin-jar hot swap) — plus the {@code jcordis-cli} scaffolder,
 * into a single jar. A business system consumes it with one coordinate:
 *
 * <pre>{@code
 * <dependency>
 *   <groupId>io.github.yhnnhyyhnn</groupId>
 *   <artifactId>jcordis-all</artifactId>
 *   <version>1.0.2-SNAPSHOT</version>
 * </dependency>
 * }</pre>
 *
 * <p>Third-party libraries (Jackson, SLF4J) are deliberately <em>not</em>
 * shaded; they are provided transitively through the published pom.
 *
 * <p>This marker class exists so the aggregate module publishes a proper
 * sources/javadoc jar — the framework classes themselves live in the
 * individual modules (and inside the shaded jar as {@code io.jcordis.*}).
 *
 * @since 1.0.1
 */
public final class JcordisAll {

    /** The jcordis runtime version bundled by this aggregate artifact. */
    public static final String VERSION = "1.0.2-SNAPSHOT";

    private JcordisAll() {}
}
