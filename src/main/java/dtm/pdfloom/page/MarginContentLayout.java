package dtm.pdfloom.page;

import dtm.pdfloom.config.Diagnostic;
import dtm.pdfloom.config.PdfConversionConfig;
import dtm.pdfloom.font.TextMetrics;
import dtm.pdfloom.html.HtmlNode;
import dtm.pdfloom.html.HtmlParser;
import dtm.pdfloom.image.ImageStore;
import dtm.pdfloom.layout.CssLayoutEngine;
import dtm.pdfloom.layout.LayoutResult;
import dtm.pdfloom.render.Paint;
import dtm.pdfloom.style.ComputedStyle;
import dtm.pdfloom.style.StyleSheet;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MarginContentLayout {
    private record Key(HtmlNode node, double width, int pageNumber) {
    }

    private MarginContentLayout() {
    }

    public static List<RenderPage> prepare(
            List<RenderPage> pages,
            HtmlParser.Document document,
            StyleSheet styles,
            PdfConversionConfig config,
            TextMetrics fonts,
            ImageStore images,
            URI origin,
            Map<String, Integer> targets) {
        Map<Key, LayoutResult> measured = new HashMap<>();
        List<RenderPage> result = new ArrayList<>();
        Map<HtmlNode, Boolean> pageDependent = new java.util.IdentityHashMap<>();
        for (RenderPage page : pages) {
            Map<String, List<Paint>> content = new LinkedHashMap<>();
            for (Map.Entry<String, ComputedStyle> entry : page.spec().marginBoxes().entrySet()) {
                Matcher element =
                        Pattern.compile("element\\(\\s*([\\w-]+)(?:\\s*,\\s*([\\w-]+))?\\s*\\)")
                                .matcher(entry.getValue().get("content"));
                if (!element.find()) {
                    continue;
                }
                String name =
                        element.group(1) + (element.group(2) == null ? "" : ":" + element.group(2));
                LayoutResult.Running running = page.running().get(name);
                if (running == null || running.source() == null) {
                    continue;
                }
                Map<String, String> values = new HashMap<>(entry.getValue().values());
                values.putIfAbsent("font-family", config.defaultFont);
                values.putIfAbsent("font-size", "9pt");
                ComputedStyle style = new ComputedStyle(values);
                MarginBoxLayout.Region region = MarginBoxLayout.region(page.spec(), entry.getKey());
                double left = MarginBoxLayout.padding(style, "left", region, fonts);
                double right = MarginBoxLayout.padding(style, "right", region, fonts);
                double available = Math.max(.1, region.width() - left - right);
                int number =
                        pageDependent.computeIfAbsent(
                                        running.source(), node -> dependsOnPage(node, styles))
                                ? page.number()
                                : 0;
                Key key = new Key(running.source(), available, number);
                LayoutResult box =
                        measured.computeIfAbsent(
                                key,
                                ignored ->
                                        new CssLayoutEngine(
                                                        config,
                                                        styles,
                                                        fonts,
                                                        images,
                                                        origin,
                                                        pages.size(),
                                                        targets,
                                                        number)
                                                .layoutElement(
                                                        running.source(),
                                                        available,
                                                        document.body()));
                double x = region.x() + left;
                String align =
                        style.get(
                                "text-align",
                                entry.getKey().endsWith("center")
                                                || entry.getKey().endsWith("middle")
                                        ? "center"
                                        : entry.getKey().endsWith("right") ? "right" : "left");
                if (align.equals("center")) {
                    x += (available - box.root.width) / 2;
                } else if (align.equals("right")) {
                    x += available - box.root.width;
                }
                double y = MarginBoxLayout.vertical(style, region, box.height, fonts);
                if (box.height
                                > region.height()
                                        - MarginBoxLayout.padding(style, "top", region, fonts)
                                        - MarginBoxLayout.padding(style, "bottom", region, fonts)
                                        + .01
                        || box.root.width > available + .01) {
                    config.report(
                            new Diagnostic(
                                    Diagnostic.Severity.WARNING,
                                    "MARGIN_BOX_OVERFLOW",
                                    "Running element exceeds margin region: " + name));
                }
                double dx = x;
                List<Paint> commands =
                        box.commands.stream().map(command -> command.move(dx, y)).toList();
                boolean clip =
                        List.of("hidden", "clip", "scroll", "auto").contains(style.get("overflow"));
                content.put(
                        entry.getKey(),
                        List.of(
                                new Paint.Group(
                                        region.x(),
                                        region.y(),
                                        region.width(),
                                        region.height(),
                                        commands,
                                        clip,
                                        "none",
                                        "50% 50%")));
            }
            result.add(
                    new RenderPage(
                            page.spec(),
                            page.number(),
                            page.flowStart(),
                            page.flowEnd(),
                            page.commands(),
                            page.running(),
                            page.strings(),
                            content));
        }
        return result;
    }

    private static boolean dependsOnPage(HtmlNode root, StyleSheet styles) {
        java.util.Deque<HtmlNode> pending = new java.util.ArrayDeque<>();
        pending.add(root);
        Pattern pageCounter =
                Pattern.compile("counter\\(\\s*page\\s*(?:,|\\))", Pattern.CASE_INSENSITIVE);
        while (!pending.isEmpty()) {
            HtmlNode node = pending.removeFirst();
            for (ComputedStyle style :
                    new ComputedStyle[] {styles.before.get(node), styles.after.get(node)}) {
                if (style != null && pageCounter.matcher(style.get("content")).find()) {
                    return true;
                }
            }
            if (node.text != null && node.text.contains(dtm.pdfloom.css.GeneratedContent.PAGE)) {
                return true;
            }
            pending.addAll(node.children);
        }
        return false;
    }
}
