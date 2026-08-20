package io.jcordis.core.registry;

import io.jcordis.core.context.Context;
import java.lang.reflect.Constructor;
import java.util.Map;

/**
 * A plugin, mirroring Cordis's {@code Plugin} union (function / constructor /
 * object-with-apply).
 *
 * <p>The three TypeScript plugin forms collapse into a single
 * {@code apply(ctx, config)} entry point; class plugins perform construction
 * and {@code [Service.init]} inside {@link #apply}. Metadata defaults keep
 * plain lambdas usable.
 */
@FunctionalInterface
public interface Plugin {

    /**
     * Applies the plugin body, optionally returning a disposable that reverts
     * its side effects (or a {@code CompletableFuture} of one).
     */
    Object apply(Context ctx, Object config);

    /** Plugin name, used for the fiber/context label. */
    default String name() {
        return null;
    }

    /** Declared service dependencies ({@code name -> config} or {@code null}). */
    default Map<String, Object> inject() {
        return Map.of();
    }

    /** Wraps a class plugin: instantiate with ctx/config and run its init hook. */
    static Plugin constructor(Class<?> clazz) {
        return new Plugin() {
            @Override
            public Object apply(Context ctx, Object config) {
                Object instance = instantiate(clazz, ctx, config);
                return instance instanceof Initializable initializable ? initializable.init() : null;
            }

            @Override
            public String name() {
                return clazz.getSimpleName();
            }
        };
    }

    /** Wraps an object plugin with explicit name and inject declarations. */
    static Plugin object(String name, Map<String, Object> inject, Plugin apply) {
        return new Plugin() {
            @Override
            public Object apply(Context ctx, Object config) {
                return apply.apply(ctx, config);
            }

            @Override
            public String name() {
                return name;
            }

            @Override
            public Map<String, Object> inject() {
                return inject;
            }
        };
    }

    private static Object instantiate(Class<?> clazz, Context ctx, Object config) {
        try {
            for (Constructor<?> ctor : clazz.getDeclaredConstructors()) {
                if (ctor.getParameterCount() == 2
                        && ctor.getParameterTypes()[0] == Context.class
                        && ctor.getParameterTypes()[1] == Object.class) {
                    ctor.setAccessible(true);
                    return ctor.newInstance(ctx, config);
                }
            }
            for (Constructor<?> ctor : clazz.getDeclaredConstructors()) {
                if (ctor.getParameterCount() == 1 && ctor.getParameterTypes()[0] == Context.class) {
                    ctor.setAccessible(true);
                    return ctor.newInstance(ctx);
                }
            }
            Constructor<?> ctor = clazz.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("invalid plugin " + clazz.getName(), e);
        }
    }
}