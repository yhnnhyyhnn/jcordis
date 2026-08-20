package io.jcordis.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import io.jcordis.core.fiber.Fiber;
import io.jcordis.core.logger.Logger;
import io.jcordis.core.registry.Plugin;
import io.jcordis.core.service.Service;
import io.jcordis.core.util.Disposable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * End-to-end demo: plugin loading, service provision, dependency response,
 * and unload rollback — mirroring Cordis's core composability guarantees.
 */
class E2eDemoTest {

    private static final String EVENT = "custom-event";

    /** Builds an inject map (values may be null, unlike {@code Map.of}). */
    private static java.util.Map<String, Object> inject(String... names) {
        java.util.Map<String, Object> map = new java.util.HashMap<>();
        for (String name : names) {
            map.put(name, null);
        }
        return map;
    }

    /** Database service: tracks connections, closes them on effect disposal. */
    static class Database extends Service {
        final List<String> connections = new ArrayList<>();

        Database(Context ctx) {
            super(ctx, "database");
        }

        Disposable connect(Context caller, String name) {
            return caller.effect(r -> {
                connections.add(name);
                return io.jcordis.core.fiber.EffectResult.of(() -> connections.remove(name));
            }, "connect(" + name + ")");
        }
    }

    @Test
    void pluginLoadsProvidesRespondsAndRollsBack() {
        Context root = Context.create();
        List<String> log = new ArrayList<>();

        // 1. database service registered on root (revertible)
        Database database = new Database(root);
        log.add("database-provided");

        // 2. application plugin depends on database; registers an event hook
        AtomicInteger events = new AtomicInteger();
        Plugin app = Plugin.object("app", inject("database"), (ctx, config) -> {
            Database db = (Database) ctx.get("database");
            db.connect(ctx, "app-connection");
            ctx.on(EVENT, (thisArg, args) -> {
                events.incrementAndGet();
                return null;
            });
            log.add("app-loaded");
            return (Disposable) () -> log.add("app-unloaded");
        });

        // 3. a nested plugin forms a chain (disposed with its parent)
        Plugin reporter = Plugin.object("reporter", java.util.Map.of(), (ctx, config) -> {
            log.add("reporter-loaded");
            return (Disposable) () -> log.add("reporter-unloaded");
        });
        Plugin host = Plugin.object("host", inject("database"), (ctx, config) -> {
            ctx.plugin(reporter).await().join();
            return null;
        });

        Fiber appFiber = root.plugin(app).await().join();
        Fiber hostFiber = root.plugin(host).await().join();
        assertThat(log).contains("database-provided", "app-loaded", "reporter-loaded");
        assertThat(database.connections).containsExactly("app-connection");

        // 4. events dispatch to registered hooks
        root.emit(EVENT);
        assertThat(events).hasValue(1);

        // 5. unloading the host fiber cascades to the nested reporter
        hostFiber.disposeAsync().join();
        assertThat(log).contains("reporter-unloaded");

        // 6. unloading the app fiber rolls back hooks and releases the connection
        appFiber.disposeAsync().join();
        root.emit(EVENT);
        assertThat(events).hasValue(1);
        assertThat(database.connections).isEmpty();
        assertThat(log).contains("app-unloaded");

        // 7. re-loading the app restores everything
        Fiber second = root.plugin(app).await().join();
        assertThat(database.connections).containsExactly("app-connection");
        root.emit(EVENT);
        assertThat(events).hasValue(2);

        // 8. teardown rolls everything back
        second.disposeAsync().join();
        assertThat(database.connections).isEmpty();
        assertThat(root.registry().size()).isZero();
        root.emit(EVENT);
        assertThat(events).hasValue(2);
    }

    @Test
    void loggerIntegratedWithPluginLifecycle() {
        Context root = Context.create();
        List<String> captured = new ArrayList<>();
        root.loggerService()
                .exporter(new io.jcordis.core.logger.Exporter() {
                    @Override
                    public void export(io.jcordis.core.logger.Message message) {
                        captured.add(message.name() + ":" + message.args()[0]);
                    }

                    @Override
                    public java.util.Map<String, Integer> levels() {
                        return java.util.Map.of("default", 3);
                    }
                });

        Plugin app = Plugin.object("my-app", java.util.Map.of(), (ctx, config) -> {
            Logger logger = ctx.logger();
            logger.debug("started");
            return (Disposable) () -> logger.debug("stopped");
        });

        Fiber fiber = root.plugin(app).await().join();
        assertThat(captured).contains("my-app:started");

        fiber.disposeAsync().join();
        assertThat(captured).contains("my-app:started", "my-app:stopped");
    }
}