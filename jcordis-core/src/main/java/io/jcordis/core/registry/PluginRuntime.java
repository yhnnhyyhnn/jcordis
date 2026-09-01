package io.jcordis.core.registry;

import io.jcordis.core.fiber.Fiber;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Per-plugin registration state, mirroring Cordis's {@code Plugin.Runtime}.
 */
public final class PluginRuntime {

    private final String name;
    private final Plugin callback;
    private final List<Fiber> fibers = new CopyOnWriteArrayList<>();

    public PluginRuntime(String name, Plugin callback) {
        this.name = name;
        this.callback = callback;
    }

    /** The plugin name (may be {@code null}). */
    public String name() {
        return name;
    }

    /** The resolved plugin body, serving as the registry key. */
    public Plugin callback() {
        return callback;
    }

    /** All live fibers created from this plugin. */
    public List<Fiber> fibers() {
        return fibers;
    }
}
