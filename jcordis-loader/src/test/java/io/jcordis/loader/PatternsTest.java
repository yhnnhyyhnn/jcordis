package io.jcordis.loader;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Verifies design-pattern APIs: Builder, Memento, Command, Strategy. */
class PatternsTest {

    @Test
    void builderBuildsOptions() {
        EntryOptions options = new EntryOptions.Builder()
                .id("svc")
                .name("feature")
                .config(Map.of("enabled", true))
                .disabled(false)
                .build();

        assertThat(options.id).isEqualTo("svc");
        assertThat(options.name).isEqualTo("feature");
        assertThat(options.config).isEqualTo(Map.of("enabled", true));
        assertThat(options.disabled).isFalse();
    }

    @Test
    void snapshotRestoresOptions() {
        EntryOptions options = new EntryOptions.Builder().id("a").config(1).build();
        EntryOptions.Snapshot snapshot = options.snapshot();

        options.config = 2;
        options.name = "changed";
        assertThat(options.config).isEqualTo(2);

        snapshot.restore(options);
        assertThat(options.config).isEqualTo(1);
        assertThat(options.name).isNull();
    }

    @Test
    void treeCommandsExecute() {
        Context root = Context.create();
        Loader loader = new Loader(root);
        loader.mock("feature", (ctx, config) -> null);

        EntryOptions options =
                new EntryOptions.Builder().id("cmd").name("feature").build();
        loader.execute(Command.TreeCommand.create(options, null));
        assertThat(loader.resolve("cmd")).isNotNull();

        loader.execute(Command.TreeCommand.update(
                "cmd", new EntryOptions.Builder().disabled(true).build(), null));
        assertThat(loader.resolve("cmd").options.disabled).isTrue();

        loader.execute(Command.TreeCommand.remove("cmd"));
        assertThat(loader.store.containsKey("cmd")).isFalse();
    }
}
