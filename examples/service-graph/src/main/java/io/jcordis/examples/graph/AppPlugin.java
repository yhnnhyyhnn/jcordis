package io.jcordis.examples.graph;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;

/** Application plugin depending on the database service. */
public final class AppPlugin implements Plugin {

    @Override
    public Object apply(Context ctx, Object config) {
        DatabaseService database = (DatabaseService) ctx.get("database");
        database.connect();
        return (io.jcordis.core.util.Disposable) database::disconnect;
    }
}