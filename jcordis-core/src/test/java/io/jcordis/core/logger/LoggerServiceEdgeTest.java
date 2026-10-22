package io.jcordis.core.logger;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import io.jcordis.core.event.AggregateError;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Boundary coverage for the logger service: level thresholds (per name and
 * default), aggregate-error expansion and buffer trimming.
 */
class LoggerServiceEdgeTest {

    private static Exporter collecting(List<Message> sink, Map<String, Integer> levels) {
        return new Exporter() {
            @Override
            public void export(Message message) {
                sink.add(message);
            }

            @Override
            public Map<String, Integer> levels() {
                return levels;
            }
        };
    }

    @Test
    void messagesBelowTheThresholdShouldBeDropped() {
        Context root = Context.create();
        List<Message> captured = new ArrayList<>();
        root.loggerService().exporter(collecting(captured, Map.of()));

        root.logger("app").debug("hidden");
        root.logger("app").info("shown");
        root.logger("app").error("always");

        assertThat(captured)
                .extracting(Message::args)
                .extracting(args -> args[0])
                .containsExactly("shown", "always");
    }

    @Test
    void perNameThresholdsShouldOverrideTheDefault() {
        Context root = Context.create();
        List<Message> captured = new ArrayList<>();
        root.loggerService().exporter(collecting(captured, Map.of("chatty", LoggerLevel.DEBUG.value())));

        root.logger("chatty").debug("visible");
        root.logger("quiet").debug("hidden");

        assertThat(captured).extracting(Message::name).containsExactly("chatty");
    }

    @Test
    void defaultThresholdShouldApplyToUnnamedLoggers() {
        Context root = Context.create();
        List<Message> captured = new ArrayList<>();
        root.loggerService().exporter(collecting(captured, Map.of("default", LoggerLevel.ERROR.value())));

        root.logger("app").warn("dropped");
        root.logger("app").error("kept");

        assertThat(captured)
                .extracting(Message::args)
                .extracting(args -> args[0])
                .containsExactly("kept");
    }

    @Test
    void aggregateErrorsShouldBeExpandedIntoOneMessageEach() {
        Context root = Context.create();
        List<Message> captured = new ArrayList<>();
        root.loggerService().exporter(collecting(captured, Map.of()));

        AggregateError aggregate =
                new AggregateError(List.of(new IllegalStateException("first"), new IllegalArgumentException("second")));
        root.logger("app").error(aggregate);

        assertThat(captured).hasSize(2);
        assertThat(captured.get(0).args()[0]).isInstanceOf(IllegalStateException.class);
        assertThat(captured.get(1).args()[0]).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bufferShouldTrimEverythingAboveItsLimit() {
        Context root = Context.create();
        root.loggerService().setBufferSize(0);
        List<Message> buffer = root.loggerService().buffer();

        root.logger("app").info("one");
        root.logger("app").info("two");

        // a zero-size buffer trims the whole backlog in one go
        assertThat(buffer).isEmpty();
        assertThat(root.loggerService().bufferSize()).isZero();
    }

    @Test
    void bufferShouldKeepTheConfiguredWindow() {
        Context root = Context.create();
        root.loggerService().setBufferSize(2);

        root.logger("app").info("one");
        root.logger("app").info("two");
        root.logger("app").info("three");

        assertThat(root.loggerService().buffer())
                .extracting(Message::args)
                .extracting(args -> args[0])
                .containsExactly("two", "three");
    }

    @Test
    void interceptLevelShouldGateWithoutExporterConfig() {
        Context root = Context.create();
        List<Message> captured = new ArrayList<>();
        root.loggerService().exporter(collecting(captured, Map.of()));

        // a logger whose intercept sets the level to DEBUG(3) sees debug; the
        // plain one keeps the INFO default — the threshold chain is
        // per-name exporter config, exporter default, logger level, INFO
        Context verbose = root.intercept("logger", Map.of("level", LoggerLevel.DEBUG.value()));
        verbose.logger().debug("from-verbose");
        root.logger().debug("from-plain");

        assertThat(captured)
                .extracting(Message::args)
                .extracting(args -> args[0])
                .containsExactly("from-verbose");
    }

    @Test
    void interceptLevelShouldLoseToExporterDefaults() {
        Context root = Context.create();
        List<Message> captured = new ArrayList<>();
        // exporter default pins the threshold at WARN(1): a verbose logger
        // intercept cannot lower it below the exporter's own default
        root.loggerService().exporter(collecting(captured, Map.of("default", LoggerLevel.WARN.value())));

        Context verbose = root.intercept("logger", Map.of("level", LoggerLevel.DEBUG.value()));
        verbose.logger().info("dropped");
        verbose.logger().warn("kept");

        assertThat(captured)
                .extracting(Message::args)
                .extracting(args -> args[0])
                .containsExactly("kept");
    }
}
