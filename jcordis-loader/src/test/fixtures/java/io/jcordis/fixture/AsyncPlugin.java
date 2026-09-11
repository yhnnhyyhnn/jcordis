package io.jcordis.fixture;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Test plugin with asynchronous initialization: its body returns an incomplete
 * future that settles shortly after, so a jar change can arrive while the
 * initialization is still in flight (the per-fiber drain case).
 */
public class AsyncPlugin implements Plugin {

    @Override
    public Object apply(Context ctx, Object config) {
        return CompletableFuture.runAsync(
                () -> {
                    try {
                        TimeUnit.MILLISECONDS.sleep(120);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
    }
}
