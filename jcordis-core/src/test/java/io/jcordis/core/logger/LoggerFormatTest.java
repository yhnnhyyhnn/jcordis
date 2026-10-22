package io.jcordis.core.logger;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Boundary coverage for the log formatter: every placeholder strategy, argument
 * arity mismatches, throwable/first-argument handling, line truncation and the
 * ANSI color rules.
 */
class LoggerFormatTest {

    private static Exporter exporter(int colors, int maxLength) {
        return new Exporter() {
            @Override
            public void export(Message message) {}

            @Override
            public int colors() {
                return colors;
            }

            @Override
            public int maxLength() {
                return maxLength;
            }
        };
    }

    private static Message message(String name, Object... args) {
        return new Message(1, System.currentTimeMillis(), name, "info", 2, args);
    }

    @Test
    void everyPlaceholderShouldUseItsFormatter() {
        String rendered = Logger.format(
                exporter(0, 10240),
                message("app", "%s|%d|%i|%f|%o|%O|%c", "text", "42.7", "3.9", "1.5", null, "obj", "ignored"));

        // %o/%O render as JSON (mirrors Cordis's JSON.stringify formatter)
        assertThat(rendered).isEqualTo("text|42|3|1.5|null|\"obj\"|");
    }

    @Test
    void objectPlaceholderShouldRenderAsJson() {
        String rendered = Logger.format(
                exporter(0, 10240),
                message("app", "%o", java.util.Map.of("a", 1, "list", java.util.Arrays.asList(true, null, "x"))));

        assertThat(rendered)
                .as("maps, arrays and iterables expand like JSON.stringify")
                .isEqualTo("{\"a\":1,\"list\":[true,null,\"x\"]}");
    }

    @Test
    void deepObjectPlaceholderShouldAbbreviateInsteadOfLooping() {
        java.util.Map<Object, Object> cyclic = new java.util.HashMap<>();
        cyclic.put("self", cyclic);

        String rendered = Logger.format(exporter(0, 10240), message("app", "%o", cyclic));

        assertThat(rendered).as("cyclic structures abbreviate, never throw").contains("…");
    }

    @Test
    void exporterFormatterShouldOverrideTheDefault() {
        io.jcordis.core.logger.Exporter exporter = new io.jcordis.core.logger.Exporter() {
            @Override
            public void export(io.jcordis.core.logger.Message message) {}

            @Override
            public java.util.Map<String, io.jcordis.core.logger.Exporter.ValueFormatter> formatters() {
                return java.util.Map.of("o", (value, e, m) -> "<" + value + ">");
            }
        };

        String rendered = Logger.format(exporter, message("app", "%o", "obj"));

        assertThat(rendered)
                .as("an exporter strategy wins over the default (mirrors Cordis)")
                .isEqualTo("<obj>");
    }

    @Test
    void unknownPlaceholderShouldBeKeptVerbatim() {
        String rendered = Logger.format(exporter(0, 10240), message("app", "%x", "value"));

        assertThat(rendered).isEqualTo("%xvalue");
    }

    @Test
    void percentEscapeShouldRenderALiteralPercent() {
        assertThat(Logger.format(exporter(0, 10240), message("app", "100%% done")))
                .isEqualTo("100% done");
    }

    @Test
    void missingArgumentsShouldRenderAsNull() {
        assertThat(Logger.format(exporter(0, 10240), message("app", "%s and %s")))
                .isEqualTo("null and null");
    }

    @Test
    void trailingArgumentsShouldBeAppended() {
        String rendered = Logger.format(exporter(0, 10240), message("app", "loaded", "plain", 42, null));

        // strings are appended as-is, other values go through %o, null stays null
        assertThat(rendered).isEqualTo("loaded plain 42 null");
    }

    @Test
    void throwableAsFirstArgumentShouldContributeItsStack() {
        String rendered = Logger.format(exporter(0, 10240), message("app", new IllegalStateException("boom")));

        // the exception class and stack travel with the message — a bare
        // "boom" loses everything needed to debug (mirrors Cordis's stack)
        assertThat(rendered).startsWith("java.lang.IllegalStateException: boom").contains("\n    at ");
    }

    @Test
    void nonStringFirstArgumentShouldBeFormattedAsObject() {
        String rendered = Logger.format(exporter(0, 10240), message("app", 42, "more"));

        assertThat(rendered).isEqualTo("42 more");
    }

    @Test
    void longLinesShouldBeTruncated() {
        String rendered = Logger.format(exporter(0, 3), message("app", "abcdefgh"));

        assertThat(rendered).isEqualTo("abc...");
    }

    @Test
    void multilineMessagesShouldBeTruncatedPerLine() {
        String rendered = Logger.format(exporter(0, 3), message("app", "abcdef\nxy\n12345"));

        assertThat(rendered).isEqualTo("abc...\nxy\n123...");
    }

    @Test
    void colorShouldHonourTheExporterDepth() {
        assertThat(Logger.color(exporter(0, 10240), 3, "x")).as("colors off").isEqualTo("x");
        assertThat(Logger.color(exporter(1, 10240), 3, "x")).isEqualTo("\u001b[33mx\u001b[0m");
        assertThat(Logger.color(exporter(1, 10240), 100, "x"))
                .as("256-color code")
                .isEqualTo("\u001b[38;5;100mx\u001b[0m");
        assertThat(Logger.color(exporter(2, 10240), 3, "x", ";1")).isEqualTo("\u001b[33;1mx\u001b[0m");
    }

    @Test
    void colorCodeShouldPickThePaletteForTheLevel() {
        int plain = Logger.code("app", 1);
        int full = Logger.code("app", 2);

        assertThat(plain).as("16-color palette index").isIn(6, 2, 3, 4, 5, 1);
        assertThat(full).as("256-color palette value").isBetween(20, 221);
        assertThat(Logger.code("app", 2)).as("stable for the same name").isEqualTo(full);
        assertThat(Logger.code("other", 2))
                .as("different names hash differently")
                .isNotEqualTo(full);
    }
}
