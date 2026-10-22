package io.jcordis.core.logger;

import java.util.Map;

/**
 * A named logger, mirroring Cordis's {@code Logger}.
 *
 * <p>Logging an {@code Error} logs its cause chain (aggregate errors log each
 * contained error). Each message is dispatched to every exporter whose
 * threshold admits the level.
 */
public final class Logger {

    private final String name;
    /** Threshold from the intercept chain; {@code null} falls back to INFO. */
    private final Integer level;

    private final LoggerService service;

    Logger(String name, Integer level, LoggerService service) {
        this.name = name;
        this.level = level;
        this.service = service;
    }

    public String name() {
        return name;
    }

    /** The intercept-provided threshold, or {@code null} for the default. */
    public Integer level() {
        return level;
    }

    public void error(Object... args) {
        service.log("error", LoggerLevel.ERROR.value(), name, level, args);
    }

    public void warn(Object... args) {
        service.log("warn", LoggerLevel.WARN.value(), name, level, args);
    }

    public void info(Object... args) {
        service.log("info", LoggerLevel.INFO.value(), name, level, args);
    }

    public void debug(Object... args) {
        service.log("debug", LoggerLevel.DEBUG.value(), name, level, args);
    }

    /** Wraps {@code value} in an ANSI color escape for the given 256-color code. */
    public static String color(Exporter exporter, int code, Object value) {
        return color(exporter, code, value, "");
    }

    /** Wraps {@code value} in an ANSI color escape with an optional decoration. */
    public static String color(Exporter exporter, int code, Object value, String decoration) {
        if (exporter.colors() == 0) return "" + value;
        String c = code < 8 ? "3" + code : "38;5;" + code;
        return "\u001b[" + c + (exporter.colors() >= 2 ? decoration : "") + "m" + value + "\u001b[0m";
    }

    /** Hashes a name into a stable 256-color palette index. */
    public static int code(String name, int level) {
        int hash = 0;
        for (int i = 0; i < name.length(); i++) {
            hash = ((hash << 3) - hash) + name.charAt(i) + 13;
            hash |= 0;
        }
        int[] palette = level >= 2 ? C256 : C16;
        // Math.abs(Integer.MIN_VALUE) is negative in Java (unlike JS) — mask
        // into a non-negative int before indexing
        return palette[(hash & 0x7fffffff) % palette.length];
    }

    /** Formats a message's args with {@code %s}/{@code %d}/{@code %o} placeholders. */
    public static String format(Exporter exporter, Message message) {
        Object[] args = message.args();
        // Arrays.asList (not List.of) so that null arguments are accepted: a
        // null is a legitimate value to log, and List.of would throw
        java.util.List<Object> remaining = new java.util.ArrayList<>(java.util.Arrays.asList(args));
        Object first = remaining.isEmpty() ? null : remaining.get(0);
        if (first instanceof Throwable error) {
            // log the stack (and the full cause chain, with AggregateError
            // members) — a bare message loses everything needed to debug the
            // failure (mirrors Cordis's `args[0].stack || message`)
            remaining.set(0, stackOf(error));
            remaining.add(0, "%s");
        } else if (!(first instanceof String)) {
            remaining.add(0, "%o");
        }
        String format = (String) remaining.remove(0);
        StringBuilder sb = new StringBuilder();
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("%([a-zA-Z%])").matcher(format);
        int cursor = 0;
        while (matcher.find()) {
            sb.append(format, cursor, matcher.start());
            String spec = matcher.group(1);
            if (spec.equals("%")) {
                sb.append('%');
            } else {
                Object value = remaining.isEmpty() ? null : remaining.remove(0);
                sb.append(formatter(exporter, spec).apply(value, exporter, message));
            }
            cursor = matcher.end();
        }
        sb.append(format.substring(cursor));
        for (Object arg : remaining) {
            if (arg != null && !(arg instanceof String)) {
                arg = formatter(exporter, "o").apply(arg, exporter, message);
            }
            sb.append(' ').append(arg);
        }
        int maxLength = exporter.maxLength();
        String[] lines = sb.toString().split("\r?\n");
        java.util.List<String> truncated = new java.util.ArrayList<>();
        for (String line : lines) {
            truncated.add(line.length() > maxLength ? line.substring(0, maxLength) + "..." : line);
        }
        return String.join("\n", truncated);
    }

    private interface Formatter {
        String apply(Object value, Exporter exporter, Message message);
    }

