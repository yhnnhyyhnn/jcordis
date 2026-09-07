package io.jcordis.core.context;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.fiber.Fiber;
import io.jcordis.core.service.ServiceKey;
import io.jcordis.core.util.Disposable;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * M-C4 spike: does jcordis already provide "one independent, disposable scope
 * per agent" out of the box? Validates the three claimed gaps —
 *
 * <ol>
 *   <li>per-agent independently disposable lifecycle (a public "child fiber");</li>
 *   <li>scoped service registration rolling back automatically when the fiber
 *       is torn down (no agentLoop/turnSummary leaks);</li>
 *   <li>shadow (per-scope) service registration via isolation realms.</li>
 * </ol>
 *
 * <p>Verdict expected: {@code ctx.plugin()} on a (possibly isolated) child
 * context IS the per-agent scope — each call creates a distinct fiber whose
 * {@code disposeAsync()} tears that agent down alone, and services provided on
 * its context are unregistered on disposal. The gaps are usage confirmation,
 * not missing machinery.
 */
class AgentScopeTest {

    @Test
    void agentFiber_shouldBeIndependentlyDisposable() {
        Context root = Context.create();
        AtomicInteger aDisposes = new AtomicInteger();
        AtomicInteger bDisposes = new AtomicInteger();
        AtomicInteger aEvents = new AtomicInteger();
        AtomicInteger bEvents = new AtomicInteger();

        // each agent = one ctx.plugin() call = one distinct, disposable scope
        Fiber agentA = root.plugin((ctx, config) -> {
            ctx.on("agent/step", (thisArg, args) -> {
                aEvents.incrementAndGet();
                return null;
            });
            return (Disposable) aDisposes::incrementAndGet;
        });
        Fiber agentB = root.plugin((ctx, config) -> {
            ctx.on("agent/step", (thisArg, args) -> {
                bEvents.incrementAndGet();
                return null;
            });
            return (Disposable) bDisposes::incrementAndGet;
        });
        assertThat(agentA).isNotSameAs(agentB);

        root.emit("agent/step");
        assertThat(aEvents).hasValue(1);
        assertThat(bEvents).hasValue(1);

        // tearing down A alone must not touch B
        agentA.disposeAsync().join();
        assertThat(aDisposes).hasValue(1);
        root.emit("agent/step");
        assertThat(aEvents).as("agent A hooks removed").hasValue(1);
        assertThat(bEvents).as("agent B unaffected").hasValue(2);
        assertThat(bDisposes).hasValue(0);

        agentB.disposeAsync().join();
        assertThat(bDisposes).hasValue(1);
    }

    @Test
    void agentLoop_shouldNotLeakServicesWhenEachTurnGetsItsOwnScope() {
        Context root = Context.create();
        // an agent loop that creates a fresh scope per turn (the recommended
        // usage): services registered in a turn disappear with the turn
        for (int turn = 0; turn < 4; turn++) {
            final int t = turn;
            Fiber turnFiber = root.plugin((ctx, config) -> {
                ctx.provide("agent-turn", "turn-" + t);
                ctx.provide("agent-turn-summary", "summary-" + t);
                return null;
            });
            assertThat(root.<Object>get("agent-turn"))
                    .as("visible during the turn")
                    .isEqualTo("turn-" + t);
            turnFiber.disposeAsync().join();
            // after the turn: both scoped services are gone — no leak
            assertThat(root.<Object>get("agent-turn"))
                    .as("rolled back after turn " + turn)
                    .isNull();
            assertThat(root.<Object>get("agent-turn-summary")).isNull();
        }
        assertThat(root.registry().size()).isZero();
    }

    @Test
    void repeatedProvideInSameScope_shouldRequireDisposeFirst() {
        Context root = Context.create();
        // registering the same name twice in ONE scope must throw — the scope
        // must be torn down (or the previous provide disposed) before re-registering
        Fiber turn = root.plugin((ctx, config) -> {
            ctx.provide("agent-turn", "first");
            try {
                ctx.provide("agent-turn", "second");
            } catch (IllegalStateException expected) {
                return null;
            }
            throw new AssertionError("duplicate provide in one scope must throw");
        });
        turn.await().join();
        turn.disposeAsync().join();
        assertThat(root.<Object>get("agent-turn")).isNull();
    }

    @Test
    void agentShadowServices_shouldIsolateByRealm() {
        Context root = Context.create();
        ServiceKey<Object> realmA = ServiceKey.unique("realm-A");
        ServiceKey<Object> realmB = ServiceKey.unique("realm-B");

        Fiber agentA = root.isolate("agent-db", realmA).plugin((ctx, config) -> {
            ctx.provide("agent-db", "db-A");
            return null;
        });
        Fiber agentB = root.isolate("agent-db", realmB).plugin((ctx, config) -> {
            ctx.provide("agent-db", "db-B");
            return null;
        });

        // shadow registration: same name, per-scope value, no cross-talk
        assertThat(root.isolate("agent-db", realmA).<Object>get("agent-db")).isEqualTo("db-A");
        assertThat(root.isolate("agent-db", realmB).<Object>get("agent-db")).isEqualTo("db-B");

        agentA.disposeAsync().join();
        assertThat(root.isolate("agent-db", realmA).<Object>get("agent-db"))
                .as("A's shadow gone")
                .isNull();
        assertThat(root.isolate("agent-db", realmB).<Object>get("agent-db"))
                .as("B's shadow intact")
                .isEqualTo("db-B");
        agentB.disposeAsync().join();
    }
}
