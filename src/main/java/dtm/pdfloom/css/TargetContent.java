package dtm.pdfloom.css;

import dtm.pdfloom.html.HtmlNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class TargetContent {
    private TargetContent() {
    }

    static String resolve(
            String content,
            HtmlNode node,
            Map<String, Integer> pages,
            Map<String, Map<String, List<Integer>>> targets) {
        StringBuilder result = new StringBuilder();
        int cursor = 0;
        char quote = 0;
        while (cursor < content.length()) {
            char character = content.charAt(cursor);
            if (character == '\\' && cursor + 1 < content.length()) {
                result.append(character).append(content.charAt(cursor + 1));
                cursor += 2;
                continue;
            }
            if (quote != 0) {
                result.append(character);
                if (character == quote) {
                    quote = 0;
                }
                cursor++;
                continue;
            }
            if (character == '\'' || character == '"') {
                quote = character;
                result.append(character);
                cursor++;
                continue;
            }
            boolean plural = content.startsWith("target-counters(", cursor);
            if (!plural && !content.startsWith("target-counter(", cursor)) {
                result.append(character);
                cursor++;
                continue;
            }
            int open = content.indexOf('(', cursor);
            int close = closing(content, open);
            if (close < 0) {
                result.append(content.substring(cursor));
                break;
            }
            List<String> arguments = CssValues.commaSeparated(content.substring(open + 1, close));
            String text = "";
            if (arguments.size() >= (plural ? 3 : 2)) {
                String id = reference(arguments.get(0), node);
                String counter = arguments.get(1).strip();
                List<Integer> values =
                        counter.equals("page")
                                ? List.of(pages.getOrDefault(id, 1))
                                : targets.getOrDefault(id, Map.of())
                                        .getOrDefault(counter, List.of(0));
                String type =
                        arguments.size() > (plural ? 3 : 2)
                                ? arguments.get(plural ? 3 : 2).strip()
                                : "decimal";
                if (plural) {
                    List<String> formatted = new ArrayList<>();
                    for (int value : values) {
                        formatted.add(GeneratedContent.format(value, type));
                    }
                    text = String.join(CssValues.unquote(arguments.get(2).strip()), formatted);
                } else {
                    text = GeneratedContent.format(values.get(values.size() - 1), type);
                }
            }
            result.append('"').append(text.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
            cursor = close + 1;
        }
        return result.toString();
    }

    private static String reference(String expression, HtmlNode node) {
        String value = expression.strip();
        if (value.startsWith("attr(") && value.endsWith(")")) {
            value = node == null ? "" : node.attr(value.substring(5, value.length() - 1).strip());
        } else if (value.startsWith("url(") && value.endsWith(")")) {
            value = CssValues.unquote(value.substring(4, value.length() - 1).strip());
        } else {
            value = CssValues.unquote(value);
        }
        int hash = value.indexOf('#');
        return hash >= 0 ? value.substring(hash + 1) : value;
    }

    private static int closing(String value, int open) {
        int depth = 0;
        char quote = 0;
        for (int index = open; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\\') {
                index++;
            } else if (quote != 0) {
                if (character == quote) {
                    quote = 0;
                }
            } else if (character == '\'' || character == '"') {
                quote = character;
            } else if (character == '(') {
                depth++;
            } else if (character == ')' && --depth == 0) {
                return index;
            }
        }
        return -1;
    }
}
