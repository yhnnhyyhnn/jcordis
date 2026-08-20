package io.jcordis.core.util;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import io.jcordis.core.util.Disposable;
import java.util.ArrayList;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Translates Cordis {@code @cordisjs/utils} List semantics: effect-scoped membership. */
class EffectListTest {

    @Test
    void push_shouldRegisterElementImmediately() {
        Context ctx = Context.create();
        EffectList<String> list = new EffectList<>(ctx, "test");

        list.push("a");
        list.push("b");

        assertThat(list.size()).isEqualTo(2);
        assertThat(list.stream().collect(Collectors.toList())).containsExactly("a", "b");
    }

    @Test
    void elements_shouldBeRemovedWhenFiberIsDisposed() {
        Context ctx = Context.create();
        EffectList<String> list = new EffectList<>(ctx, "test");
        list.push("a");
        list.push("b");
        assertThat(list.size()).isEqualTo(2);

        ctx.fiber().disposeAsync().join();

        assertThat(list.isEmpty()).isTrue();
    }

    @Test
    void disposable_shouldRemoveSingleElement() {
        Context ctx = Context.create();
        EffectList<String> list = new EffectList<>(ctx, "test");
        list.push("a");
        Disposable remove = list.push("b");

        remove.dispose();

        assertThat(list.size()).isEqualTo(1);
        assertThat(list.stream().collect(Collectors.toList())).containsExactly("a");
    }

    @Test
    void filterAndMap_shouldReturnLazyViews() {
        Context ctx = Context.create();
        EffectList<Integer> list = new EffectList<>(ctx, "test");
        list.push(1);
        list.push(2);
        list.push(3);

        assertThat(list.filter(i -> i % 2 == 1).collect(Collectors.toList())).containsExactly(1, 3);
        assertThat(list.map(i -> i * 10).collect(Collectors.toList())).containsExactly(10, 20, 30);
    }

    @Test
    void iterator_shouldYieldInsertionOrder() {
        Context ctx = Context.create();
        EffectList<String> list = new EffectList<>(ctx, "test");
        list.push("a");
        list.push("b");
        list.push("c");

        ArrayList<String> seen = new ArrayList<>();
        for (String value : list) {
            seen.add(value);
        }
        assertThat(seen).containsExactly("a", "b", "c");
    }
}
