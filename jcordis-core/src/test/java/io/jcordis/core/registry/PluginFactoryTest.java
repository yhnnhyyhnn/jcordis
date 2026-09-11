package io.jcordis.core.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jcordis.core.context.Context;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Boundary coverage for the class-plugin factory: constructor selection order,
 * the initializable hook, caching and the invalid-plugin error path.
 *
 * <p>The wrapper returns the hook result (not the instance), so each fixture
 * records what happened in static probes.
 */
class PluginFactoryTest {

    /** Plugin with the preferred two-argument constructor. */
    public static final class TwoArgPlugin implements Plugin {
        static final AtomicInteger CONSTRUCTED = new AtomicInteger();
        static volatile Context ctx;
        static volatile Object config;

        public TwoArgPlugin(Context ctx, Object config) {
            CONSTRUCTED.incrementAndGet();
            TwoArgPlugin.ctx = ctx;
            TwoArgPlugin.config = config;
        }

        @Override
        public Object apply(Context ctx, Object config) {
            return null;
        }
    }

    /** Plugin with only the context constructor. */
    public static final class OneArgPlugin implements Plugin {
        static final AtomicInteger CONSTRUCTED = new AtomicInteger();
        static volatile Context ctx;

        public OneArgPlugin(Context ctx) {
            CONSTRUCTED.incrementAndGet();
            OneArgPlugin.ctx = ctx;
        }

        @Override
        public Object apply(Context ctx, Object config) {
            return null;
        }
    }

    /** Plugin with a private no-argument constructor. */
    public static final class NoArgPlugin implements Plugin {
        static final AtomicInteger CONSTRUCTED = new AtomicInteger();

        private NoArgPlugin() {
            CONSTRUCTED.incrementAndGet();
        }

        @Override
        public Object apply(Context ctx, Object config) {
            return null;
        }
    }

    /** Plugin with an initializable hook. */
    public static final class InitializingPlugin implements Plugin, Initializable {
        static final AtomicInteger INITIALIZED = new AtomicInteger();

        public InitializingPlugin() {}

        @Override
        public Object apply(Context ctx, Object config) {
            return null;
        }

        @Override
        public Object init() {
            INITIALIZED.incrementAndGet();
            return null;
        }
    }

    /** Plugin without a usable constructor. */
    public static final class BrokenPlugin implements Plugin {
        public BrokenPlugin(String ignored) {}

        @Override
        public Object apply(Context ctx, Object config) {
            return null;
        }
    }

    @Test
    void twoArgumentConstructorShouldWinOverTheOthers() {
        Context root = Context.create();
        TwoArgPlugin.CONSTRUCTED.set(0);
        Plugin plugin = Plugin.constructor(TwoArgPlugin.class);

        plugin.apply(root, Map.of("v", 1));

        assertThat(TwoArgPlugin.CONSTRUCTED).hasValue(1);
        assertThat(TwoArgPlugin.ctx).isSameAs(root);
        assertThat(TwoArgPlugin.config).isEqualTo(Map.of("v", 1));
    }

    @Test
    void contextConstructorShouldBeUsedWhenThereIsNoTwoArgumentOne() {
        Context root = Context.create();
        OneArgPlugin.CONSTRUCTED.set(0);
        Plugin plugin = Plugin.constructor(OneArgPlugin.class);

        plugin.apply(root, Map.of());

        assertThat(OneArgPlugin.CONSTRUCTED).hasValue(1);
        assertThat(OneArgPlugin.ctx).isSameAs(root);
    }

    @Test
    void privateNoArgumentConstructorShouldBeMadeAccessible() {
        NoArgPlugin.CONSTRUCTED.set(0);
        Plugin plugin = Plugin.constructor(NoArgPlugin.class);

        plugin.apply(Context.create(), null);

        assertThat(NoArgPlugin.CONSTRUCTED).hasValue(1);
    }

    @Test
    void initializableHookShouldRunAfterInstantiation() {
        Context root = Context.create();
        InitializingPlugin.INITIALIZED.set(0);
        Plugin plugin = Plugin.constructor(InitializingPlugin.class);

        plugin.apply(root, null);

        assertThat(InitializingPlugin.INITIALIZED).as("init() ran once").hasValue(1);
    }

    @Test
    void constructorShouldBeCachedPerClass() {
        assertThat(Plugin.constructor(OneArgPlugin.class))
                .as("the same wrapper is reused so runtimes key on the class")
                .isSameAs(Plugin.constructor(OneArgPlugin.class));
        assertThat(Plugin.constructor(OneArgPlugin.class).name()).isEqualTo("OneArgPlugin");
    }

    @Test
    void pluginWithoutAUsableConstructorShouldFail() {
        Context root = Context.create();
        Plugin plugin = Plugin.constructor(BrokenPlugin.class);

        assertThatThrownBy(() -> plugin.apply(root, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid plugin");
    }
}
