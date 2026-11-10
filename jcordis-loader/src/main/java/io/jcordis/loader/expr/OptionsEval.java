package io.jcordis.loader.expr;

import io.jcordis.loader.EntryOptions;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Expands {@code ${...}} expressions inside entry options through the
 * whitelisted evaluator — the safe counterpart of Cordis's evaluated entry
 * options ({@code !!js} / interpolate). Applied once, when the options enter
 * the tree (read/update), which matches jcordis's resolved-data model.
 *
 * <p>Expanded fields: {@code disabled} and {@code group} accept boolean
 * expressions as strings ("${env.PROD == 'true'}"); the {@code config} tree
 * and the values of {@code inject}/{@code intercept}/{@code isolate}
 * interpolate their string leaves. {@code id} and {@code name} stay literal —
 * they are structural. The default scope exposes {@code env} and {@code prop}
 * over the loader's evaluation scope.
 */
public final class OptionsEval {

    private OptionsEval() {}

    /** Returns {@code options} with every supported field expanded in place. */
    public static EntryOptions apply(EntryOptions options, Map<String, Object> scope) {
        if (options == null) return null;
        options.disabled = booleanOf(options.disabled, scope, "disabled");
        options.group = booleanOf(options.group, scope, "group");
        options.config = Interpolator.expandValue(options.config, scope);
        options.inject = valueMapOf(options.inject, scope);
        options.intercept = valueMapOf(options.intercept, scope);
        options.isolate = valueMapOf(options.isolate, scope);
        return options;
    }

    /**
     * A boolean field given as an expression string ("${env.X == 'true'}")
     * evaluates to its boolean result; a plain Boolean passes through; null
     * stays null. A string without placeholders did not evaluate to a boolean
     * and is reported as a configuration error.
     */
    private static Object booleanOf(Object field, Map<String, Object> scope, String what) {
        if (field instanceof String expression) {
            Object value = Interpolator.expand(expression, scope);
            if (value instanceof Boolean bool) {
                return bool;
            }
            throw new IllegalArgumentException("entry option '" + what + "' expression did not evaluate to a boolean: "
                    + expression + " -> " + value);
        }
        return field;
    }

    private static Map<String, Object> valueMapOf(Map<String, Object> map, Map<String, Object> scope) {
        if (map == null) return null;
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            // keys stay literal (they address services); only values expand
            copy.put(entry.getKey(), Interpolator.expandValue(entry.getValue(), scope));
        }
        return copy;
    }
}
