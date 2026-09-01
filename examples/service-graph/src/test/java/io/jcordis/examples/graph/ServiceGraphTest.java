package io.jcordis.examples.graph;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import io.jcordis.core.service.ServiceKey;
import org.junit.jupiter.api.Test;

/** Demonstrates service provision, dependency injection and isolation realms. */
class ServiceGraphTest {

    @Test
    void providerServesInjector() {
        Context root = Context.create();
        new DatabaseService(root);

        java.util.Map<String, Object> inject = new java.util.HashMap<>();
        inject.put("database", null);
        Plugin app = Plugin.object("app", inject, (ctx, config) -> {
            DatabaseService db = (DatabaseService) ctx.get("database");
            db.connect();
            return (io.jcordis.core.util.Disposable) db::disconnect;
        });
        io.jcordis.core.fiber.Fiber fiber = root.plugin(app).await().join();

        DatabaseService db = (DatabaseService) root.get("database");
        assertThat(db.connections()).isEqualTo(1);
        fiber.disposeAsync().join();
        assertThat(db.connections()).isZero();
    }

    @Test
    void isolatedRealmsDoNotShareServices() {
        Context root = Context.create();
        new DatabaseService(root);

        // isolated realm: its own database service under a unique key
        Context isolated = root.isolate("database");
        ServiceKey<?> isolatedKey = isolated.isolateKey("database");
        assertThat(isolatedKey).isNotNull();
        assertThat(isolatedKey).isNotEqualTo(ServiceKey.of("database"));

        // root's database is invisible to the isolated realm
        DatabaseService db = (DatabaseService) root.get("database");
        db.connect();
        assertThat(db.connections()).isEqualTo(1);
    }

    @Test
    void sharedLabelSharesRealm() {
        Context root = Context.create();
        ServiceKey<?> label = ServiceKey.unique("shared");
        Context a = root.isolate("svc", label);
        Context b = root.isolate("svc", label);
        assertThat(a.isolateKey("svc")).isSameAs(b.isolateKey("svc"));

        a.provide("svc", "value");
        assertThat(b.<String>get("svc")).isEqualTo("value");
        assertThat(root.<String>get("svc")).isNull();
    }
}
