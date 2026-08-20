package io.jcordis.core.fiber;

/** Framework-level error with a stable machine-readable code. */
public class CordisError extends RuntimeException {

    public enum Code {
        INACTIVE_EFFECT,
    }

    private final Code code;

    public CordisError(Code code) {
        super(code.name());
        this.code = code;
    }

    public Code code() {
        return code;
    }
}