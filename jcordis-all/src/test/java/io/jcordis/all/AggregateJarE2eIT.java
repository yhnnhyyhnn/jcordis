package io.jcordis.all;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * End-to-end: the shaded aggregate jar must be usable standalone — exactly as
 * a business system consumes it (one coordinate + transitively provided
 * third-party libs). Classes are loaded from the jar via an isolated
 * {@link URLClassLoader} whose parent is the platform loader (no jcordis on
 * the parent), and the loader flow is driven reflectively.
 */
class AggregateJarE2eIT {

    private static final Path JAR = Path.of("target", "jcordis-all-1.0.1-SNAPSHOT.jar");

    @Test
    void aggregatedJar_shouldRunLoaderFlowStandalone() throws Exception {
        assertThat(JAR).exists();

        List<URL> urls = new ArrayList<>();
        urls.add(JAR.toUri().toURL());
        // third-party deps are NOT shaded; provide them as a business app would
        String classpath = System.getProperty("java.class.path");
        for (String entry : classpath.split(Pattern.quote(System.getProperty("path.separator")))) {
            if (entry.contains("jackson") || entry.contains("snakeyaml") || entry.contains("slf4j")) {
                urls.add(Path.of(entry).toUri().toURL());
            }
        }
        assertThat(urls).anyMatch(u -> u.getPath().contains("jackson"));

        try (URLClassLoader isolated =
                new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader())) {
            Class<?> contextClass = Class.forName("io.jcordis.core.context.Context", true, isolated);
            Class<?> loaderClass = Class.forName("io.jcordis.loader.Loader", true, isolated);
            Class<?> pluginClass = Class.forName("io.jcordis.core.registry.Plugin", true, isolated);
            Class<?> optionsClass = Class.forName("io.jcordis.loader.EntryOptions", true, isolated);

            // every class must come from the aggregate jar, not the test classpath
            assertThat(contextClass.getProtectionDomain().getCodeSource().getLocation())
                    .isEqualTo(JAR.toUri().toURL());

            Object ctx = contextClass.getMethod("create").invoke(null);
            Object loader = loaderClass.getConstructor(contextClass).newInstance(ctx);

            // a demo plugin implemented reflectively (Proxy over Plugin)
            Object plugin = Proxy.newProxyInstance(
                    isolated, new Class<?>[] {pluginClass}, (proxy, method, args) -> switch (method.getName()) {
                        case "apply" -> null;
                        case "name" -> "demo";
                        case "inject" -> Map.of();
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "toString" -> "demo-plugin-proxy";
                        case "equals" -> proxy == args[0];
                        default -> null;
                    });
            loaderClass.getMethod("mock", String.class, pluginClass).invoke(loader, "demo", plugin);

            Object options = optionsClass.getConstructor().newInstance();
            optionsClass.getField("id").set(options, "a");
            optionsClass.getField("name").set(options, "demo");
            loaderClass.getMethod("read", List.class).invoke(loader, List.of(options));

            Method expectFiber = loaderClass.getMethod("expectFiber", String.class);
            assertThat(expectFiber.invoke(loader, "a"))
                    .as("entry fiber loaded from the jar")
                    .isNotNull();
            Method entries = loaderClass.getMethod("entries");
            assertThat((List<?>) entries.invoke(loader)).hasSize(1);
        }
    }
}
