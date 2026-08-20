package io.jcordis.loader;

/**
 * Per-entry isolation realm, mirroring Cordis's {@code LocalRealm}. Service
 * keys generated here are suffixed {@code #id}, so entries with
 * {@code isolate: {name: true}} never share isolated services with each other
 * or with any other realm.
 */
public final class LocalRealm extends Realm {

    private final String suffix;

    public LocalRealm(Entry entry) {
        this.suffix = "#" + entry.options.id;
    }

    @Override
    public String suffix() {
        return suffix;
    }
}
