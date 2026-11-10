package io.jcordis.loader.include;

import io.jcordis.core.util.Disposable;

/**
 * A controllable file watch (mirrors Cordis's {@code fs.watch()} returning a
 * {@code Watcher}): {@link #dispose()} unregisters, {@link #pause()} suspends
 * change delivery for this callback and {@link #resume()} releases the
 * changes that piled up meanwhile — coalesced into a single delivery, so a
 * batch of edits under pause costs one callback run, not one per file save.
 *
 * <p>Disposing also detaches the underlying {@code ctx.effect} registration,
 * so tearing down the owning fiber cleans the watch either way.
 */
public final class Watcher implements Disposable, Runnable {

    private final Runnable callback;
    private volatile Disposable effect;
    private volatile boolean paused;
    /** A change happened while paused; resume delivers it exactly once. */
    private volatile boolean pending;

    Watcher(Runnable callback) {
        this.callback = callback;
    }

    /** Called by {@link Hmr} after the effect registration completed. */
    void ownedBy(Disposable effect) {
        this.effect = effect;
    }

    /** The polling loop's delivery point; a paused watch records and defers. */
    @Override
    public void run() {
        if (paused) {
            pending = true;
            return;
        }
        callback.run();
    }

    /** Suspends change delivery for this watch (changes are remembered). */
    public Watcher pause() {
        paused = true;
        return this;
    }

    /** Resumes delivery; changes that piled up while paused fire once, coalesced. */
    public Watcher resume() {
        if (!paused) {
            return this;
        }
        paused = false;
        if (pending) {
            pending = false;
            callback.run();
        }
        return this;
    }

    /** Whether delivery is currently suspended. */
    public boolean isPaused() {
        return paused;
    }

    /** Unregisters this watch (idempotent; also cleaned by fiber teardown). */
    @Override
    public void dispose() {
        Disposable registration = effect;
        if (registration != null) {
            registration.dispose();
        }
    }
}
