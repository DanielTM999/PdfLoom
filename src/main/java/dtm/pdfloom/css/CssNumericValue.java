package dtm.pdfloom.css;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public record CssNumericValue(double value, int dimension) {
    public record Context(
            double percentageBase,
            double em,
            double rem,
            double viewportWidth,
            double viewportHeight,
            double ex,
            double ch) {
        public Context(
                double percentageBase,
                double em,
                double rem,
                double viewportWidth,
                double viewportHeight) {
            this(percentageBase, em, rem, viewportWidth, viewportHeight, em * .5, em * .5);
        }
    }

    public CssNumericValue {
        if (!Double.isFinite(value) || dimension < 0 || dimension > 1) {
            throw new IllegalArgumentException("Invalid CSS numeric dimension");
        }
    }

    public static CssNumericValue parse(String expression, Context context) {
        Parser parser = new Parser(expression, context);
        CssNumericValue result = parser.sum();
        parser.space();
        if (parser.position != parser.expression.length()) {
            throw new IllegalArgumentException("Trailing CSS numeric input");
        }
        return result;
    }

    private static final class Parser {
        private final String expression;
        private final Context context;
        private int position;

        private Parser(String expression, Context context) {
            this.expression = expression.toLowerCase(Locale.ROOT);
            this.context = context;
        }

        private void space() {
            while (position < expression.length()
                    && Character.isWhitespace(expression.charAt(position))) {
                position++;
            }
        }

        private boolean eat(char character) {
            space();
            if (position < expression.length() && expression.charAt(position) == character) {
                position++;
                return true;
            }
            return false;
        }

        private CssNumericValue sum() {
            CssNumericValue result = product();
            while (true) {
                boolean add = eat('+');
                if (!add && !eat('-')) {
                    return result;
                }
                CssNumericValue right = product();
                sameDimension(result, right);
                result =
                        new CssNumericValue(
                                result.value + (add ? right.value : -right.value),
                                result.dimension);
            }
        }

        private CssNumericValue product() {
            CssNumericValue result = atom();
            while (true) {
                if (eat('*')) {
                    CssNumericValue right = atom();
                    result =
                            new CssNumericValue(
                                    result.value * right.value, result.dimension + right.dimension);
                } else if (eat('/')) {
                    CssNumericValue right = atom();
                    result =
                            new CssNumericValue(
                                    result.value / right.value, result.dimension - right.dimension);
                } else {
                    return result;
                }
            }
        }

        private CssNumericValue atom() {
            space();
            if (eat('-')) {
                CssNumericValue value = atom();
                return new CssNumericValue(-value.value, value.dimension);
            }
            if (eat('+')) {
                return atom();
            }
            if (eat('(')) {
                CssNumericValue value = sum();
                require(')');
                return value;
            }
            int start = position;
            while (position < expression.length()
                    && Character.isLetter(expression.charAt(position))) {
                position++;
            }
            if (start != position) {
                String function = expression.substring(start, position);
                require('(');
                List<CssNumericValue> arguments = new ArrayList<>();
                arguments.add(sum());
                while (eat(',')) {
                    arguments.add(sum());
                }
                require(')');
                CssNumericValue first = arguments.get(0);
                arguments.forEach(argument -> sameDimension(first, argument));
                double value =
                        switch (function) {
                            case "calc" -> {
                                if (arguments.size() != 1) {
                                    throw new IllegalArgumentException(
                                            "calc requires one expression");
                                }
                                yield first.value;
                            }
                            case "min" ->
                                    arguments.stream()
                                            .mapToDouble(CssNumericValue::value)
                                            .min()
                                            .orElseThrow();
                            case "max" ->
                                    arguments.stream()
                                            .mapToDouble(CssNumericValue::value)
                                            .max()
                                            .orElseThrow();
                            case "clamp" -> {
                                if (arguments.size() != 3) {
                                    throw new IllegalArgumentException(
                                            "clamp requires three expressions");
                                }
                                yield Math.max(
                                        first.value,
                                        Math.min(arguments.get(1).value, arguments.get(2).value));
                            }
                            default ->
                                    throw new IllegalArgumentException(
                                            "Unknown CSS numeric function");
                        };
                return new CssNumericValue(value, first.dimension);
            }
            start = position;
            while (position < expression.length()
                    && (Character.isDigit(expression.charAt(position))
                            || expression.charAt(position) == '.')) {
                position++;
            }
            if (position == start) {
                throw new IllegalArgumentException("Missing CSS number");
            }
            if (position + 1 < expression.length()
                    && expression.charAt(position) == 'e'
                    && (Character.isDigit(expression.charAt(position + 1))
                            || expression.charAt(position + 1) == '+'
                            || expression.charAt(position + 1) == '-')) {
                position++;
                if (expression.charAt(position) == '+' || expression.charAt(position) == '-') {
                    position++;
                }
                int exponentStart = position;
                while (position < expression.length()
                        && Character.isDigit(expression.charAt(position))) {
                    position++;
                }
                if (position == exponentStart) {
                    throw new IllegalArgumentException("Missing CSS exponent");
                }
            }
            double number = Double.parseDouble(expression.substring(start, position));
            start = position;
            while (position < expression.length()
                    && (Character.isLetter(expression.charAt(position))
                            || expression.charAt(position) == '%')) {
                position++;
            }
            String unit = expression.substring(start, position);
            double factor =
                    switch (unit) {
                        case "", "pt" -> 1;
                        case "px" -> .75;
                        case "mm" -> 72 / 25.4;
                        case "cm" -> 72 / 2.54;
                        case "in" -> 72;
                        case "pc" -> 12;
                        case "%" -> context.percentageBase / 100;
                        case "em" -> context.em;
                        case "rem" -> context.rem;
                        case "ex" -> context.ex;
                        case "ch" -> context.ch;
                        case "vw" -> context.viewportWidth / 100;
                        case "vh" -> context.viewportHeight / 100;
                        case "vmin" ->
                                Math.min(context.viewportWidth, context.viewportHeight) / 100;
                        case "vmax" ->
                                Math.max(context.viewportWidth, context.viewportHeight) / 100;
                        default -> throw new IllegalArgumentException("Unknown CSS unit: " + unit);
                    };
            return new CssNumericValue(number * factor, unit.isEmpty() ? 0 : 1);
        }

        private void require(char character) {
            if (!eat(character)) {
                throw new IllegalArgumentException("Missing CSS delimiter: " + character);
            }
        }

        private void sameDimension(CssNumericValue left, CssNumericValue right) {
            if (left.dimension != right.dimension) {
                throw new IllegalArgumentException("Incompatible CSS numeric dimensions");
            }
        }
    }
}
