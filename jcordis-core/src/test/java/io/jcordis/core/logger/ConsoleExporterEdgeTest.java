package io.jcordis.core.logger;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import org.junit.jupiter.api.Test;

/**
 * Boundary coverage for the console exporter's rendering switches: timestamp,
 * label padding and the per-message time diff.
 */
class ConsoleExporterEdgeTest {

    private static Message message(String type, int level, Object... args) {
        return new Message(1, System.currentTimeMillis(), "app", type, level, args);
    }

    @Test
    void timestampShouldBeOmittedWhenDisabled() {
        ConsoleExporter.Config config = new ConsoleExporter.Config();
        config.showTime = "";
        config.colors = 0;
        ConsoleExporter exporter = new ConsoleExporter(Context.create(), config);

        String rendered = exporter.render(message("info", 2, "hello"));

        assertThat(rendered).isEqualTo("[I] app hello");
    }

    @Test
    void timestampShouldBeRenderedWhenEnabled() {
        ConsoleExporter.Config config = new ConsoleExporter.Config();
        config.showTime = "HH:mm:ss";
        ConsoleExporter exporter = new ConsoleExporter(Context.create(), config);

        String rendered = exporter.render(message("info", 2, "hello"));

        assertThat(rendered).containsPattern("\\d{2}:\\d{2}:\\d{2}");
    }

    @Test
    void labelShouldBePaddedToTheConfiguredWidth() {
        ConsoleExporter.Config config = new ConsoleExporter.Config();
        config.labelWidth = 10;
        config.showTime = "";
        config.colors = 0;
        ConsoleExporter exporter = new ConsoleExporter(Context.create(), config);

        String rendered = exporter.render(message("info", 2, "hello"));

        assertThat(rendered).isEqualTo("[I] app        hello");
    }

    @Test
    void diffShouldBeAppendedWhenEnabled() {
        ConsoleExporter.Config config = new ConsoleExporter.Config();
        config.showTime = "";
        config.showDiff = true;
        config.colors = 0;
        ConsoleExporter exporter = new ConsoleExporter(Context.create(), config);

        String rendered = exporter.render(message("info", 2, "hello"));

        assertThat(rendered).containsPattern("hello \\+\\d+ms");
    }

    @Test
    void typePrefixShouldUseTheFirstLetterOfAnyType() {
        ConsoleExporter.Config config = new ConsoleExporter.Config();
        config.showTime = "";
        config.colors = 0;
        ConsoleExporter exporter = new ConsoleExporter(Context.create(), config);

        assertThat(exporter.render(message("warn", 1, "careful"))).startsWith("[W]");
        assertThat(exporter.render(message("debug", 3, "detail"))).startsWith("[D]");
    }

    @Test
    void defaultConstructorShouldUseAWorkingConfiguration() {
        Context root = Context.create();
        ConsoleExporter exporter = new ConsoleExporter(root);

        assertThat(exporter.colors()).isEqualTo(2);
        assertThat(exporter.render(message("info", 2, "hello"))).contains("app").contains("hello");
        assertThat(root.loggerService().exporters())
                .as("registered on the context")
                .isNotEmpty();
    }
}
