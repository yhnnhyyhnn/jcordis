package io.jcordis.loader;

/**
 * Global (shared) isolation realm, mirroring Cordis's {@code GlobalRealm}.
 * Service keys generated here are suffixed {@code @label}, so entries sharing
 * the same {@code isolate: {name: label}} label share one realm, while
 * different labels stay isolated.
 */
public final class GlobalRealm extends Realm {

    private final String suffix;

    public GlobalRealm(String label) {
        this.suffix = "@" + label;
    }

    @Override
    public String suffix() {
        return suffix;
    }
}
