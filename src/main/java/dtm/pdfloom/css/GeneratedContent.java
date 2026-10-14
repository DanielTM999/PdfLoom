package dtm.pdfloom.css;

import dtm.pdfloom.html.HtmlNode;

import java.util.*;
import java.util.regex.*;

public final class GeneratedContent {
    public static final String PAGE = "\uE000", PAGES = "\uE001";

    private GeneratedContent() {
    }

    public static String evaluate(
            String value, HtmlNode node, Map<String, List<Integer>> counters) {
        return evaluate(value, node, counters, Map.of());
    }

    public static String evaluate(
            String value,
            HtmlNode node,
            Map<String, List<Integer>> counters,
            Map<String, Integer> targetPages) {
        return evaluate(value, node, counters, targetPages, Map.of());
    }

    public static String evaluate(
            String value,
            HtmlNode node,
            Map<String, List<Integer>> counters,
            Map<String, Integer> targetPages,
            Map<String, Map<String, List<Integer>>> targetCounters) {
        value = TargetContent.resolve(value, node, targetPages, targetCounters);
        if (value.isBlank() || value.equals("none") || value.equals("normal")) {
            return "";
        }
        Matcher m =
                Pattern.compile(
                                "\"((?:\\\\.|[^\"])*)\"|'((?:\\\\.|[^'])*)'|counter\\(([^)]*)\\)|counters\\(([^)]*)\\)|attr\\(([^)]*)\\)")
                        .matcher(value);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            if (m.group(1) != null || m.group(2) != null) {
                String t = m.group(1) != null ? m.group(1) : m.group(2);
                b.append(t.replace("\\\"", "\"").replace("\\'", "'").replace("\\A", "\n"));
            } else if (m.group(3) != null) {
                String[] args = m.group(3).split(",");
                String name = args[0].trim();
                if (name.equals("page")) {
                    b.append(PAGE);
                } else if (name.equals("pages")) {
                    b.append(PAGES);
                } else {
                    List<Integer> c = counters.getOrDefault(name, List.of(0));
                    b.append(
                            format(
                                    c.get(c.size() - 1),
                                    args.length > 1 ? args[1].trim() : "decimal"));
                }
            } else if (m.group(4) != null) {
                String[] args = m.group(4).split(",", 3);
                List<Integer> c = counters.getOrDefault(args[0].trim(), List.of(0));
                String separator = args.length > 1 ? CssValues.unquote(args[1].trim()) : ".";
                b.append(
                        String.join(
                                separator,
                                c.stream()
                                        .map(
                                                x ->
                                                        format(
                                                                x,
                                                                args.length > 2
                                                                        ? args[2].trim()
                                                                        : "decimal"))
                                        .toList()));
            } else if (m.group(5) != null && node != null) {
                b.append(node.attr(m.group(5).trim()));
            }
        }
        return b.toString();
    }

    public static String format(int n, String type) {
        if (type.equals("decimal-leading-zero")) {
            return n < 10 ? "0" + n : Integer.toString(n);
        }
        if (type.equals("lower-alpha") || type.equals("upper-alpha")) {
            StringBuilder b = new StringBuilder();
            int x = n;
            while (x > 0) {
                x--;
                b.insert(0, (char) ('a' + x % 26));
                x /= 26;
            }
            return type.equals("upper-alpha")
                    ? b.toString().toUpperCase(Locale.ROOT)
                    : b.toString();
        }
        if (type.equals("lower-roman") || type.equals("upper-roman")) {
            int[] v = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
            String[] names = {
                "M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"
            };
            StringBuilder b = new StringBuilder();
            int x = n;
            for (int i = 0; i < v.length; i++) {
                while (x >= v[i]) {
                    b.append(names[i]);
                    x -= v[i];
                }
            }
            return type.equals("lower-roman")
                    ? b.toString().toLowerCase(Locale.ROOT)
                    : b.toString();
        }
        return Integer.toString(n);
    }
}
