package dtm.pdfloom.style;

import dtm.pdfloom.html.HtmlNode;

import java.net.URI;
import java.util.*;

public final class StyleSheet {
    public double rootFontSize = 12;
    public List<HtmlNode> elements = List.of();
    public final Map<HtmlNode, String> fontExpressions = new IdentityHashMap<>();

    public record PageRule(
            String selector,
            ComputedStyle style,
            Map<String, ComputedStyle> margins,
            Map<String, Boolean> important,
            Map<String, Map<String, Boolean>> marginImportant,
            List<Integer> layer) {
        public PageRule(String selector, ComputedStyle style, Map<String, ComputedStyle> margins) {
            this(selector, style, margins, Map.of(), Map.of(), List.of(Integer.MAX_VALUE));
        }

        public PageRule {
            margins = Map.copyOf(margins);
            important = Map.copyOf(important);
            Map<String, Map<String, Boolean>> copy = new LinkedHashMap<>();
            marginImportant.forEach((key, value) -> copy.put(key, Map.copyOf(value)));
            marginImportant = Map.copyOf(copy);
            layer = List.copyOf(layer);
        }
    }

    public record FontFace(
            String family, int weight, boolean italic, String reference, URI origin) {
            }

    public final Map<HtmlNode, ComputedStyle> styles = new IdentityHashMap<>();
    public final Map<HtmlNode, ComputedStyle> before = new IdentityHashMap<>(),
            after = new IdentityHashMap<>();
    public final List<PageRule> pages = new ArrayList<>();
    public final List<FontFace> fonts = new ArrayList<>();

    public ComputedStyle style(HtmlNode n) {
        return styles.get(n);
    }
}
