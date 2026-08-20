package io.jcordis.group;

import io.jcordis.loader.Loader;

/**
 * Group plugin, mirroring {@code @cordisjs/plugin-group}.
 *
 * <p>Re-exports the loader's group marker plugin: entries referencing this
 * plugin become group containers whose config list spawns child entries.
 */
public final class GroupPlugin {

    public static final io.jcordis.core.registry.Plugin INSTANCE = Loader.GROUP_PLUGIN;

    private GroupPlugin() {}
}