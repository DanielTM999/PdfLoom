package dtm.pdfloom.page;

import dtm.pdfloom.config.PdfConversionConfig;
import dtm.pdfloom.css.CssValues;
import dtm.pdfloom.exception.PdfConversionException;
import dtm.pdfloom.style.*;

import java.util.*;

public record PageSpec(
        double width,
        double height,
        double top,
        double right,
        double bottom,
        double left,
        Map<String, ComputedStyle> marginBoxes) {
    public PageSpec {
        marginBoxes = Map.copyOf(marginBoxes);
        if (width <= 0
                || height <= 0
                || left < 0
                || right < 0
                || top < 0
                || bottom < 0
                || left + right >= width
                || top + bottom >= height
                || !Double.isFinite(width + height + top + right + bottom + left)) {
            throw new PdfConversionException("Invalid CSS @page dimensions/margins");
        }
    }

    public double contentWidth() {
        return width - left - right;
    }

    public double contentHeight() {
        return height - top - bottom;
    }

    public static PageSpec resolve(
            PdfConversionConfig c, StyleSheet css, int page, String name, boolean blank) {
        Map<String, String> v = new HashMap<>();
        Map<String, ComputedStyle> boxes = new LinkedHashMap<>();
        Map<String, dtm.pdfloom.css.CssPriority> priorities = new HashMap<>();
        Map<String, Map<String, String>> marginValues = new LinkedHashMap<>();
        int order = 0;
        for (StyleSheet.PageRule rule : css.pages) {
            order++;
            String selector = rule.selector().replace(" ", "");
            String[] parts = selector.split(":");
            if (!parts[0].isEmpty() && !parts[0].equals(name)) {
                continue;
            }
            boolean applies = true;
            long specificity = parts[0].isEmpty() ? 0 : 1_000_000;
            for (int index = 1; index < parts.length; index++) {
                applies &=
                        switch (parts[index]) {
                            case "first" -> page == 1;
                            case "left" -> page % 2 == 0;
                            case "right" -> page % 2 == 1;
                            case "blank" -> blank;
                            default -> false;
                        };
                specificity +=
                        parts[index].equals("first") || parts[index].equals("blank") ? 1000 : 1;
            }
            if (!applies) {
                continue;
            }
            for (Map.Entry<String, String> entry : rule.style().values().entrySet()) {
                dtm.pdfloom.css.CssPriority priority =
                        new dtm.pdfloom.css.CssPriority(
                                rule.important().getOrDefault(entry.getKey(), false),
                                false,
                                rule.layer(),
                                specificity,
                                order);
                if (!priorities.containsKey(entry.getKey())
                        || priority.compareTo(priorities.get(entry.getKey())) >= 0) {
                    priorities.put(entry.getKey(), priority);
                    v.put(entry.getKey(), entry.getValue());
                }
            }
            for (Map.Entry<String, ComputedStyle> box : rule.margins().entrySet()) {
                Map<String, String> values =
                        marginValues.computeIfAbsent(box.getKey(), key -> new HashMap<>());
                for (Map.Entry<String, String> entry : box.getValue().values().entrySet()) {
                    String key = "@" + box.getKey() + ":" + entry.getKey();
                    dtm.pdfloom.css.CssPriority priority =
                            new dtm.pdfloom.css.CssPriority(
                                    rule.marginImportant()
                                            .getOrDefault(box.getKey(), Map.of())
                                            .getOrDefault(entry.getKey(), false),
                                    false,
                                    rule.layer(),
                                    specificity,
                                    order);
                    if (!priorities.containsKey(key)
                            || priority.compareTo(priorities.get(key)) >= 0) {
                        priorities.put(key, priority);
                        values.put(entry.getKey(), entry.getValue());
                    }
                }
            }
        }
        marginValues.forEach(
                (key, values) -> {
                    for (String property :
                            List.of(
                                    "font-family",
                                    "font-size",
                                    "font-weight",
                                    "font-style",
                                    "color")) {
                        if (v.containsKey(property)) {
                            values.putIfAbsent(property, v.get(property));
                        }
                    }
                    if (values.containsKey("font-size")) {
                        values.put(
                                "font-size",
                                CssValues.length(
                                                values.get("font-size"),
                                                css.rootFontSize,
                                                css.rootFontSize,
                                                css.rootFontSize,
                                                c.pageWidth,
                                                c.pageHeight,
                                                9)
                                        + "pt");
                    }
                    boxes.put(key, new ComputedStyle(values));
                });
        double pageFont =
                CssValues.length(
                        v.get("font-size"),
                        css.rootFontSize,
                        css.rootFontSize,
                        css.rootFontSize,
                        c.pageWidth,
                        c.pageHeight,
                        css.rootFontSize);
        double w = c.pageWidth, h = c.pageHeight;
        String size = v.getOrDefault("size", "").toLowerCase(Locale.ROOT);
        List<String> parts = CssValues.tokens(size);
        if (size.contains("a4")) {
            w = 595.2756;
            h = 841.8898;
        } else if (size.contains("a3")) {
            w = 841.8898;
            h = 1190.5512;
        } else if (size.contains("a5")) {
            w = 419.5276;
            h = 595.2756;
        } else if (size.contains("letter")) {
            w = 612;
            h = 792;
        } else if (size.contains("legal")) {
            w = 612;
            h = 1008;
        } else if (!parts.isEmpty()
                && !Set.of("auto", "portrait", "landscape").contains(parts.get(0))) {
            w =
                    CssValues.length(
                            parts.get(0),
                            c.pageWidth,
                            pageFont,
                            css.rootFontSize,
                            c.pageWidth,
                            c.pageHeight,
                            c.pageWidth);
            h =
                    parts.size() > 1
                            ? CssValues.length(
                                    parts.get(1),
                                    c.pageHeight,
                                    pageFont,
                                    css.rootFontSize,
                                    c.pageWidth,
                                    c.pageHeight,
                                    c.pageHeight)
                            : w;
        }
        if (size.contains("landscape") && w < h) {
            double t = w;
            w = h;
            h = t;
        }
        if (size.contains("portrait") && w > h) {
            double t = w;
            w = h;
            h = t;
        }
        return new PageSpec(
                w,
                h,
                CssValues.length(
                        v.get("margin-top"), w, pageFont, css.rootFontSize, w, h, c.marginTop),
                CssValues.length(
                        v.get("margin-right"), w, pageFont, css.rootFontSize, w, h, c.marginRight),
                CssValues.length(
                        v.get("margin-bottom"),
                        w,
                        pageFont,
                        css.rootFontSize,
                        w,
                        h,
                        c.marginBottom),
                CssValues.length(
                        v.get("margin-left"), w, pageFont, css.rootFontSize, w, h, c.marginLeft),
                boxes);
    }
}
