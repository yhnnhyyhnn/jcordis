package io.jcordis.core.fiber;

import java.util.List;

/**
 * Metadata of a registered effect, mirroring Cordis's {@code EffectMeta}.
 *
 * <p>Each {@link Fiber#effect} carries a label and a list of child metas — the
 * nested effects its runner collected. {@link Fiber#getEffects} exposes the
 * tree in registration order, mirroring Cordis's {@code fiber.getEffects()}.
 *
 * @param label the effect label (e.g. {@code ctx.on("foo")})
 * @param children nested effect metas collected by this effect's runner
 */
public record EffectMeta(String label, List<EffectMeta> children) {}
