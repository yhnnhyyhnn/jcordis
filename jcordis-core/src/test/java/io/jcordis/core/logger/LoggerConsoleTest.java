package io.jcordis.core.logger;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import org.junit.jupiter.api.Test;

/** Renders messages through the console exporter. */
class LoggerConsoleTest {

    @Test
    void rendersTypePrefixAndName() {
        Context root = Context.create();
        ConsoleExporter exporter = new ConsoleExporter(root);
        String rendered = exporter.render(
                new Message(1, System.currentTimeMillis(), "my-app", "info", 2, new Object[] {"hello"}));
        assertThat(rendered).contains("[I]").contains("my-app").contains("hello");
    }

    @Test
    void rendersErrorPrefix() {
        Context root = Context.create();
        ConsoleExporter exporter = new ConsoleExporter(root);
        String rendered =
                exporter.render(new Message(1, System.currentTimeMillis(), "app", "error", 0, new Object[] {"boom"}));
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
        String rendered =
                exporter.render(new Message(1, System.currentTimeMillis(), "app", "info", 2, new Object[] {"msg"}));
        assertThat(rendered).doesNotContain("\u001b[");
    }

    @Test
    void colorsEnabled() {
        Context root = Context.create();
        ConsoleExporter.Config config = new ConsoleExporter.Config();
        config.colors = 2;
        ConsoleExporter exporter = new ConsoleExporter(root, config);
        String rendered =
                exporter.render(new Message(1, System.currentTimeMillis(), "app", "info", 2, new Object[] {"msg"}));
        assertThat(rendered).contains("\u001b[");
    }

    @Test
    void percentC_colorsValueWithNameCode() {
        Context root = Context.create();
        ConsoleExporter.Config config = new ConsoleExporter.Config();
        config.colors = 2;
        ConsoleExporter exporter = new ConsoleExporter(root, config);
        String rendered = exporter.render(new Message(
                1, System.currentTimeMillis(), "my-app", "info", 2, new Object[] {"%s plugin %C", "apply", "demo"}));
        // the %C value must be rendered (not swallowed) and colored
        assertThat(rendered).contains("apply plugin").contains("demo").contains("\u001b[");
    }

    @Test
    void percentC_withColorsDisabled_rendersPlainValue() {
        Context root = Context.create();
        ConsoleExporter.Config config = new ConsoleExporter.Config();
        config.colors = 0;
        ConsoleExporter exporter = new ConsoleExporter(root, config);
        String rendered = exporter.render(
                new Message(1, System.currentTimeMillis(), "my-app", "info", 2, new Object[] {"plugin %C", "demo"}));
        assertThat(rendered).contains("plugin demo").doesNotContain("\u001b[");
    }
}
