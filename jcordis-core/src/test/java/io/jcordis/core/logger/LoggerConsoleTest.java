package io.jcordis.core.logger;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import io.jcordis.core.logger.Message;
import org.junit.jupiter.api.Test;

/** Renders messages through the console exporter. */
class LoggerConsoleTest {

    @Test
    void rendersTypePrefixAndName() {
        Context root = Context.create();
        ConsoleExporter exporter = new ConsoleExporter(root);
        String rendered = exporter.render(new Message(1, System.currentTimeMillis(), "my-app", "info", 2, new Object[] {"hello"}));
        assertThat(rendered).contains("[I]").contains("my-app").contains("hello");
    }

    @Test
    void rendersErrorPrefix() {
        Context root = Context.create();
        ConsoleExporter exporter = new ConsoleExporter(root);
        String rendered = exporter.render(new Message(1, System.currentTimeMillis(), "app", "error", 0, new Object[] {"boom"}));
        assertThat(rendered).contains("[E]").contains("boom");
    }

    @Test
    void formatsPlaceholders() {
        Context root = Context.create();
        ConsoleExporter exporter = new ConsoleExporter(root);
        String rendered = exporter.render(
                new Message(1, System.currentTimeMillis(), "app", "info", 2, new Object[] {"value: %d", 42}));
        assertThat(rendered).contains("value: 42");
    }

    @Test
    void colorsDisabled() {
        Context root = Context.create();
        ConsoleExporter.Config config = new ConsoleExporter.Config();
        config.colors = 0;
        ConsoleExporter exporter = new ConsoleExporter(root, config);
        String rendered = exporter.render(new Message(1, System.currentTimeMillis(), "app", "info", 2, new Object[] {"msg"}));
        assertThat(rendered).doesNotContain("\u001b[");
    }

    @Test
    void colorsEnabled() {
        Context root = Context.create();
        ConsoleExporter.Config config = new ConsoleExporter.Config();
        config.colors = 2;
        ConsoleExporter exporter = new ConsoleExporter(root, config);
        String rendered = exporter.render(new Message(1, System.currentTimeMillis(), "app", "info", 2, new Object[] {"msg"}));
        assertThat(rendered).contains("\u001b[");
    }
}