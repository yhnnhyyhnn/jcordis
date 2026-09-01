package io.jcordis.core.registry;

/**
 * A plugin instance hook, mirroring Cordis's {@code [Service.init]} symbol.
 *
 * <p>Returned value may be a {@code Disposable}, a {@code CompletableFuture}
 * of one, or {@code null}.
 */
public interface Initializable {

    Object init();
}
