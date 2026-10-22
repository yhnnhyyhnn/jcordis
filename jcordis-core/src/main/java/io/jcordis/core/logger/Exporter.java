package io.jcordis.core.logger;

import java.util.Map;

/**
 * A log sink, mirroring Cordis's {@code Exporter}.
 *
 * <p>Receives every message whose level passes the exporter's threshold.
 * {@link #levels} can override the per-name threshold (keyed by logger name,
 * or {@code "default"}), and {@link #formatters} can override individual
 * placeholder strategies (keyed by placeholder letter).
 */
public interface Exporter {

    /** Formats one placeholder value (strategy interface, mirrors Cordis's {@code Formatter}). */
    @FunctionalInterface
    interface ValueFormatter {

        String apply(Object value, Exporter exporter, Message message);
    }

    void export(Message message);

    /** Per-name level thresholds, or {@code null} to use the logger's level. */
    default Map<String, Integer> levels() {
        return Map.of();
    }

    /** Custom placeholder strategies; override the defaults keyed by letter. */
    default Map<String, ValueFormatter> formatters() {
        return Map.of();
    }

    /** ANSI color count ({@code 0} disables coloring). */
    default int colors() {
        return 0;
    }

    /** Maximum line length before truncation. */
    default int maxLength() {
        return 10240;
    }
}
