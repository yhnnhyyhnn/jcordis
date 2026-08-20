package io.jcordis.logger.console;

import io.jcordis.core.context.Context;
import io.jcordis.core.logger.Exporter;
import io.jcordis.core.logger.Logger;
import io.jcordis.core.logger.Message;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Console exporter, mirroring Cordis's {@code ConsoleExporter}.
 *
 * <p>Renders each message as {@code [L] name message} with optional ANSI colors
 * (name hashed to a stable 256-color code) and a timestamp prefix. Register via
 * {@code ctx.loggerService().exporter(this)}.
 */
public class ConsoleExporter implements Exporter {

    private final String showTime;
    private final int colors;
    private final int labelWidth;
    private final boolean showDiff;
    private long timestamp;

    public ConsoleExporter(Context ctx, Config config) {
        this.showTime = config.showTime;
        this.colors = config.colors;
        this.labelWidth = config.labelWidth;
        this.showDiff = config.showDiff;
        this.timestamp = System.currentTimeMillis();
        ctx.loggerService().exporter(this);
    }

    public ConsoleExporter(Context ctx) {
        this(ctx, new Config());
    }

    @Override
    public void export(Message message) {
        System.out.println(render(message));
    }

    @Override
    public int colors() {
        return colors;
    }

    public String render(Message message) {
        StringBuilder output = new StringBuilder();
        if (showTime != null && !showTime.isEmpty()) {
            String time = DateTimeFormatter.ofPattern(toJavaPattern(showTime))
                    .format(LocalDateTime.now());
            output.append(Logger.color(this, 8, time));
        }
        int code = Logger.code(message.name(), colors);
        String label = Logger.color(this, code, message.name(), ";1");
        String prefix = "[" + Character.toUpperCase(message.type().charAt(0)) + "]";
        String space = " ";
        if (labelWidth > 0) {
            label = pad(label, labelWidth);
        }
        output.append(prefix).append(space).append(label).append(space);
        output.append(Logger.format(this, message));
        if (showDiff) {
            long diff = message.ts() - timestamp;
            output.append(Logger.color(this, code, " +" + diff + "ms"));
            timestamp = message.ts();
        }
        return output.toString();
    }

    private static String pad(String value, int width) {
        StringBuilder sb = new StringBuilder(value);
        while (sb.length() < width) {
            sb.append(' ');
        }
        return sb.toString();
    }

    private static String toJavaPattern(String cordisPattern) {
        return cordisPattern.replace("yyyy", "yyyy")
                .replace("MM", "MM")
                .replace("dd", "dd")
                .replace("hh", "HH")
                .replace("mm", "mm")
                .replace("ss", "ss");
    }

    /** Configuration for the console exporter. */
    public static class Config {
        public String showTime = "yyyy-MM-dd HH:mm:ss ";
        public int colors = 2;
        public int labelWidth = 0;
        public boolean showDiff;
    }
}