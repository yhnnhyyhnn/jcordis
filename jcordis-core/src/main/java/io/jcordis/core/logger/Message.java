package io.jcordis.core.logger;

/** A single log entry, mirroring Cordis's {@code Message}. */
public record Message(int sn, long ts, String name, String type, int level, Object[] args) {}
