package io.jcordis.examples.graph;

import io.jcordis.core.context.Context;
import io.jcordis.core.service.Service;

/** Database service: provides connections and exposes a counter. */
public final class DatabaseService extends Service {

    private int connections;

    public DatabaseService(Context ctx) {
        super(ctx, "database");
    }

    public int connections() {
        return connections;
    }

    public void connect() {
        connections++;
    }

    public void disconnect() {
        connections--;
    }
}