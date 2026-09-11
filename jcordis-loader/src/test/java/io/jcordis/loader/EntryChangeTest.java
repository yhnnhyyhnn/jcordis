package io.jcordis.loader;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tree-side mutations report structured {@link EntryChange}s to
 * {@link EntryTree#commit(EntryChange)} (mirrors Cordis's {@code EntryChange}):
 * a persistence layer such as {@code Include} relies on it to write runtime
 * changes back to the config file.
 */
class EntryChangeTest {

    private static EntryOptions options(String id, String name, Object config) {
        EntryOptions options = new EntryOptions();
        options.id = id;
        options.name = name;
        options.config = config;
        return options;
    }

    @Test
    void createShouldReportCreatedChange() {
        Context root = Context.create();
        Loader loader = new Loader(root);
        loader.builtin("demo", (ctx, config) -> null);
        List<EntryChange> changes = new ArrayList<>();
        loader.addCommitListener(changes::add);

        loader.create(options("a", "demo", Map.of("v", 1)), null);

        assertThat(changes).hasSize(1);
        EntryChange change = changes.get(0);
        assertThat(change.id).isEqualTo("a");
        assertThat(change.isCreated()).isTrue();
        assertThat(change.isRemoved()).isFalse();
        assertThat(change.group).isSameAs(loader.root);
        assertThat(change.options.config).isEqualTo(Map.of("v", 1));
    }

    @Test
    void updateShouldReportLegacyAndNewOptions() {
        Context root = Context.create();
        Loader loader = new Loader(root);
        loader.builtin("demo", (ctx, config) -> null);
        loader.create(options("a", "demo", Map.of("v", 1)), null);
        List<EntryChange> changes = new ArrayList<>();
        loader.addCommitListener(changes::add);

        EntryOptions next = new EntryOptions();
        next.config = Map.of("v", 2);
        loader.update("a", next, null);

        assertThat(changes).hasSize(1);
        EntryChange change = changes.get(0);
        assertThat(change.isCreated()).isFalse();
        assertThat(change.isRemoved()).isFalse();
        assertThat(change.legacy.config).isEqualTo(Map.of("v", 1));
        assertThat(change.options.config).isEqualTo(Map.of("v", 2));
    }

    @Test
    void removeShouldReportRemovalWithLegacy() {
        Context root = Context.create();
        Loader loader = new Loader(root);
        loader.builtin("demo", (ctx, config) -> null);
        loader.create(options("a", "demo", null), null);
        List<EntryChange> changes = new ArrayList<>();
        loader.addCommitListener(changes::add);

        loader.remove("a");

        assertThat(changes).hasSize(1);
        EntryChange change = changes.get(0);
        assertThat(change.isRemoved()).isTrue();
        assertThat(change.options).isNull();
        assertThat(change.legacy.name).isEqualTo("demo");
    }

    @Test
    void transferShouldReportMovedChange() {
        Context root = Context.create();
        Loader loader = new Loader(root);
        loader.builtin("demo", (ctx, config) -> null);
        EntryOptions group = options("g", "@cordisjs/plugin-group", new ArrayList<EntryOptions>());
        group.group = true;
        loader.create(group, null);
        loader.create(options("a", "demo", null), null);
        List<EntryChange> changes = new ArrayList<>();
        loader.addCommitListener(changes::add);

        loader.transfer("a", "g");

        // moving across trees reports twice (mirrors Cordis): the source tree
        // sees a removal, the target tree sees the moved entry
        assertThat(changes).hasSize(2);
        assertThat(changes.get(0).isRemoved()).isTrue();
        assertThat(changes.get(0).group).isSameAs(loader.root);
        EntryChange change = changes.get(1);
        assertThat(change.isMoved()).isTrue();
        assertThat(change.from).isSameAs(loader.root);
        assertThat(change.group).isSameAs(loader.resolveGroup("g"));
    }

    @Test
    void listenersOnTheRootTreeShouldSeeSubtreeChanges() {
        Context root = Context.create();
        Loader loader = new Loader(root);
        loader.builtin("demo", (ctx, config) -> null);
        EntryOptions group = options("g", "@cordisjs/plugin-group", new ArrayList<EntryOptions>());
        group.group = true;
        loader.create(group, null);
        List<EntryChange> changes = new ArrayList<>();
        loader.addCommitListener(changes::add);

        // creating inside the group's subtree must reach root-tree listeners
        loader.resolveGroup("g").tree.create(options("child", "demo", null), null);

        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).id).isEqualTo("child");
        assertThat(changes.get(0).isCreated()).isTrue();
    }

    @Test
    void removedListenerShouldStopReceivingChanges() {
        Context root = Context.create();
        Loader loader = new Loader(root);
        loader.builtin("demo", (ctx, config) -> null);
        List<EntryChange> changes = new ArrayList<>();
        EntryTree.CommitListener listener = changes::add;
        loader.addCommitListener(listener);
        loader.create(options("a", "demo", null), null);

        loader.removeCommitListener(listener);
        loader.create(options("b", "demo", null), null);

        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).id).isEqualTo("a");
    }
}
