package io.jcordis.loader.expr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The whitelisted expression evaluator and {@code ${}} interpolation — the
 * safe counterpart of Cordis's {@code !!js} config expressions. The syntax
 * (literals, scoped property chains, comparisons, arithmetic, logic, ternary)
 * is the security boundary: no method calls, no reflection, no types.
 */
class EvaluatorTest {

    private static final Map<String, Object> SCOPE = Map.of(
            "env", Map.of("PROD", "true", "COUNT", "3", "NAME", "jcordis"),
            "prop", Map.of("region", "cn"),
            "n", 10);

    private static Object eval(String expression) {
        return Evaluator.evaluate(expression, SCOPE);
    }

    @Test
    void literalsAndArithmetic() {
        assertThat(eval("1 + 2 * 3")).isEqualTo(7.0);
        assertThat(eval("(1 + 2) * 3")).isEqualTo(9.0);
        assertThat(eval("10 % 3")).isEqualTo(1.0);
        assertThat(eval("-n + 1")).isEqualTo(-9.0);
        assertThat(eval("'a' + 'b'")).isEqualTo("ab");
        assertThat(eval("'n=' + n")).isEqualTo("n=10");
        assertThat(eval("1 == 1.0")).isEqualTo(true);
    }

    @Test
    void propertyChainsResolveFromTheScope() {
        assertThat(eval("env.PROD")).isEqualTo("true");
        assertThat(eval("env.PROD == 'true'")).isEqualTo(true);
        assertThat(eval("env.COUNT >= 3")).isEqualTo(true);
        assertThat(eval("prop.region + '-' + env.NAME")).isEqualTo("cn-jcordis");
        // a missing link reads as null: absent env vars compare false, no error
        assertThat(eval("env.MISSING == 'x'")).isEqualTo(false);
        assertThat(eval("env.MISSING")).isNull();
    }

    @Test
    void logicAndTernary() {
        assertThat(eval("env.PROD == 'true' && n > 5")).isEqualTo(true);
        assertThat(eval("!false")).isEqualTo(true);
        assertThat(eval("false || env.COUNT == 3")).isEqualTo(true);
        assertThat(eval("env.COUNT >= 3 ? 'many' : 'few'")).isEqualTo("many");
        // JS-style short-circuit keeps a null check safe
        assertThat(eval("env.MISSING != null && env.MISSING.length > 0 || 'default'"))
                .isEqualTo("default");
    }

    @Test
    void keywordsAndNull() {
        assertThat(eval("true")).isEqualTo(true);
        assertThat(eval("false || true")).isEqualTo(true);
        assertThat(eval("null")).isNull();
        assertThat(eval("null == null")).isEqualTo(true);
    }

    @Test
    void syntaxErrorsAreRejected() {
        assertThatThrownBy(() -> eval("System.exit(1)"))
                .as("method calls are outside the whitelist")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> eval("1 = 2")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> eval("1 & 2")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> eval("'unterminated")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> eval("1 +")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> eval("1 2")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> eval("'a' < 1"))
                .as("string vs number does not compare")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void interpolationExpandsPlaceholders() {
        assertThat(Interpolator.expand("hello ${env.NAME}", SCOPE)).isEqualTo("hello jcordis");
        assertThat(Interpolator.expand("${env.COUNT}", SCOPE))
                .as("a single whole-string placeholder takes the runtime type (a string stays a string)")
                .isEqualTo("3");
        assertThat(Interpolator.expand("${1 + 2}", SCOPE))
                .as("a numeric expression keeps its number type")
                .isEqualTo(3.0);
        assertThat(Interpolator.expand("plain", SCOPE)).isEqualTo("plain");
        assertThat(Interpolator.expand("no placeholders", SCOPE)).isEqualTo("no placeholders");
        assertThat(Interpolator.expand("count=${env.COUNT} region=${prop.region}", SCOPE))
                .isEqualTo("count=3 region=cn");
        assertThat(Interpolator.expand("literal $${env.NAME} stays", SCOPE))
                .as("$${ escapes to a literal ${")
                .isEqualTo("literal ${env.NAME} stays");
    }

    @Test
    void interpolationExpandsDeepValues() {
        Object expanded = Interpolator.expandValue(
                java.util.Map.of("greeting", "hi ${env.NAME}", "nested", java.util.List.of(1, "${env.COUNT}")), SCOPE);

        assertThat(expanded).isEqualTo(java.util.Map.of("greeting", "hi jcordis", "nested", java.util.List.of(1, "3")));
    }
}
