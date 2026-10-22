package io.jcordis.loader;

import java.io.IOException;
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

    private final Runnable onClose;

    /** Loads a plugin from its jar file. */
    public PluginClassLoader(Path jar, ClassLoader parent) {
        this(new URL[] {toUrl(jar)}, parent, null);
    }

    /**
     * Loads a plugin from arbitrary classpath roots (jar files or class
     * directories), with an optional cleanup run when the loader closes
     * (compiled source directories are throwaway).
     */
    public PluginClassLoader(URL[] urls, ClassLoader parent, Runnable onClose) {
        super(urls, parent);
        this.onClose = onClose;
    }

    @Override
    public void close() throws IOException {
        try {
            super.close();
        } finally {
            if (onClose != null) {
                onClose.run();
            }
        }
    }

    private static URL toUrl(Path jar) {
        try {
            return jar.toUri().toURL();
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("invalid plugin jar path: " + jar, e);
        }
    }
}
