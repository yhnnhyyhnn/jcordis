package io.jcordis.core.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jcordis.core.context.Context;
import io.jcordis.core.util.Disposable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Translates Cordis events.spec.ts: registration, five dispatch modes, thisArg filtering. */
class EventBusTest {

    private static final String EVENT = "custom-event";

    /** Translates the Session helper: carries a flag and filters by the context's predicate. */
    private static final class Session implements EventFilter {
        private final boolean flag;

        Session(boolean flag) {
            this.flag = flag;
        }

        @Override
        public boolean test(Object context) {
            Context ctx = (Context) context;
            Predicate<Object> filter = ctx.filter();
            return filter == null || filter.test(this);
        }
    }

    /** Translates the Filter helper: matches sessions whose flag equals its own. */
    private static final class Filter implements Predicate<Object> {
        private final boolean flag;

        Filter(boolean flag) {
            this.flag = flag;
        }

        @Override
        public boolean test(Object session) {
            return session instanceof Session other && other.flag == flag;
        }
    }

    @Test
    void on_shouldRegisterEmitAndDispose() {
        Context root = Context.create();
        AtomicInteger calls = new AtomicInteger();
        Disposable dispose = root.on(EVENT, (thisArg, args) -> {
            calls.incrementAndGet();
            return null;
        });

        root.emit(EVENT);
        assertThat(calls).hasValue(1);
        root.emit(EVENT);
        assertThat(calls).hasValue(2);

        dispose.dispose();
        root.emit(EVENT);
        assertThat(calls).hasValue(2);
    }

    @Test
    void once_shouldFireOnlyOnce() {
        Context root = Context.create();
        AtomicInteger calls = new AtomicInteger();
        Disposable dispose = root.once(EVENT, (thisArg, args) -> {
            calls.incrementAndGet();
            return null;
        });

        root.emit(EVENT);
        assertThat(calls).hasValue(1);
        root.emit(EVENT);
        assertThat(calls).hasValue(1);

        dispose.dispose();
        root.emit(EVENT);
        assertThat(calls).hasValue(1);
    }

    @Test
    void parallel_shouldRespectThisArgFilter() {
        Context root = Context.create();
        root.parallel(EVENT).join();

        AtomicInteger calls = new AtomicInteger();
        root.extend(new Filter(true)).on(EVENT, (thisArg, args) -> {
            calls.incrementAndGet();
            return null;
        });

        root.parallel(EVENT).join();
        assertThat(calls).hasValue(1);
        root.parallel(new Session(false), EVENT).join();
        assertThat(calls).hasValue(1);
        root.parallel(new Session(true), EVENT).join();
        assertThat(calls).hasValue(2);
    }