    /** Renders a throwable with its stack, cause chain and AggregateError members. */
    private static String stackOf(Throwable error) {
        StringBuilder sb = new StringBuilder();
        renderThrowable(sb, error, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
        return sb.toString();
    }

    private static void renderThrowable(StringBuilder sb, Throwable error, java.util.Set<Throwable> seen) {
        if (error == null || !seen.add(error)) return;
        sb.append(error);
        for (StackTraceElement element : error.getStackTrace()) {
            sb.append("\n    at ").append(element);
        }
        if (error instanceof io.jcordis.core.event.AggregateError aggregate) {
            for (Throwable inner : aggregate.errors()) {
                sb.append("\n  [parallel] ");
                renderThrowable(sb, inner, seen);
            }
        }
        if (error.getCause() != null) {
            sb.append("\nCaused by: ");
            renderThrowable(sb, error.getCause(), seen);
        }
    }

    /**
     * Formatting strategies keyed by placeholder letter (strategy registry,
     * mirrors Cordis's {@code defaultFormatters}).
     */
    private static final java.util.Map<String, Formatter> FORMATTERS = buildFormatters();

    private static java.util.Map<String, Formatter> buildFormatters() {
        java.util.Map<String, Formatter> formatters = new java.util.HashMap<>();
        formatters.put("s", (value, exporter, message) -> String.valueOf(value));
        // non-numeric input must not make the logging call itself throw —
        // render NaN like Cordis does (mirrors JS Number coercion)
        Formatter number = (value, exporter, message) -> {
            try {
                return String.valueOf((long) Double.parseDouble(String.valueOf(value)));
            } catch (NumberFormatException e) {
                return "NaN";
            }
        };
        formatters.put("d", number);
        formatters.put("i", number);
        formatters.put("f", (value, exporter, message) -> {
            try {
                return String.valueOf(Double.parseDouble(String.valueOf(value)));
            } catch (NumberFormatException e) {
                return "NaN";
            }
        });
        Formatter object = (value, exporter, message) -> value == null ? "null" : jsonify(value, 0);
        formatters.put("o", object);
        formatters.put("O", object);
        formatters.put("c", (value, exporter, message) -> "");
        // %C colors the value with the message name's palette code (mirrors Cordis)
        formatters.put(
                "C", (value, exporter, message) -> color(exporter, code(message.name(), exporter.colors()), value));
        return java.util.Map.copyOf(formatters);
    }

    /** Nesting depth beyond which {@link #jsonify} stops expanding (cyclic-safe). */
    private static final int JSON_MAX_DEPTH = 8;

    /**
     * Renders a value as JSON, mirroring Cordis's {@code JSON.stringify}
     * object formatter: strings are quoted, maps/arrays/iterables expand. A
     * depth limit replaces the cyclic-reference error — deeply nested values
     * abbreviate instead of throwing (the logging call itself must never fail).
     */
    static String jsonify(Object value, int depth) {
        if (value == null) return "null";
        if (value instanceof CharSequence || value instanceof Character) {
            return "\"" + value + "\"";
        }
        if (value instanceof Boolean || value instanceof Number) {
            return value.toString();
        }
        if (depth >= JSON_MAX_DEPTH) {
            return "…";
        }
        StringBuilder sb = new StringBuilder();
        if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append(jsonify(String.valueOf(entry.getKey()), depth + 1))
                        .append(':')
                        .append(jsonify(entry.getValue(), depth + 1));
            }
            return sb.append('}').toString();
        }
        if (value instanceof Object[] array) {
            sb.append('[');
            for (int i = 0; i < array.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(jsonify(array[i], depth + 1));
            }
            return sb.append(']').toString();
        }
        if (value instanceof Iterable<?> iterable) {
            sb.append('[');
            boolean first = true;
            for (Object item : iterable) {
                if (!first) sb.append(',');
                first = false;
                sb.append(jsonify(item, depth + 1));
            }
            return sb.append(']').toString();
        }
        return "\"" + value + "\"";
    }

    private static Formatter formatter(Exporter exporter, String spec) {
        // an exporter-provided strategy wins over the default (mirrors Cordis's
        // `exporter.formatters?.[char] ?? defaultFormatters[char]`)
        Exporter.ValueFormatter custom = exporter.formatters().get(spec);
        if (custom != null) {
            return custom::apply;
        }
        return FORMATTERS.getOrDefault(spec, (value, e, message) -> "%" + spec + value);
    }

    private static final int[] C16 = {6, 2, 3, 4, 5, 1};
    private static final int[] C256 = {
        20, 21, 26, 27, 32, 33, 38, 39, 40, 41, 42, 43, 44, 45, 56, 57, 62, 63, 68, 69, 74, 75, 76, 77, 78, 79, 80, 81,
        92, 93, 98, 99, 112, 113, 129, 134, 135, 148, 149, 160, 161, 162, 163, 164, 165, 166, 167, 168, 169, 170, 171,
        172, 173, 178, 179, 184, 185, 196, 197, 198, 199, 200, 201, 202, 203, 204, 205, 206, 207, 208, 209, 214, 215,
        220, 221,
    };
}
