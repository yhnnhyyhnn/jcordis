package io.jcordis.loader.expr;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Whitelisted expression evaluator — the safe counterpart of Cordis's
 * {@code !!js} config expressions. The grammar is a fixed, small subset:
 * literals, scoped property chains, comparisons, arithmetic, logic and the
 * ternary operator. There are no method calls, no reflection and no type
 * references: the syntax itself is the security boundary.
 *
 * <pre>
 * env.PROD == 'true'            property chain over the evaluation scope
 * 1 + 2 * 3                     arithmetic (numbers form one numeric domain)
 * 'a' + env.SUFFIX              concatenation when either side is a string
 * env.COUNT &gt;= 3 ? 'many' : 'few'
 * !env.DEBUG
 * </pre>
 *
 * <p>Property chains navigate maps by key; a missing link resolves to
 * {@code null} (never an error — an absent env var reads as null, so
 * {@code env.X == 'v'} is simply false). Division and modulo follow Java's
 * double semantics.
 */
public final class Evaluator {

    private Evaluator() {}

    /** Evaluates {@code expression} against {@code scope} (property chains resolve from it). */
    public static Object evaluate(String expression, Map<String, Object> scope) {
        Parser parser = new Parser(new Lexer(expression).lex(), expression, scope);
        Object value = parser.parseExpression();
        parser.expectEnd();
        return value;
    }

    // ---------------------------------------------------------------- lexing

    private enum Kind {
        IDENT,
        NUMBER,
        STRING,
        LPAREN,
        RPAREN,
        LBRACKET,
        RBRACKET,
        DOT,
        QUESTION,
        COLON,
        PLUS,
        MINUS,
        STAR,
        SLASH,
        PERCENT,
        LT,
        LE,
        GT,
        GE,
        EQ,
        NE,
        AND,
        OR,
        NOT,
        END
    }

    private record Token(Kind kind, Object value) {

        static Token of(Kind kind) {
            return new Token(kind, null);
        }
    }

    private static final class Lexer {

        private final String text;
        private int index;

        Lexer(String text) {
            this.text = text;
        }

        List<Token> lex() {
            List<Token> tokens = new ArrayList<>();
            while (index < text.length()) {
                char c = text.charAt(index);
                if (Character.isWhitespace(c)) {
                    index++;
                    continue;
                }
                if (Character.isLetter(c) || c == '_') {
                    int start = index;
                    while (index < text.length()
                            && (Character.isLetterOrDigit(text.charAt(index)) || text.charAt(index) == '_')) {
                        index++;
                    }
                    tokens.add(new Token(Kind.IDENT, text.substring(start, index)));
                    continue;
                }
                if (Character.isDigit(c)) {
                    int start = index;
                    while (index < text.length()
                            && (Character.isDigit(text.charAt(index)) || text.charAt(index) == '.')) {
                        index++;
                    }
                    String literal = text.substring(start, index);
                    tokens.add(new Token(
                            Kind.NUMBER,
                            literal.indexOf('.') >= 0
                                    ? (Object) Double.parseDouble(literal)
                                    : (Object) Long.parseLong(literal)));
                    continue;
                }
                if (c == '\'' || c == '"') {
                    index++;
                    StringBuilder literal = new StringBuilder();
                    while (index < text.length() && text.charAt(index) != c) {
                        if (text.charAt(index) == '\\' && index + 1 < text.length()) {
                            index++;
                        }
                        literal.append(text.charAt(index));
                        index++;
                    }
                    if (index >= text.length()) {
                        throw error("unterminated string literal");
                    }
                    index++;
                    tokens.add(new Token(Kind.STRING, literal.toString()));
                    continue;
                }
                switch (c) {
                    case '(' -> tokens.add(Token.of(Kind.LPAREN));
                    case ')' -> tokens.add(Token.of(Kind.RPAREN));
                    case '[' -> tokens.add(Token.of(Kind.LBRACKET));
                    case ']' -> tokens.add(Token.of(Kind.RBRACKET));
                    case '.' -> tokens.add(Token.of(Kind.DOT));
                    case '?' -> tokens.add(Token.of(Kind.QUESTION));
                    case ':' -> tokens.add(Token.of(Kind.COLON));
                    case '+' -> tokens.add(Token.of(Kind.PLUS));
                    case '-' -> tokens.add(Token.of(Kind.MINUS));
                    case '*' -> tokens.add(Token.of(Kind.STAR));
                    case '/' -> tokens.add(Token.of(Kind.SLASH));
                    case '%' -> tokens.add(Token.of(Kind.PERCENT));
                    case '<' -> {
                        if (match('=')) {
                            tokens.add(Token.of(Kind.LE));
                        } else {
                            tokens.add(Token.of(Kind.LT));
                            index++;
                        }
                        continue;
                    }
                    case '>' -> {
                        if (match('=')) {
                            tokens.add(Token.of(Kind.GE));
                        } else {
                            tokens.add(Token.of(Kind.GT));
                            index++;
                        }
                        continue;
                    }
                    case '=' -> {
                        if (!match('=')) {
                            throw error("single '=' is not an operator (use ==)");
                        }
                        tokens.add(Token.of(Kind.EQ));
                        continue;
                    }
                    case '!' -> {
                        if (match('=')) {
                            tokens.add(Token.of(Kind.NE));
                        } else {
                            tokens.add(Token.of(Kind.NOT));
                            index++;
                        }
                        continue;
                    }
                    case '&' -> {
                        if (!match('&')) {
                            throw error("single '&' is not an operator (use &&)");
                        }
                        tokens.add(Token.of(Kind.AND));
                        continue;
                    }
                    case '|' -> {
                        if (!match('|')) {
                            throw error("single '|' is not an operator (use ||)");
                        }
                        tokens.add(Token.of(Kind.OR));
                        continue;
                    }
                    default -> throw error("unexpected character '" + c + "'");
                }
                index++;
            }
            tokens.add(Token.of(Kind.END));
            return tokens;
        }

