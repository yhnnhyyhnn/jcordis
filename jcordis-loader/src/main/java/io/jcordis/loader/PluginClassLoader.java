package io.jcordis.loader;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

/**
 * Per-plugin class loader, mirroring the module cache of Cordis's HMR.
 *
 * <p>Each plugin jar gets its own instance, so discarding it (unload) releases
 * every class it defined. Parent-first delegation (the {@link URLClassLoader}
 * default) keeps framework classes and third-party libraries single-instance:
 * their versions are always the host's.
 */
public final class PluginClassLoader extends URLClassLoader {

    public PluginClassLoader(Path jar, ClassLoader parent) {
        super(new URL[] {toUrl(jar)}, parent);
    }

    private static URL toUrl(Path jar) {
        try {
            return jar.toUri().toURL();
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("invalid plugin jar path: " + jar, e);
        }
    }
}
