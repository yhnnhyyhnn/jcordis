package io.jcordis.core.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * An identity-keyed list of disposables, mirroring Cordis's
 * {@code DisposableList}.
 *
 * <p>Each {@link #push} returns a disposer removing that element; {@link #clear}
 * returns the removed elements in reverse insertion order (used for teardown).
 */
public final class DisposableList<T> implements Iterable<T> {

    private final Map<Integer, T> map = new LinkedHashMap<>();
    private int sn;

    public int length() {
        return map.size();
    }

    /** Appends a value and returns a disposer that removes it. */
    public Disposable push(T value) {
        int id = ++sn;
        map.put(id, value);
        return () -> map.remove(id);
    }

    /** Removes the given value by identity, returning whether it was present. */
    public boolean delete(T value) {
        int id = -1;
        for (Map.Entry<Integer, T> entry : map.entrySet()) {
            if (entry.getValue() == value) {
                id = entry.getKey();
                break;
            }
        }
        if (id < 0) return false;
        map.remove(id);
        return true;
    }

    /** Removes and returns all elements in reverse insertion order. */
    public List<T> clear() {
        List<T> values = new ArrayList<>(map.values());
        map.clear();
        java.util.Collections.reverse(values);
        return values;
    }

    @Override
    public java.util.Iterator<T> iterator() {
        return map.values().iterator();
    }

    /** Indexed access for diagnostics. */
    public T get(int index) {
        java.util.Iterator<T> it = map.values().iterator();
        for (int i = 0; i < index; i++) {
            it.next();
        }
        if (!it.hasNext()) throw new NoSuchElementException("index " + index);
        return it.next();
    }
}
