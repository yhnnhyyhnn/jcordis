package io.jcordis.loader;

import io.jcordis.core.service.ServiceKey;
import java.util.HashMap;
import java.util.Map;

/**
 * An isolation realm, mirroring Cordis's {@code Realm}.
 *
 * <p>Each realm owns service keys for isolated names; the {@code suffix}
 * distinguishes local realms (per-entry {@code #id}) from global realms
 * (shared {@code @label}).
 */
public abstract class Realm {

    protected final Map<String, ServiceKey<?>> store = new HashMap<>();

    public abstract String suffix();

    public ServiceKey<?> access(String name, boolean create) {
        ServiceKey<?> key = store.get(name);
        if (key == null && create) {
            key = ServiceKey.unique(name + suffix());
            store.put(name, key);
        }
        return key != null ? key : ServiceKey.of(name + suffix());
    }

    public void delete(String name) {
        store.remove(name);
    }

    public int size() {
        return store.size();
    }
}