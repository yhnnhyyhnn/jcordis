package io.jcordis.loader;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * Cross-tree moves transfer ownership (mirrors Cordis #271): an entry moved
 * between a group's subtree and the root tree — with its nested members — is
 * removed from the source tree's store and adopted by the target tree's, and
 * the change is reported as a removal plus a creation instead of an update.
 */
class CrossTreeMoveTest {

    private static EntryOptions entry(String id, String name) {
        EntryOptions options = new EntryOptions();
        options.id = id;
        options.name = name;
        return options;
    }

    private static EntryOptions group(String id, List<EntryOptions> config) {
        EntryOptions options = entry(id, "@cordisjs/plugin-group");
        options.group = true;
        options.config = config;
        return options;
    }

    @Test
    void crossTreeMoveTransfersTheEntryAndReportsRemovalAndCreation() {
        Context root = Context.create();
        Loader loader = new Loader(root);
        loader.mock("demo", (ctx, cfg) -> null);
        loader.create(group("g", List.of(entry("inner", "demo"))), null);
        EntryTree subtree = loader.resolve("g").subtree;
        assertThat(subtree.store.get("inner")).isNotNull();

        List<EntryChange> changes = new CopyOnWriteArrayList<>();
        loader.addCommitListener(changes::add);

        // move the nested entry out of the group subtree into the root tree
        loader.transfer("g:inner", null);

        // ownership transferred: the subtree no longer resolves it, the root adopts it
        assertThat(subtree.store.get("inner"))
                .as("source tree gave the entry away")
                .isNull();
        Entry moved = loader.store.get("inner");
        assertThat(moved).as("target tree adopted the live entry").isNotNull();
        assertThat(moved.parent).isSameAs(loader.root);
        assertThat(moved.fiber).as("the live fiber moved, not a rebuild").isNotNull();
        // reported as a removal to the source tree and a creation to the target
        assertThat(changes).hasSize(2);
        assertThat(changes.get(0).options).isNull();
        assertThat(changes.get(0).legacy).isNotNull();
        assertThat(changes.get(1).options).isNotNull();
        assertThat(changes.get(1).legacy).isNull();
    }

    @Test
    void crossTreeMoveOfAGroupTransfersItsMembers() {
        Context root = Context.create();
        Loader loader = new Loader(root);
        loader.mock("demo", (ctx, cfg) -> null);
        loader.create(group("outer", List.of()), null);
        // a nested group inside outer's subtree
        loader.create(group("inner", List.of(entry("leaf", "demo"))), "outer");
        EntryTree subtree = loader.resolve("outer").subtree;
        assertThat(subtree.store.get("inner")).isNotNull();
        // the leaf lives in inner's OWN subtree (every group owns one)
        assertThat(loader.resolve("outer:inner").subtree.store.get("leaf")).isNotNull();

        // move the nested group itself to the root tree: the group entry
        // transfers ownership; its subtree (and the leaf in it) travels with
        // it and stays reachable through the moved entry
        loader.transfer("outer:inner", null);

        assertThat(subtree.store.get("inner"))
                .as("source tree gave the entry away")
                .isNull();
        assertThat(loader.store.get("inner")).isNotNull();
        Entry leaf = loader.resolve("inner:leaf");
        // the leaf entry stays reachable through the moved group (its fiber
        // lifecycle is the group-restart semantics, covered elsewhere)
        assertThat(leaf)
                .as("nested members stay reachable through the moved group")
                .isNotNull();
        assertThat(leaf.id()).isEqualTo("inner:leaf");
    }
}
