package io.jcordis.core.logger;

/** Log levels, mirroring Cordis's {@code LoggerLevel}. */
public enum LoggerLevel {
    ERROR(0),
    WARN(1),
    INFO(2),
    DEBUG(3);

    private final int value;

    LoggerLevel(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }
}
