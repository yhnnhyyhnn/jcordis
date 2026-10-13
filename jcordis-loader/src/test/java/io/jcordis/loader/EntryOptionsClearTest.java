package io.jcordis.loader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jcordis.core.context.Context;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Regressions for the loader alignment items: explicit field clearing on
 * runtime updates (Cordis deletes a null option; Java marks it with
 * {@link EntryOptions#clear}), and unconditional parent prefixes in
 * {@link Entry#id()} so a child sharing its group's id stays addressable.
 */
class EntryOptionsClearTest {

    private static EntryOptions entry(String id, String name) {
        EntryOptions options = new EntryOptions();
        options.id = id;
        options.name = name;
        return options;
    }

    @Test
    void mergeShouldDeleteClearedFields() {
        EntryOptions target = entry("a", "demo");
        target.intercept = Map.of("demo", Map.of("base", "url"));
        target.config = Map.of("v", 1);

        target.merge(new EntryOptions().clear("intercept"));

        assertThat(target.intercept).isNull();
        assertThat(target.config).isEqualTo(Map.of("v", 1));
        assertThat(target.id).isEqualTo("a");
    }

    @Test
    void clearShouldRemoveARuntimeOption() {
        Context root = Context.create();
        Loader loader = new Loader(root);
        loader.mock("demo", (ctx, cfg) -> null);
        EntryOptions options = entry("a", "demo");
        options.intercept = Map.of("demo", Map.of("base", "url"));
        loader.create(options, null);
        assertThat(loader.resolve("a").options.intercept).isNotNull();

        loader.update("a", new EntryOptions().clear("intercept"), null);

        assertThat(loader.resolve("a").options.intercept).isNull();
        assertThat(loader.resolve("a").fiber.ctx().interceptConfig("demo")).isNull();
    }

    @Test
    void clearShouldRejectUnknownKeys() {
        assertThatThrownBy(() -> new EntryOptions().clear("nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown entry option");
    }

    @Test
    void childSharingItsGroupIdShouldCarryThePrefix() {
        Context root = Context.create();
        Loader loader = new Loader(root);
        loader.mock("demo", (ctx, cfg) -> null);

        EntryOptions group = entry("g", "@cordisjs/plugin-group");
        group.group = true;
        group.config = new java.util.ArrayList<>(List.of(entry("g", "demo")));
        loader.create(group, null);

        // the child keeps its own address even when the id matches the group's
        assertThat(loader.resolve("g").isGroup()).isTrue();
        Entry child = loader.resolve("g:g");
        assertThat(child).isNotNull();
        assertThat(child.fiber).isNotNull();
    }
}
