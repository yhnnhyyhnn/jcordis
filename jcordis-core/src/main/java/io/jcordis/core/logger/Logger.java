package io.jcordis.core.logger;

/**
 * A named logger, mirroring Cordis's {@code Logger}.
 *
 * <p>Logging an {@code Error} logs its cause chain (aggregate errors log each
 * contained error). Each message is dispatched to every exporter whose
 * threshold admits the level.
 */
public final class Logger {

    private final String name;
    private final LoggerService service;

    Logger(String name, LoggerService service) {
        this.name = name;
        this.service = service;
    }

    public String name() {
        return name;
    }

    public void error(Object... args) {
        service.log("error", LoggerLevel.ERROR.value(), name, args);
    }

    public void warn(Object... args) {
        service.log("warn", LoggerLevel.WARN.value(), name, args);
    }

    public void info(Object... args) {
        service.log("info", LoggerLevel.INFO.value(), name, args);
    }

    public void debug(Object... args) {
        service.log("debug", LoggerLevel.DEBUG.value(), name, args);
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
        return palette[Math.abs(hash) % palette.length];
    }

    /** Formats a message's args with {@code %s}/{@code %d}/{@code %o} placeholders. */
    public static String format(Exporter exporter, Message message) {
        Object[] args = message.args();
        java.util.List<Object> remaining = new java.util.ArrayList<>(java.util.List.of(args));
        Object first = remaining.isEmpty() ? null : remaining.get(0);
        if (first instanceof Throwable error) {
            remaining.set(0, error.getMessage());
            remaining.add(0, "%s");
        } else if (!(first instanceof String)) {
            remaining.add(0, "%o");
        }
        String format = (String) remaining.remove(0);
        StringBuilder sb = new StringBuilder();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("%([a-zA-Z%])").matcher(format);
        int cursor = 0;
        while (matcher.find()) {
            sb.append(format, cursor, matcher.start());
            String spec = matcher.group(1);
            if (spec.equals("%")) {
                sb.append('%');
            } else {
                Object value = remaining.isEmpty() ? null : remaining.remove(0);
                sb.append(formatter(spec).apply(value));
            }
            cursor = matcher.end();
        }
        sb.append(format.substring(cursor));
        for (Object arg : remaining) {
            if (arg != null && !(arg instanceof String)) {
                arg = formatter("o").apply(arg);
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

    private static java.util.function.Function<Object, String> formatter(String spec) {
        return switch (spec) {
            case "s" -> value -> String.valueOf(value);
            case "d", "i" -> value -> String.valueOf((long) Double.parseDouble(String.valueOf(value)));
            case "f" -> value -> String.valueOf(Double.parseDouble(String.valueOf(value)));
            case "o", "O" -> value -> value == null ? "null" : String.valueOf(value);
            case "c" -> value -> "";
            case "C" -> value -> "";
            default -> value -> "%" + spec + value;
        };
    }

    private static final int[] C16 = {6, 2, 3, 4, 5, 1};
    private static final int[] C256 = {
        20, 21, 26, 27, 32, 33, 38, 39, 40, 41, 42, 43, 44, 45, 56, 57, 62,
        63, 68, 69, 74, 75, 76, 77, 78, 79, 80, 81, 92, 93, 98, 99, 112, 113,
        129, 134, 135, 148, 149, 160, 161, 162, 163, 164, 165, 166, 167, 168,
        169, 170, 171, 172, 173, 178, 179, 184, 185, 196, 197, 198, 199, 200,
        201, 202, 203, 204, 205, 206, 207, 208, 209, 214, 215, 220, 221,
    };
}