package io.jcordis.core.logger;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import io.jcordis.core.service.Service;
import io.jcordis.core.util.Disposable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Translates Cordis logger.spec.ts: bounded buffer, exporters, name derivation. */
class LoggerTest {

    private static final String EVENT = "custom-event";

    /** Builds an inject map (values may be null, unlike {@code Map.of}). */
    private static Map<String, Object> inject(String... names) {
        Map<String, Object> map = new java.util.HashMap<>();
        for (String name : names) {
            map.put(name, null);
        }
        return map;
    }

    /** Captures messages via a custom exporter at DEBUG level. */
    private static List<Message> setup(Context ctx) {
        List<Message> captured = new ArrayList<>();
        ctx.loggerService()
                .exporter(new Exporter() {
                    @Override
                    public void export(Message message) {
                        captured.add(message);
                    }

                    @Override
                    public Map<String, Integer> levels() {
                        return Map.of("default", 3);
                    }
                });
        return captured;
    }

    @Test
    void keepsBoundedBuffer() {
        Context ctx = Context.create();
        List<Message> buffer = ctx.loggerService().buffer();
        ctx.loggerService().setBufferSize(2);
        ctx.logger().info("one");
        ctx.logger().info("two");
        ctx.logger().info("three");
        assertThat(buffer).isSameAs(ctx.loggerService().buffer());
        assertThat(buffer.stream().map(m -> m.args()[0]).toList()).containsExactly("two", "three");

        ctx.loggerService().setBufferSize(1);
        ctx.logger().info("four");
        assertThat(buffer.stream().map(m -> m.args()[0]).toList()).containsExactly("four");

        ctx.loggerService().setBufferSize(0);
        ctx.logger().info("five");
        assertThat(buffer).isEmpty();
    }

    @Test
    void disposesExporter() {
        Context ctx = Context.create();
        ctx.loggerService().exporters().clear();
        List<Message> first = new ArrayList<>();
        List<Message> second = new ArrayList<>();
        Disposable disposeFirst = ctx.loggerService()
                .exporter(new Exporter() {
                    @Override
                    public void export(Message message) {
                        first.add(message);
                    }
                });
        Disposable disposeSecond = ctx.loggerService()
                .exporter(new Exporter() {
                    @Override
                    public void export(Message message) {
                        second.add(message);
                    }
                });

        disposeFirst.dispose();
        ctx.logger().info("test");
        assertThat(first).isEmpty();
        assertThat(second).hasSize(1);

        disposeSecond.dispose();
        ctx.logger().info("test");
        assertThat(second).hasSize(1);
    }

    @Test
    void usesFiberNameOutsideService() {
        Context ctx = Context.create();
        List<Message> captured = setup(ctx);
        ctx.logger().debug("hello");
        assertThat(captured.stream().map(Message::name).toList()).containsExactly("root");
    }

    @Test
    void honoursExplicitName() {
        Context ctx = Context.create();
        List<Message> captured = setup(ctx);
        ctx.logger("custom").debug("hello");
        assertThat(captured.stream().map(Message::name).toList()).containsExactly("custom");
    }

    @Test
    void honoursInterceptName() {
        Context ctx = Context.create();
        List<Message> captured = setup(ctx);
        Context intercepted = ctx.intercept("logger", Map.of("name", "intercepted"));
        intercepted.logger().debug("hello");
        assertThat(captured.stream().map(Message::name).toList()).containsExactly("intercepted");
    }

    @Test
    void usesServiceNameFromInsideServiceMethod() {
        Context ctx = Context.create();
        List<Message> captured = setup(ctx);

        Plugin foo = Plugin.object("foo:driver", Map.of(), (pluginCtx, config) -> {
            new FooService(pluginCtx);
            return null;
        });
        ctx.plugin(foo).await().join();

        ((FooService) ctx.get("foo")).action();
        assertThat(captured.stream().map(Message::name).toList()).contains("foo:driver");
        assertThat(captured.stream().map(Message::name).toList()).doesNotContain("root");
    }

    @Test
    void usesServiceNameFromInsideInit() {
        Context ctx = Context.create();
        List<Message> captured = setup(ctx);

        Plugin foo = Plugin.object("foo:driver", Map.of(), (pluginCtx, config) -> {
            FooService service = new FooService(pluginCtx);
            service.logFromInit();
            return null;
        });
        ctx.plugin(foo).await().join();

        assertThat(captured.stream().map(Message::name).toList()).contains("foo:driver");
    }

    /** Service whose methods log via the context logger. */
    static class FooService extends Service {
        FooService(Context ctx) {
            super(ctx, "foo");
        }

        void action() {
            ctx.logger().debug("from action");
        }

        void logFromInit() {
            ctx.logger().debug("from init");
        }
    }

    @Test
    void innermostServiceName() {
        Context ctx = Context.create();
        List<Message> captured = setup(ctx);

        Plugin bar = Plugin.object("bar:driver", Map.of(), (pluginCtx, config) -> {
            new BarService(pluginCtx);
            return null;
        });
        ctx.plugin(bar).await().join();

        Plugin foo = Plugin.object("foo:driver", inject("bar"), (pluginCtx, config) -> {
            new FooWithBar(pluginCtx);
            return null;
        });
        ctx.plugin(foo).await().join();

        ctx.inject(List.of("foo"), (injectCtx, config) -> {
            ((FooWithBar) injectCtx.get("foo")).action();
            return null;
        }).await().join();

        assertThat(captured.stream().map(m -> m.name() + ":" + m.args()[0]).toList())
                .containsExactly("bar:driver:from bar", "foo:driver:from foo");
    }

    /** Bar service providing the innermost log. */
    static class BarService extends Service {
        BarService(Context ctx) {
            super(ctx, "bar");
        }

        void action() {
            ctx.logger().debug("from bar");
        }
    }

    /** Foo service delegating to bar then logging itself. */
    static class FooWithBar extends Service {
        FooWithBar(Context ctx) {
            super(ctx, "foo");
        }

        void action() {
            ((BarService) ctx.get("bar")).action();
            ctx.logger().debug("from foo");
        }
    }
}