    @Test
    void parallel_shouldAggregateErrorsWithoutShortCircuiting() {
        Context root = Context.create();
        AtomicBoolean settled = new AtomicBoolean(false);
        AtomicReference<EventHandler> holder = new AtomicReference<>((thisArg, args) -> null);

        Disposable dispose = root.on(EVENT, (thisArg, args) -> {
            holder.get().invoke(thisArg, args);
            return null;
        });
        holder.set((thisArg, args) -> {
            throw new RuntimeException("test");
        });
        root.on(EVENT, (thisArg, args) -> CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            settled.set(true);
            throw new RuntimeException("async");
        }));

        Throwable error = root.parallel(EVENT).handle((value, e) -> e).join();
        assertThat(error).hasRootCauseInstanceOf(AggregateError.class);
        assertThat(((AggregateError) error.getCause()).errors())
                .extracting(Throwable::getMessage)
                .containsExactlyInAnyOrder("test", "async");
        assertThat(settled).isTrue();
        dispose.dispose();
    }

    @Test
    void emit_shouldRespectThisArgFilterAndPropagateErrors() {
        Context root = Context.create();
        root.emit(EVENT);

        AtomicInteger calls = new AtomicInteger();
        root.extend(new Filter(true)).on(EVENT, (thisArg, args) -> {
            calls.incrementAndGet();
            return null;
        });

        root.emit(EVENT);
        assertThat(calls).hasValue(1);
        root.emit(new Session(false), EVENT);
        assertThat(calls).hasValue(1);
        root.emit(new Session(true), EVENT);
        assertThat(calls).hasValue(2);

        AtomicReference<EventHandler> holder = new AtomicReference<>((thisArg, args) -> null);
        Disposable dispose = root.on(EVENT, (thisArg, args) -> holder.get().invoke(thisArg, args));
        holder.set((thisArg, args) -> {
            throw new RuntimeException("test");
        });

        assertThatThrownBy(() -> root.emit(EVENT)).isInstanceOf(RuntimeException.class).hasMessage("test");
        dispose.dispose();
    }

    @Test
    void serial_shouldRespectThisArgFilterAndPropagateErrors() {
        Context root = Context.create();
        root.serial(EVENT).join();

        AtomicInteger calls = new AtomicInteger();
        root.extend(new Filter(true)).on(EVENT, (thisArg, args) -> {
            calls.incrementAndGet();
            return null;
        });

        root.serial(EVENT).join();
        assertThat(calls).hasValue(1);
        root.serial(new Session(false), EVENT).join();
        assertThat(calls).hasValue(1);
        root.serial(new Session(true), EVENT).join();
        assertThat(calls).hasValue(2);

        AtomicReference<EventHandler> holder = new AtomicReference<>((thisArg, args) -> null);
        Disposable dispose = root.on(EVENT, (thisArg, args) -> holder.get().invoke(thisArg, args));
        holder.set((thisArg, args) -> {
            throw new RuntimeException("message");
        });

        assertThatThrownBy(() -> root.serial(EVENT).join())
                .isInstanceOf(CompletionException.class)
                .hasRootCauseInstanceOf(RuntimeException.class)
                .hasRootCauseMessage("message");
        dispose.dispose();
    }

    @Test
    void bail_shouldRespectThisArgFilterAndPropagateErrors() {
        Context root = Context.create();
        root.bail(EVENT);

        AtomicInteger calls = new AtomicInteger();
        root.extend(new Filter(true)).on(EVENT, (thisArg, args) -> {
            calls.incrementAndGet();
            return null;
        });

        root.bail(EVENT);
        assertThat(calls).hasValue(1);
        root.bail(new Session(false), EVENT);
        assertThat(calls).hasValue(1);
        root.bail(new Session(true), EVENT);
        assertThat(calls).hasValue(2);

        AtomicReference<EventHandler> holder = new AtomicReference<>((thisArg, args) -> null);
        Disposable dispose = root.on(EVENT, (thisArg, args) -> holder.get().invoke(thisArg, args));
        holder.set((thisArg, args) -> {
            throw new RuntimeException("message");
        });

        assertThatThrownBy(() -> root.bail(EVENT)).isInstanceOf(RuntimeException.class).hasMessage("message");
        dispose.dispose();
    }

    @Test
    void waterfall_shouldChainCallbacksAndShortCircuit() {
        Context root = Context.create();
        AtomicInteger cb1 = new AtomicInteger();
        AtomicInteger cb2 = new AtomicInteger();
        AtomicInteger cb3 = new AtomicInteger();
        AtomicInteger cb4 = new AtomicInteger();

        root.on("test/waterfall", (thisArg, args) -> {
            cb1.incrementAndGet();
            int value = (Integer) args[0];
            @SuppressWarnings("unchecked")
            Supplier<Object> next = (Supplier<Object>) args[1];
            return value + (Integer) next.get();
        });
        root.on("test/waterfall", (thisArg, args) -> {
            cb2.incrementAndGet();
            int value = (Integer) args[0];
            @SuppressWarnings("unchecked")
            Supplier<Object> next = (Supplier<Object>) args[1];
            return value + (Integer) next.get();
        });

        assertThat(root.waterfall("test/waterfall", 1, args -> 2)).isEqualTo(4);
        assertThat(cb1).hasValue(1);
        assertThat(cb2).hasValue(1);

        root.on("test/waterfall", (thisArg, args) -> {
            cb3.incrementAndGet();
            return args[0];
        });
        root.on("test/waterfall", (thisArg, args) -> {
            cb4.incrementAndGet();
            int value = (Integer) args[0];
            @SuppressWarnings("unchecked")
            Supplier<Object> next = (Supplier<Object>) args[1];
            return value + (Integer) next.get();
        });

        assertThat(root.waterfall("test/waterfall", 1, args -> 2)).isEqualTo(3);
        assertThat(cb3).hasValue(1);
        assertThat(cb4).hasValue(0);
    }

    @Test
    void update_shouldRunFiberLocalUpdateListenersBeforeConfigUpdate() {
        Context root = Context.create();
        List<Object> seen = new ArrayList<>();

        root.on("internal/update", (thisArg, args) -> {
            seen.add(args[0]);
            @SuppressWarnings("unchecked")
            Supplier<Object> next = (Supplier<Object>) args[2];
            return next.get();
        }, EventOptions.of(false, false));

        root.fiber().update("new-config", false);

        assertThat(seen).containsExactly("new-config");
        assertThat(root.fiber().config()).isEqualTo("new-config");
    }
}