package dtm.pdfloom.css;

import java.util.*;

public final class CssValues {
    private CssValues() {
    }

    public static double length(
            String text,
            double base,
            double em,
            double rem,
            double vw,
            double vh,
            double fallback) {
        return length(text, new CssNumericValue.Context(base, em, rem, vw, vh), fallback);
    }

    public static double length(String text, CssNumericValue.Context context, double fallback) {
        if (text == null
                || text.isBlank()
                || Set.of("auto", "none", "normal", "initial").contains(text)) {
            return fallback;
        }
        try {
            double n = CssNumericValue.parse(text, context).value();
            return Double.isFinite(n) ? n : fallback;
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    public static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        int start = 0, depth = 0;
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote && (i == 0 || s.charAt(i - 1) != '\\')) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (Character.isWhitespace(c) && depth == 0) {
                if (start < i) {
                    out.add(s.substring(start, i));
                }
                start = i + 1;
            }
        }
        if (start < s.length()) {
            out.add(s.substring(start));
        }
        return out;
    }

    public static String unquote(String s) {
        return s.length() > 1
                        && ((s.startsWith("\"") && s.endsWith("\""))
                                || (s.startsWith("'") && s.endsWith("'")))
                ? s.substring(1, s.length() - 1)
                : s;
    }

    public static List<String> commaSeparated(String value) {
        return CssConditions.split(value, ",");
    }
}