        private boolean match(char expected) {
            if (index + 1 < text.length() && text.charAt(index + 1) == expected) {
                index += 2;
                return true;
            }
            return false;
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException("expression error at offset " + index + ": " + message);
        }
    }

    // --------------------------------------------------------------- parsing

    private static final class Parser {

        private final List<Token> tokens;
        private final String expression;
        private final Map<String, Object> scope;
        private int position;

        Parser(List<Token> tokens, String expression, Map<String, Object> scope) {
            this.tokens = tokens;
            this.expression = expression;
            this.scope = scope;
        }

        Object parseExpression() {
            return parseTernary();
        }

        void expectEnd() {
            if (peek().kind() != Kind.END) {
                throw error("unexpected trailing input");
            }
        }

        private Object parseTernary() {
            Object condition = parseOr();
            if (peek().kind() == Kind.QUESTION) {
                next();
                Object whenTrue = parseTernary();
                expect(Kind.COLON);
                Object whenFalse = parseTernary();
                return Evaluator.truthy(condition) ? whenTrue : whenFalse;
            }
            return condition;
        }

        private Object parseOr() {
            Object left = parseAnd();
            while (peek().kind() == Kind.OR) {
                next();
                Object right = parseAnd();
                // JS-style short-circuit: the truthy side wins
                left = Evaluator.truthy(left) ? left : right;
            }
            return left;
        }

        private Object parseAnd() {
            Object left = parseEquality();
            while (peek().kind() == Kind.AND) {
                next();
                Object right = parseEquality();
                left = Evaluator.truthy(left) ? right : left;
            }
            return left;
        }

        private Object parseEquality() {
            Object left = parseComparison();
            while (peek().kind() == Kind.EQ || peek().kind() == Kind.NE) {
                boolean negate = next().kind() == Kind.NE;
                Object right = parseComparison();
                left = Evaluator.equals(left, right);
                if (negate) {
                    left = !(Boolean) left;
                }
            }
            return left;
        }

        private Object parseComparison() {
            Object left = parseAdditive();
            Kind kind = peek().kind();
            if (kind == Kind.LT || kind == Kind.LE || kind == Kind.GT || kind == Kind.GE) {
                next();
                Object right = parseAdditive();
                // a null on either side orders against nothing: every ordering
                // comparison is false (JS-like null handling — and it keeps the
                // non-short-circuiting conjunction safe on missing properties)
                if (left == null || right == null) {
                    return false;
                }
                int order = Evaluator.compare(left, right, this::error);
                return switch (kind) {
                    case LT -> order < 0;
                    case LE -> order <= 0;
                    case GT -> order > 0;
                    default -> order >= 0;
                };
            }
            return left;
        }

        private Object parseAdditive() {
            Object left = parseMultiplicative();
            while (peek().kind() == Kind.PLUS || peek().kind() == Kind.MINUS) {
                boolean minus = next().kind() == Kind.MINUS;
                Object right = parseMultiplicative();
                if (minus) {
                    left = Evaluator.numeric(left, this::error) - Evaluator.numeric(right, this::error);
                } else {
                    left = Evaluator.add(left, right, this::error);
                }
            }
            return left;
        }

        private Object parseMultiplicative() {
            Object left = parseUnary();
            while (peek().kind() == Kind.STAR || peek().kind() == Kind.SLASH || peek().kind() == Kind.PERCENT) {
                Kind kind = next().kind();
                Object right = parseUnary();
                double a = Evaluator.numeric(left, this::error);
                double b = Evaluator.numeric(right, this::error);
                left = switch (kind) {
                    case STAR -> a * b;
                    case SLASH -> a / b;
                    default -> a % b;
                };
            }
            return left;
        }

