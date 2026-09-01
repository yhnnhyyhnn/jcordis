package io.jcordis.core.logger;

import io.jcordis.core.context.Context;
import io.jcordis.core.fiber.EffectResult;
import io.jcordis.core.util.Disposable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Logger service of a context tree, mirroring Cordis's {@code LoggerService}.
 *
 * <p>Keeps a bounded chronological {@link #buffer} (fed by a built-in
 * exporter), allows registering custom {@link Exporter}s via
 * {@link #exporter} (revertible through the fiber effect system), and derives
 * logger names from the context's {@code logger} intercept chain, falling
 * back to the fiber name.
 */
public final class LoggerService {

    private final Context ctx;
    private final List<Message> buffer = new ArrayList<>();
    private final Map<Integer, Exporter> exporters = new ConcurrentHashMap<>();

    private volatile int bufferSize = 1000;
    private volatile int snMessage;
    private volatile int snExporter;

    public LoggerService(Context ctx) {
        this.ctx = ctx;
        exporter(new Exporter() {
            @Override
            public void export(Message message) {
                buffer.add(message);
                int overflow = buffer.size() - bufferSize;
                if (overflow == 1) {
                    buffer.remove(0);
                } else if (overflow > 1) {
                    buffer.subList(0, overflow).clear();
                }
            }
        });
    }

    public List<Message> buffer() {
        return buffer;
    }

    public int bufferSize() {
        return bufferSize;
    }

    public void setBufferSize(int bufferSize) {
        this.bufferSize = bufferSize;
    }

    public Map<Integer, Exporter> exporters() {
        return exporters;
    }

    /** Registers an exporter, returning a disposable that unregisters it. */
    public Disposable exporter(Exporter exporter) {
        return ctx.fiber()
                .effect(
                        runner -> {
                            int id = ++snExporter;
                            exporters.put(id, exporter);
                            return EffectResult.of(() -> exporters.remove(id));
                        },
                        "ctx.logger.exporter()");
    }

    /** Returns a logger named after the context's intercept chain or fiber. */
    public Logger named(Context source) {
        String name = resolveName(source);
        return new Logger(name, this);
    }

    /** Returns a logger with an explicit name. */
    public Logger named(String name) {
        return new Logger(name, this);
    }

    private String resolveName(Context source) {
        Context cursor = source;
        while (cursor != null) {
            Object config = cursor.interceptConfig("logger");
            if (config instanceof Map<?, ?> map && map.get("name") != null) {
                return (String) map.get("name");
            }
            cursor = cursor.parent();
        }
        return source.fiber().name();
    }

    void log(String type, int level, String name, Object... args) {
        if (args.length == 1 && args[0] instanceof io.jcordis.core.event.AggregateError aggregate) {
            for (Throwable error : aggregate.errors()) {
                log(type, level, name, error);
            }
            return;
        }
        int sn = ++snMessage;
        long ts = System.currentTimeMillis();
        for (Exporter exporter : exporters.values()) {
            Integer threshold = exporter.levels().get(name);
            if (threshold == null) {
                threshold = exporter.levels().getOrDefault("default", LoggerLevel.INFO.value());
            }
            if (threshold < level) continue;
            exporter.export(new Message(sn, ts, name, type, level, args));
        }
    }
}
