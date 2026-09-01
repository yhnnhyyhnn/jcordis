package io.jcordis.core.fiber;

import io.jcordis.core.context.Context;

/**
 * Runner of an effect body. The returned {@link EffectResult} carries the
 * disposable(s) that revert the side effects (temporal composability).
 */
@FunctionalInterface
public interface EffectRunner {

    EffectResult run(Context ctx);
}
