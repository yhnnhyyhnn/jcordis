package io.jcordis.core.event;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Boundary coverage for the event dispatch modes: asynchronous listeners,
 * short-circuits, empty listener sets and the internal dispatch hook.
 */
class EventBusEdgeTest {

    @Test
    void serialShouldAwaitAsynchronousListeners() {
        Context root = Context.create();
        List<String> order = new ArrayList<>();
        root.on("step", (thisArg, args) -> {
            order.add("first-start");
            return CompletableFuture.runAsync(() -> order.add("first-done"));
        });
        root.on("step", (thisArg, args) -> {
            order.add("second");
            return null;
        });

        root.events().serial(root, "step").join();

        assertThat(order).containsExactly("first-start", "first-done", "second");
    }

    @Test
    void serialShouldShortCircuitOnABailValue() {
        Context root = Context.create();
        AtomicInteger later = new AtomicInteger();
        root.on("step", (thisArg, args) -> "stop");
        root.on("step", (thisArg, args) -> {
            later.incrementAndGet();
            return null;
        });

        Object result = root.events().serial(root, "step").join();

        assertThat(result).isEqualTo("stop");
        assertThat(later).as("listeners after the bail are skipped").hasValue(0);
    }

    @Test
    void serialShouldPropagateListenerFailures() {
        Context root = Context.create();
        root.on("step", (thisArg, args) -> {
            throw new IllegalStateException("boom");
        });

        CompletableFuture<Object> future = root.events().serial(root, "step");

        assertThat(future).isCompletedExceptionally();
        assertThat(future.exceptionNow()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void serialShouldUnwrapCompletionExceptions() {
        Context root = Context.create();
        root.on("step", (thisArg, args) -> {
            throw new CompletionException(new IllegalArgumentException("wrapped"));
        });

        CompletableFuture<Object> future = root.events().serial(root, "step");

        assertThat(future).isCompletedExceptionally();
        assertThat(future.exceptionNow()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void serialAndParallelAndBailShouldTolerateEmptyListenerSets() {
        Context root = Context.create();

        assertThat(root.events().serial(root, "nothing").join()).isNull();
        assertThat(root.events().parallel(root, "nothing")).isCompleted();
        assertThat(root.events().bail(root, "nothing")).as("no listener bailed").isNull();
    }

    @Test
    void parallelShouldAwaitAsynchronousListeners() {
        Context root = Context.create();
        AtomicInteger done = new AtomicInteger();
        root.on("step", (thisArg, args) -> CompletableFuture.runAsync(done::incrementAndGet));
        root.on("step", (thisArg, args) -> CompletableFuture.runAsync(done::incrementAndGet));

        root.events().parallel(root, "step").join();

        assertThat(done).hasValue(2);
    }

    @Test
    void parallelShouldFailWhenAListenerFails() {
        Context root = Context.create();
        root.on("step", (thisArg, args) -> {
            throw new IllegalStateException("boom");
        });

        assertThat(root.events().parallel(root, "step")).isCompletedExceptionally();
    }

    @Test
    void nonBailValuesShouldNotShortCircuit() {
        Context root = Context.create();
        AtomicInteger later = new AtomicInteger();
        root.on("step", (thisArg, args) -> Boolean.FALSE);
        root.on("step", (thisArg, args) -> {
            later.incrementAndGet();
            return null;
        });

        Object result = root.events().bail(root, "step");

        assertThat(result).as("Boolean.FALSE is an explicit 'continue'").isNull();
        assertThat(later).hasValue(1);
    }

    @Test
    void internalDispatchHookShouldObserveEveryNonInternalEmit() {
        Context root = Context.create();
        List<String> dispatched = new ArrayList<>();
        root.on("internal/dispatch", (thisArg, args) -> {
            dispatched.add(String.valueOf(args[1]));
            return null;
        });

        root.events().emit(root, "app/event");
        root.events().emit(root, "internal/hidden");

        assertThat(dispatched).as("internal events do not re-enter the hook").containsExactly("app/event");
    }

    @Test
    void disposingOneDuplicateRegistrationShouldKeepTheOther() {
        Context root = Context.create();
        AtomicInteger calls = new AtomicInteger();
        io.jcordis.core.event.EventHandler listener = (thisArg, args) -> {
            calls.incrementAndGet();
            return null;
        };
        io.jcordis.core.util.Disposable first = root.on("app/dup", listener);
        root.on("app/dup", listener);

        root.events().emit(root, "app/dup");
        assertThat(calls).hasValue(2);

        // one disposable removes exactly one registration — the second
        // registration stays alive (mirrors Cordis's findIndex + splice)
        first.dispose();
        root.events().emit(root, "app/dup");
        assertThat(calls).hasValue(3);
    }

    @Test
    void parallelAggregateErrorShouldNameTheFailingEvent() {
        Context root = Context.create();
        root.on("app/boom", (thisArg, args) -> {
            throw new IllegalStateException("test");
        });

        Throwable error = root.events()
                .parallel(root, "app/boom")
                .handle((ignored, e) -> e)
                .join();
        if (error instanceof java.util.concurrent.CompletionException completion) {
            error = completion.getCause();
        }

        assertThat(error).isInstanceOf(AggregateError.class);
        assertThat(error)
                .as("the aggregate names the failing event (mirrors Cordis #205)")
                .hasMessageContaining("one or more listeners of event \"app/boom\" failed");
        assertThat(((AggregateError) error).errors()).hasSize(1);
    }
}
