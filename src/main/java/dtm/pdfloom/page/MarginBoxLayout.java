package dtm.pdfloom.page;

import dtm.pdfloom.css.CssValues;
import dtm.pdfloom.font.TextMetrics;
import dtm.pdfloom.render.Paint;
import dtm.pdfloom.style.ComputedStyle;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MarginBoxLayout {
    public record Region(double x, double y, double width, double height) {
    }

    private MarginBoxLayout() {
    }

    public static Region region(PageSpec page, String position) {
        if (position.endsWith("corner")) {
            boolean top = position.startsWith("top");
            boolean left = position.contains("left");
            return new Region(
                    left ? 0 : page.width() - page.right(),
                    top ? 0 : page.height() - page.bottom(),
                    left ? page.left() : page.right(),
                    top ? page.top() : page.bottom());
        }
        if (position.startsWith("left-") || position.startsWith("right-")) {
            boolean left = position.startsWith("left-");
            double height = page.contentHeight() / 3;
            int segment = position.endsWith("top") ? 0 : position.endsWith("middle") ? 1 : 2;
            return new Region(
                    left ? 0 : page.width() - page.right(),
                    page.top() + segment * height,
                    left ? page.left() : page.right(),
                    height);
        }
        boolean top = position.startsWith("top-");
        String prefix = top ? "top-" : "bottom-";
        boolean siblings =
                List.of("left", "center", "right").stream()
                        .map(value -> prefix + value)
                        .anyMatch(
                                key ->
                                        !key.equals(position)
                                                && page.marginBoxes().containsKey(key));
        double width = siblings ? page.contentWidth() / 3 : page.contentWidth();
        double x =
                position.endsWith("center")
                        ? page.left() + (page.contentWidth() - width) / 2
                        : position.endsWith("right")
                                ? page.width() - page.right() - width
                                : page.left();
        return new Region(
                x,
                top ? 0 : page.height() - page.bottom(),
                width,
                top ? page.top() : page.bottom());
    }

    public static double padding(
            ComputedStyle style, String side, Region region, TextMetrics metrics) {
        return CssValues.length(
                style.get("padding-" + side),
                region.width,
                metrics.font(style).size(),
                12,
                region.width,
                region.height,
                0);
    }

    public static double vertical(
            ComputedStyle style, Region region, double height, TextMetrics metrics) {
        double top = padding(style, "top", region, metrics);
        double bottom = padding(style, "bottom", region, metrics);
        double free = Math.max(0, region.height - top - bottom - height);
        double offset =
                style.is("vertical-align", "top")
                        ? 0
                        : style.is("vertical-align", "bottom") ? free : free / 2;
        return region.y + top + offset;
    }

    public static List<Paint> text(
            String text, ComputedStyle style, Region region, String position, TextMetrics metrics) {
        TextMetrics.Font font = metrics.font(style, text);
        double left = padding(style, "left", region, metrics);
        double right = padding(style, "right", region, metrics);
        double available = Math.max(0, region.width - left - right);
        String whiteSpace = style.get("white-space", "normal");
        boolean preserve =
                List.of("pre", "pre-wrap", "pre-line", "break-spaces").contains(whiteSpace);
        boolean wrap = !List.of("pre", "nowrap").contains(whiteSpace);
        String normalized =
                preserve ? text.replace("\t", "    ") : text.replaceAll("\\s+", " ").strip();
        List<String> lines = new ArrayList<>();
        for (String paragraph : normalized.split("\\n", -1)) {
            if (!wrap || metrics.width(paragraph, font) <= available) {
                lines.add(paragraph);
                continue;
            }
            StringBuilder pending = new StringBuilder();
            Matcher tokens = Pattern.compile("\\S+|\\s+").matcher(paragraph);
            while (tokens.find()) {
                String token = tokens.group();
                if (!pending.isEmpty()
                        && metrics.width(pending.toString() + token, font) > available) {
                    lines.add(pending.toString().stripTrailing());
                    pending.setLength(0);
                }
                if (!pending.isEmpty() || !token.isBlank()) {
                    pending.append(token);
                }
            }
            lines.add(pending.toString());
        }
        TextMetrics.Bounds first = metrics.bounds(lines.get(0), font);
        double lineHeight =
                style.get("line-height", "normal").matches("[0-9.]+")
                        ? Double.parseDouble(style.get("line-height")) * font.size()
                        : CssValues.length(
                                style.get("line-height"),
                                font.size(),
                                font.size(),
                                12,
                                region.width,
                                region.height,
                                font.size() * 1.2);
        TextMetrics.Bounds last = metrics.bounds(lines.get(lines.size() - 1), font);
        double height = first.ascent() + last.descent() + (lines.size() - 1) * lineHeight;
        double y = vertical(style, region, height, metrics);
        String align =
                style.get(
                        "text-align",
                        position.endsWith("center") || position.endsWith("middle")
                                ? "center"
                                : position.endsWith("right") ? "right" : "left");
        List<Paint> commands = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            String value = metrics.supportedText(lines.get(index), font);
            double width = metrics.width(value, font);
            TextMetrics.Bounds ink = metrics.bounds(value, font);
            double x =
                    region.x
                            + left
                            + (align.equals("center")
                                    ? (available - width) / 2
                                    : align.equals("right") || align.equals("end")
                                            ? available - width
                                            : 0);
            commands.add(
                    new Paint.Text(
                            x,
                            y + first.ascent() + index * lineHeight - ink.ascent(),
                            width,
                            ink.ascent() + ink.descent(),
                            value,
                            font.key(),
                            font.size(),
                            style.get("color", "black"),
                            0,
                            0,
                            style.get("text-decoration"),
                            "",
                            1,
                            ink.ascent(),
                            lineHeight));
        }
        return commands;
    }
}
