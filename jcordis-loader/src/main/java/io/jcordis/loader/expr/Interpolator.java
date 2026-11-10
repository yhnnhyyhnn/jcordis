package io.jcordis.loader.expr;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Expands {@code ${expression}} placeholders inside config strings through the
 * whitelisted {@link Evaluator} — the safe counterpart of Cordis's config
 * interpolation ({@code with(ctx){eval}}). A string consisting of exactly one
 * placeholder takes the expression's runtime type (a boolean stays a boolean);
 * anything else concatenates the parts.
 *
 * <p>{@code $${} escapes to a literal {@code ${}}. Malformed expressions throw
 * with the offending fragment named; strings without placeholders are returned
 * untouched.
 */
public final class Interpolator {

    private Interpolator() {}

    /** Expands every placeholder in {@code text} against {@code scope}. */
    public static Object expand(String text, Map<String, Object> scope) {
        if (text == null || text.indexOf("${") < 0) {
            return text;
        }
        StringBuilder literal = new StringBuilder();
        boolean singleWholeExpression = false;
        Object wholeValue = null;
        int index = 0;
        while (index < text.length()) {
            char c = text.charAt(index);
            if (c == '$'
                    && index + 1 < text.length()
                    && text.charAt(index + 1) == '$'
                    && index + 2 < text.length()
                    && text.charAt(index + 2) == '{') {
                // $${ escapes to a literal ${
                literal.append("${");
                index += 3;
                continue;
            }
            if (c == '$' && index + 1 < text.length() && text.charAt(index + 1) == '{') {
                int end = findExpressionEnd(text, index + 2);
                if (end < 0) {
                    throw new IllegalArgumentException("unterminated ${ in: " + text);
                }
                String expression = text.substring(index + 2, end);
                Object value = Evaluator.evaluate(expression, scope);
                literal.append(Evaluator.stringify(value));
                if (index == 0 && end == text.length() - 1) {
                    singleWholeExpression = true;
                    wholeValue = value;
                }
                index = end + 1;
                continue;
            }
            literal.append(c);
            index++;
        }
        if (singleWholeExpression) {
            return wholeValue;
        }
        return literal.toString();
    }

    private static int findExpressionEnd(String text, int from) {
        int depth = 1;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** Deeply expands a config value: string leaves interpolate, maps and lists recurse. */
    public static Object expandValue(Object value, Map<String, Object> scope) {
        if (value instanceof String text) {
            return expand(text, scope);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), expandValue(entry.getValue(), scope));
            }
            return copy;
        }
        if (value instanceof java.util.List<?> list) {
            java.util.List<Object> copy = new java.util.ArrayList<>();
            for (Object item : list) {
                copy.add(expandValue(item, scope));
            }
            return copy;
        }
        return value;
    }
}
