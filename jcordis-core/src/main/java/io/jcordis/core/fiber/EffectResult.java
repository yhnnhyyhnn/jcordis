package io.jcordis.core.fiber;

import io.jcordis.core.util.Disposable;
import java.util.List;

/**
 * Result of an effect runner, mirroring Cordis's effect return protocol:
 * a single disposable, an iterable of disposables, or nothing.
 */
public sealed interface EffectResult {

    /** No disposables (e.g. effect returned null). */
    EffectResult NOOP = new Noop();

    record Noop() implements EffectResult {}

    record Single(Disposable disposable) implements EffectResult {}

    record Multiple(List<Disposable> disposables) implements EffectResult {}

    /** Wraps a possibly-null disposable. */
    static EffectResult of(Disposable disposable) {
        return disposable == null ? NOOP : new Single(disposable);
    }

    /** Wraps several disposables (corresponds to an iterable effect in Cordis). */
    static EffectResult of(Disposable... disposables) {
        return new Multiple(List.of(disposables));
    }
}
