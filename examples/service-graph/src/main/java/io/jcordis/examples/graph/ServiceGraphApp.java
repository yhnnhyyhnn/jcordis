package io.jcordis.examples.graph;

import io.jcordis.core.context.Context;
import io.jcordis.core.logger.ConsoleExporter;
import io.jcordis.loader.EntryOptions;
import io.jcordis.loader.Loader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runnable demo of the service graph: a database plugin provides a service,
 * an app plugin depends on it (declared inject), and removing the provider
 * unloads the dependent automatically (dependency response).
 *
 * <p>Run with {@code mvn -pl examples/service-graph exec:java}.
 */
public final class ServiceGraphApp {

    public static void main(String[] args) {
        Context root = Context.create();
        new ConsoleExporter(root);
        Loader loader = new Loader(root);

        loader.builtin("database", (ctx, config) -> {
            new DatabaseService(ctx);
            return null;
        });
        loader.builtin("app", new AppPlugin());

        EntryOptions database = new EntryOptions();
        database.id = "db";
        database.name = "database";

        EntryOptions app = new EntryOptions();
        app.id = "app";
        app.name = "app";
        Map<String, Object> inject = new HashMap<>();
        inject.put("database", null);
        app.inject = inject;

        loader.read(List.of(database, app));

        DatabaseService service = (DatabaseService) root.get("database");
        System.out.println("app connected; database connections = " + service.connections());

        System.out.println("--- removing the database provider ---");
        loader.remove("db");

        // the app plugin was unloaded with its dependency (dependency response);
        // a fresh app entry would not activate until 'database' returns
        System.out.println("app fiber state after provider removal = "
                + loader.expectFiber("app").state());
        root.fiber().disposeAsync().join();
    }

    private ServiceGraphApp() {}
}