        private Object parseUnary() {
            if (peek().kind() == Kind.NOT) {
                next();
                return !Evaluator.truthy(parseUnary());
            }
            if (peek().kind() == Kind.MINUS) {
                next();
                return -Evaluator.numeric(parseUnary(), this::error);
            }
            return parsePrimary();
        }

        private Object parsePrimary() {
            Token token = next();
            switch (token.kind()) {
                case NUMBER:
                case STRING:
                    return token.value();
                case IDENT: {
                    String word = (String) token.value();
                    if (peek().kind() != Kind.DOT && peek().kind() != Kind.LBRACKET) {
                        return switch (word) {
                            case "true" -> Boolean.TRUE;
                            case "false" -> Boolean.FALSE;
                            case "null" -> null;
                            default -> scope.get(word);
                        };
                    }
                    Object value = scope.get(word);
                    while (peek().kind() == Kind.DOT || peek().kind() == Kind.LBRACKET) {
                        if (peek().kind() == Kind.DOT) {
                            next();
                            Token property = expect(Kind.IDENT);
                            value = Evaluator.property(value, (String) property.value());
                        } else {
                            // bracket indexing reaches keys the dot chain cannot
                            // express (dotted property names like 'java.home')
                            next();
                            Token key = expect(Kind.STRING);
                            expect(Kind.RBRACKET);
                            value = Evaluator.property(value, (String) key.value());
                        }
                    }
                    return value;
                }
                case LPAREN: {
                    Object value = parseTernary();
                    expect(Kind.RPAREN);
                    return value;
                }
                default:
                    throw error("unexpected token");
            }
        }

        private Token peek() {
            return tokens.get(position);
        }

        private Token next() {
            return tokens.get(position++);
        }

        private Token expect(Kind kind) {
            Token token = next();
            if (token.kind() != kind) {
                throw error("expected " + kind + " but found " + token.kind());
            }
            return token;
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(
                    "expression error in \"" + expression + "\" at token " + position + ": " + message);
        }
    }

    // ------------------------------------------------------------- semantics

    static boolean truthy(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean bool) return bool;
        if (value instanceof Number number) return number.doubleValue() != 0;
        if (value instanceof String string) return !string.isEmpty();
        return true;
    }

    static boolean equals(Object a, Object b) {
        if (a instanceof Number left && b instanceof Number right) {
            return left.doubleValue() == right.doubleValue();
        }
        // JS-style weak equality: '3' == 3 compares numerically (config
        // values arrive as strings, thresholds are written as numbers)
        if (a instanceof Number && b instanceof String) {
            return numericOrNull(b) != null && ((Number) a).doubleValue() == numericOrNull(b);
        }
        if (b instanceof Number && a instanceof String) {
            return numericOrNull(a) != null && ((Number) b).doubleValue() == numericOrNull(a);
        }
        return Objects.equals(a, b);
    }

    interface ErrorReporter {
        IllegalArgumentException error(String message);
    }

    static double numeric(Object value, ErrorReporter errors) {
        if (value instanceof Number number) return number.doubleValue();
        throw errors.error("expected a number but found " + typeName(value));
    }

    static Object add(Object a, Object b, ErrorReporter errors) {
        if (a instanceof String || b instanceof String) {
            return stringify(a) + stringify(b);
        }
        if (a instanceof Number && b instanceof Number) {
            return ((Number) a).doubleValue() + ((Number) b).doubleValue();
        }
        throw errors.error("cannot add " + typeName(a) + " and " + typeName(b));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static int compare(Object a, Object b, ErrorReporter errors) {
        if (a instanceof Number left && b instanceof Number right) {
            return Double.compare(left.doubleValue(), right.doubleValue());
        }
        if (a instanceof String left && b instanceof String right) {
            return left.compareTo(right);
        }
        // weak ordering across the string/number boundary ('3' >= 3)
        Double left = numericOrNull(a);
        Double right = numericOrNull(b);
        if (left != null && right != null) {
            return Double.compare(left, right);
        }
        if (a != null && a.getClass().isInstance(b)) {
            return ((Comparable) a).compareTo(b);
        }
        throw errors.error("cannot compare " + typeName(a) + " and " + typeName(b));
    }

    /** Parses a value as a number when it is one, else {@code null}. */
    private static Double numericOrNull(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    static Object property(Object value, String name) {
        if (value instanceof Map<?, ?> map) {
            return map.get(name);
        }
        return null;
    }

    static String stringify(Object value) {
        if (value == null) return "null";
        if (value instanceof Number number) {
            double d = number.doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
                return String.valueOf((long) d);
            }
            return String.valueOf(d);
        }
        return String.valueOf(value);
    }

    private static String typeName(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }
}
