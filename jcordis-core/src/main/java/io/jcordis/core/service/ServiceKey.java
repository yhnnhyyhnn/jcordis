package io.jcordis.core.service;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Type-safe service key, mirroring Cordis's use of JS symbols for service identity.
 *
 * <p>Two flavors exist:
 * <ul>
 *   <li>{@link #of(String)} — canonical key. Equal for the same name, so all
 *       non-isolated consumers of a service share one key (like {@code Symbol.for}).</li>
 *   <li>{@link #unique(String)} — fresh key per call. Used for {@code ctx.isolate(name)},
 *       so isolated realms never collide with the canonical key or each other
 *       (like {@code Symbol(name)}).</li>
 * </ul>
 *
 * @param <T> the service value type
 */
public final class ServiceKey<T> {

    private static final ConcurrentHashMap<String, ServiceKey<?>> CANONICAL = new ConcurrentHashMap<>();
    private static final AtomicLong UID = new AtomicLong();

    private final String name;
    private final long uid; // 0 for canonical keys, unique per generated key

    private ServiceKey(String name, long uid) {
        this.name = name;
        this.uid = uid;
    }

    /** Returns the canonical (globally shared) key for the given service name. */
    @SuppressWarnings("unchecked")
    public static <T> ServiceKey<T> of(String name) {
        return (ServiceKey<T>) CANONICAL.computeIfAbsent(name, key -> new ServiceKey<>(key, 0));
    }

    /** Returns a brand-new key that is unequal to every other key. */
    public static <T> ServiceKey<T> unique(String name) {
        return new ServiceKey<>(name, UID.incrementAndGet());
    }

    public String name() {
        return name;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ServiceKey<?> key)) return false;
        return uid == key.uid && name.equals(key.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, uid);
    }

    @Override
    public String toString() {
        return uid == 0 ? name : name + "#" + uid;
    }
}