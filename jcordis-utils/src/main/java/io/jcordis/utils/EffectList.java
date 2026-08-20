package io.jcordis.utils;

import io.jcordis.core.context.Context;
import io.jcordis.core.fiber.EffectResult;
import io.jcordis.core.util.Disposable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Effect-scoped collection, mirroring {@code @cordisjs/utils} {@code List} —
 * named {@code EffectList} to stay distinct from {@link java.util.List}.
 *
 * <p>Each {@link #push}ed element is registered as an effect on the owning
 * context (Observer pattern): the element appears immediately, and is removed
 * automatically when the current fiber is torn down. Iteration preserves
 * insertion order; {@link #filter} and {@link #map} return lazy stream views.
 *
 * @param <T> the element type
 */
public final class EffectList<T> implements Iterable<T> {

    private final Context ctx;
    private final String trace;
    private final ArrayList<T> inner = new ArrayList<>();

    public EffectList(Context ctx, String trace) {
        this.ctx = ctx;
        this.trace = trace;
    }

    /** Number of elements currently registered. */
    public int size() {
        return inner.size();
    }

    /** Whether the list is empty. */
    public boolean isEmpty() {
        return inner.isEmpty();
    }

    /**
     * Appends an element, registered as an effect on the owning context. The
     * element disappears when the current fiber is disposed, or when the
     * returned disposable is invoked.
     */
    public Disposable push(T value) {
        return ctx.effect(runner -> {
            inner.add(value);
            return EffectResult.of(() -> inner.remove(value));
        }, trace + ".push()");
    }

    /** Returns a lazy filtered view of this list. */
    public Stream<T> filter(Predicate<T> predicate) {
        return stream().filter(predicate);
    }

    /** Returns a lazy mapped view of this list. */
    public <U> Stream<U> map(Function<T, U> mapper) {
        return stream().map(mapper);
    }

    /** Returns a sequential stream over the registered elements. */
    public Stream<T> stream() {
        return inner.stream();
    }

    @Override
    public Iterator<T> iterator() {
        return new Iterator<T>() {
            private final Iterator<T> delegate = inner.iterator();

            @Override
            public boolean hasNext() {
                return delegate.hasNext();
            }

            @Override
            public T next() {
                if (!delegate.hasNext()) {
                    throw new NoSuchElementException();
                }
                return delegate.next();
            }
        };
    }
}
