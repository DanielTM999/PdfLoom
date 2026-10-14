package dtm.pdfloom.html;

import org.jsoup.nodes.Element;

import java.util.*;

public final class HtmlNode {
    private final Element element;
    public final String tag, text;
    public final Map<String, String> attributes;
    public final List<HtmlNode> children = new ArrayList<>();
    public HtmlNode parent;

    HtmlNode(Element e, String text) {
        element = e;
        this.text = text;
        tag = e == null ? "#text" : e.normalName();
        Map<String, String> a = new LinkedHashMap<>();
        if (e != null) {
            e.attributes().forEach(v -> a.put(v.getKey(), v.getValue()));
        }
        attributes = Map.copyOf(a);
    }

    private HtmlNode() {
        element = null;
        tag = "#anonymous";
        text = null;
        attributes = Map.of();
    }

    public static HtmlNode anonymousText(String value, HtmlNode parent) {
        HtmlNode node = new HtmlNode();
        node.parent = parent;
        HtmlNode text = new HtmlNode(null, value);
        text.parent = node;
        node.children.add(text);
        return node;
    }

    public static HtmlNode anonymousBox(List<HtmlNode> children, HtmlNode parent) {
        HtmlNode node = new HtmlNode();
        node.parent = parent;
        node.children.addAll(children);
        return node;
    }

    public String attr(String name) {
        return attributes.getOrDefault(name, "");
    }

    public boolean matches(String selector) {
        return element != null && element.is(selector);
    }

    public String markup() {
        return element == null ? "" : element.outerHtml();
    }

    public String svgMarkup(Map<HtmlNode, dtm.pdfloom.style.ComputedStyle> styles) {
        if (element == null) {
            return "";
        }
        Element clone = element.clone();
        List<Element> targets = clone.getAllElements();
        List<HtmlNode> sources = new ArrayList<>();
        Deque<HtmlNode> pending = new ArrayDeque<>();
        pending.push(this);
        while (!pending.isEmpty()) {
            HtmlNode node = pending.pop();
            if (node.element != null) {
                sources.add(node);
            }
            for (int index = node.children.size() - 1; index >= 0; index--) {
                pending.push(node.children.get(index));
            }
        }
        for (int index = 0; index < Math.min(sources.size(), targets.size()); index++) {
            dtm.pdfloom.style.ComputedStyle style = styles.get(sources.get(index));
            if (style == null) {
                continue;
            }
            StringBuilder declarations = new StringBuilder(targets.get(index).attr("style"));
            for (String property :
                    List.of(
                            "fill",
                            "fill-rule",
                            "fill-opacity",
                            "stroke",
                            "stroke-width",
                            "stroke-dasharray",
                            "stroke-dashoffset",
                            "stroke-linecap",
                            "stroke-linejoin",
                            "stroke-miterlimit",
                            "stroke-opacity",
                            "color",
                            "font-family",
                            "font-size",
                            "font-weight",
                            "font-style",
                            "text-anchor",
                            "dominant-baseline",
                            "visibility")) {
                if (!style.get(property).isBlank()) {
                    declarations
                            .append(';')
                            .append(property)
                            .append(':')
                            .append(style.get(property));
                }
            }
            targets.get(index).attr("style", declarations.toString());
        }
        if (!clone.hasAttr("xmlns")) {
            clone.attr("xmlns", "http://www.w3.org/2000/svg");
        }
        if (!clone.hasAttr("xmlns:xlink")) {
            clone.attr("xmlns:xlink", "http://www.w3.org/1999/xlink");
        }
        return clone.outerHtml();
    }

    public String textContent() {
        StringBuilder b = new StringBuilder();
        Deque<HtmlNode> q = new ArrayDeque<>();
        q.push(this);
        while (!q.isEmpty()) {
            HtmlNode n = q.pop();
            if (n.text != null) {
                b.append(n.text);
            }
            for (int i = n.children.size() - 1; i >= 0; i--) {
                q.push(n.children.get(i));
            }
        }
        return b.toString();
    }
}
