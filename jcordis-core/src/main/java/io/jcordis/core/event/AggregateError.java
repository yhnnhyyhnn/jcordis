package io.jcordis.core.event;

import java.util.List;

/**
 * Collects every failure of a {@code parallel} dispatch, mirroring the JS
 * built-in {@code AggregateError} thrown by Cordis's {@code parallel}.
 */
public class AggregateError extends RuntimeException {

    private final List<Throwable> errors;

    public AggregateError(List<Throwable> errors) {
        super(errors.stream()
                .map(error -> error.getMessage() != null ? error.getMessage() : error.getClass().getName())
                .reduce((a, b) -> a + ", " + b)
                .orElse("aggregate error"));
        this.errors = List.copyOf(errors);
    }

    /** Every error raised by the dispatched listeners. */
    public List<Throwable> errors() {
        return errors;
    }
}