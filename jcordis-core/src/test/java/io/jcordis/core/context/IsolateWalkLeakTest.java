package io.jcordis.core.context;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.fiber.Fiber;
import io.jcordis.core.service.ServiceKey;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Reproduces the third-party (majo) report against the fiber-chain walk: a
 * plugin inside an isolated realm must not see the root fiber's resolution
 * snapshot for a service the root itself injects — the walk layer leaked
 * across the isolation boundary even though the fallback resolves strictly
 * from the caller's key.
 */
class IsolateWalkLeakTest {

    @Test
    void walkMustNotLeakTheHostSnapshotAcrossAnIsolationBoundary() {
        Context root = Context.create();
        root.provide("loop", "root-loop");
        // the host plugin injects the service, so ITS fiber carries a
        // resolution snapshot under the default key
        Fiber host = root.inject(List.of("loop"), (ctx, cfg) -> null).await().join();

        // the scope is derived from the host's context: a plugin inside it
        // walks up to the host fiber — the snapshot there must be invisible
        // behind the isolation boundary
        ServiceKey<Object> realm = ServiceKey.unique("loop");
        Context scope = host.ctx().isolate("loop", realm);
        scope.provide("loop", "shadow-loop");

        AtomicReference<Object> seen = new AtomicReference<>("unset");
        Fiber fiber = scope.plugin((ctx, cfg) -> {
            seen.set(ctx.get("loop"));
            return null;
        });
        fiber.await().join();

        assertThat(seen.get())
                .as("the isolated realm sees its own impl, never the host fiber's snapshot")
                .isEqualTo("shadow-loop");
    }
}
