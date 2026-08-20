package io.jcordis.core.logger;

import java.util.Map;

/**
 * A log sink, mirroring Cordis's {@code Exporter}.
 *
 * <p>Receives every message whose level passes the exporter's threshold.
 * {@link #levels} can override the per-name threshold (keyed by logger name,
 * or {@code "default"}).
 */
public interface Exporter {

    void export(Message message);

    /** Per-name level thresholds, or {@code null} to use the logger's level. */
    default Map<String, Integer> levels() {
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