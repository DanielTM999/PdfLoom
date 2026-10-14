package dtm.pdfloom.css;

import dtm.pdfloom.config.PdfConversionConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CssConditions {
    private CssConditions() {
    }

    static boolean evaluate(String expression, Predicate<String> atom) {
        String value = expression.strip().toLowerCase(Locale.ROOT);
        List<String> alternatives = split(value, ",", " or ");
        if (alternatives.size() > 1) {
            return alternatives.stream().anyMatch(part -> evaluate(part, atom));
        }
        List<String> conjunctions = split(value, " and ");
        if (conjunctions.size() > 1) {
            return conjunctions.stream().allMatch(part -> evaluate(part, atom));
        }
        if (value.startsWith("not ")) {
            return !evaluate(value.substring(4), atom);
        }
        if (value.startsWith("only ")) {
            return evaluate(value.substring(5), atom);
        }
        if (value.startsWith("(") && closing(value, 0) == value.length() - 1) {
            return evaluate(value.substring(1, value.length() - 1), atom);
        }
        return atom.test(value);
    }

    static List<String> split(String source, String... separators) {
        List<String> result = new ArrayList<>();
        int depth = 0;
        int start = 0;
        char quote = 0;
        for (int index = 0; index < source.length(); index++) {
            char character = source.charAt(index);
            if (quote != 0) {
                if (character == quote && (index == 0 || source.charAt(index - 1) != '\\')) {
                    quote = 0;
                }
                continue;
            }
            if (character == '\'' || character == '"') {
                quote = character;
            } else if (character == '(' || character == '[') {
                depth++;
            } else if (character == ')' || character == ']') {
                depth--;
            } else if (depth == 0) {
                for (String separator : separators) {
                    if (source.startsWith(separator, index)) {
                        result.add(source.substring(start, index).strip());
                        index += separator.length() - 1;
                        start = index + 1;
                        break;
                    }
                }
            }
        }
        result.add(source.substring(start).strip());
        return result;
    }

    private static int closing(String value, int start) {
        int depth = 0;
        for (int index = start; index < value.length(); index++) {
            if (value.charAt(index) == '(') {
                depth++;
            } else if (value.charAt(index) == ')' && --depth == 0) {
                return index;
            }
        }
        return -1;
    }

    static boolean media(String expression, PdfConversionConfig config) {
        return evaluate(expression, value -> feature(value, config));
    }

    private static boolean feature(String value, PdfConversionConfig config) {
        if (value.isBlank() || value.equals("all") || value.equals("print")) {
            return true;
        }
        if (value.equals("screen")) {
            return false;
        }
        int separator = value.indexOf(':');
        if (separator >= 0) {
            String name = value.substring(0, separator).strip();
            String expected = value.substring(separator + 1).strip();
            if (name.equals("orientation")) {
                return expected.equals(
                        config.pageWidth > config.pageHeight ? "landscape" : "portrait");
            }
            double actual =
                    name.endsWith("width")
                            ? config.pageWidth
                            : name.endsWith("height")
                                    ? config.pageHeight
                                    : name.endsWith("resolution") ? 96 : Double.NaN;
            double target =
                    name.endsWith("resolution")
                            ? resolution(expected)
                            : CssValues.length(
                                    expected,
                                    Double.NaN,
                                    12,
                                    12,
                                    config.pageWidth,
                                    config.pageHeight,
                                    Double.NaN);
            return Double.isFinite(actual + target)
                    && (name.startsWith("min-")
                            ? actual >= target
                            : name.startsWith("max-")
                                    ? actual <= target
                                    : Math.abs(actual - target) < .001);
        }
        Matcher range =
                Pattern.compile("(width|height|resolution)\\s*(<=|>=|<|>|=)\\s*(.+)")
                        .matcher(value);
        if (range.matches()) {
            double actual =
                    range.group(1).equals("width")
                            ? config.pageWidth
                            : range.group(1).equals("height") ? config.pageHeight : 96;
            double target =
                    range.group(1).equals("resolution")
                            ? resolution(range.group(3))
                            : CssValues.length(
                                    range.group(3),
                                    Double.NaN,
                                    12,
                                    12,
                                    config.pageWidth,
                                    config.pageHeight,
                                    Double.NaN);
            return switch (range.group(2)) {
                case "<" -> actual < target;
                case ">" -> actual > target;
                case "<=" -> actual <= target;
                case ">=" -> actual >= target;
                default -> Math.abs(actual - target) < .001;
            };
        }
        return false;
    }

    private static double resolution(String value) {
        try {
            if (value.endsWith("dppx")) {
                return Double.parseDouble(value.substring(0, value.length() - 4)) * 96;
            }
            if (value.endsWith("dpi")) {
                return Double.parseDouble(value.substring(0, value.length() - 3));
            }
            if (value.endsWith("dpcm")) {
                return Double.parseDouble(value.substring(0, value.length() - 4)) * 2.54;
            }
        } catch (NumberFormatException exception) {
            return Double.NaN;
        }
        return Double.NaN;
    }
}
