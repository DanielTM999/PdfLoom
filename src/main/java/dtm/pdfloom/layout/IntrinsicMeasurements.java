package dtm.pdfloom.layout;

import dtm.pdfloom.config.PdfConversionConfig;
import dtm.pdfloom.css.CssValues;
import dtm.pdfloom.exception.PdfConversionException;
import dtm.pdfloom.font.TextMetrics;
import dtm.pdfloom.html.HtmlNode;
import dtm.pdfloom.image.ImageStore;
import dtm.pdfloom.style.ComputedStyle;
import dtm.pdfloom.style.StyleSheet;

import java.net.URI;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class IntrinsicMeasurements {
    record Sizes(double minimum, double preferred) {
    }

    private final PdfConversionConfig config;
    private final StyleSheet styles;
    private final TextMetrics metrics;
    private final ImageStore images;
    private final URI origin;
    private final Map<HtmlNode, String> before;
    private final Map<HtmlNode, String> after;
    private final int pageGuess;
    private final int pageNumber;
    private final Map<HtmlNode, Sizes> cached = new IdentityHashMap<>();

    IntrinsicMeasurements(
            PdfConversionConfig config,
            StyleSheet styles,
            TextMetrics metrics,
            ImageStore images,
            URI origin,
            Map<HtmlNode, String> before,
            Map<HtmlNode, String> after,
            int pageGuess,
            int pageNumber) {
        this.config = config;
        this.styles = styles;
        this.metrics = metrics;
        this.images = images;
        this.origin = origin;
        this.before = before;
        this.after = after;
        this.pageGuess = pageGuess;
        this.pageNumber = pageNumber;
    }

    Sizes measure(HtmlNode node) {
        return measure(node, 0);
    }

    private Sizes measure(HtmlNode node, int depth) {
        if (depth > config.maxDepth) {
            throw new PdfConversionException("Intrinsic measurement depth exceeded");
        }
        Sizes previous = cached.get(node);
        if (previous != null) {
            return previous;
        }
        ComputedStyle style = styles.style(node.tag.equals("#text") ? node.parent : node);
        if (style == null || style.is("display", "none")) {
            return new Sizes(0, 0);
        }
        Sizes size;
        if (node.tag.equals("#text")) {
            Line line = new Line();
            text(line, node.text, style);
            size = line.finish();
        } else if (node.tag.equals("img") || node.tag.equals("svg")) {
            ImageStore.Image image =
                    node.tag.equals("svg")
                            ? images.inlineSvg(node.svgMarkup(styles.styles), origin)
                            : node.attr("src").isBlank()
                                    ? null
                                    : images.load(node.attr("src"), origin);
            double width = image == null ? 0 : image.width() * .75;
            double hint = length(style, "width", Double.NaN);
            double height = length(style, "height", Double.NaN);
            if (Double.isFinite(hint)) {
                width = hint;
            } else if (image != null && Double.isFinite(height)) {
                width = height * image.width() / image.height();
            }
            size = new Sizes(width, width);
        } else if (style.is("display", "flex") || style.is("display", "inline-flex")) {
            double minimum = 0;
            double preferred = 0;
            int count = 0;
            boolean column = style.get("flex-direction").startsWith("column");
            boolean wrap = !style.get("flex-wrap", "nowrap").equals("nowrap");
            for (HtmlNode child : node.children) {
                Sizes childSize = outer(child, depth + 1);
                if (childSize.preferred <= 0 && childSize.minimum <= 0) {
                    continue;
                }
                minimum =
                        column || wrap
                                ? Math.max(minimum, childSize.minimum)
                                : minimum + childSize.minimum;
                preferred =
                        column
                                ? Math.max(preferred, childSize.preferred)
                                : preferred + childSize.preferred;
                count++;
            }
            double gap = length(style, "column-gap", 0) * Math.max(0, count - 1);
            size = new Sizes(minimum + (column || wrap ? 0 : gap), preferred + (column ? 0 : gap));
        } else if (style.is("display", "grid") || style.is("display", "inline-grid")) {
            size = grid(node, style, depth);
        } else {
            Line line = new Line();
            generated(line, node, true);
            for (HtmlNode child : node.children) {
                flow(line, child, depth + 1);
            }
            generated(line, node, false);
            size = line.finish();
        }
        if (!node.tag.equals("#text")) {
            double explicit = length(style, "width", Double.NaN);
            double minimum = length(style, "min-width", 0);
            double maximum = length(style, "max-width", Double.POSITIVE_INFINITY);
            double preferred = Double.isFinite(explicit) ? explicit : size.preferred;
            double min =
                    Double.isFinite(explicit) ? Math.min(explicit, size.minimum) : size.minimum;
            size =
                    new Sizes(
                            Math.max(minimum, Math.min(maximum, min)),
                            Math.max(minimum, Math.min(maximum, preferred)));
        }
        cached.put(node, size);
        return size;
    }

    private Sizes grid(HtmlNode node, ComputedStyle style, int depth) {
        List<String> tracks =
                GridDefinition.tokens(style.get("grid-template-columns")).stream()
                        .filter(value -> !value.startsWith("["))
                        .toList();
        if (tracks.isEmpty()
                || tracks.contains("subgrid")
                || tracks.stream().anyMatch(value -> value.startsWith("repeat("))) {
            Line line = new Line();
            for (HtmlNode child : node.children) {
                flow(line, child, depth + 1);
            }
            return line.finish();
        }
        double[] minimum = new double[tracks.size()];
        double[] preferred = new double[tracks.size()];
        int cursor = 0;
        for (HtmlNode child : node.children) {
            Sizes size = outer(child, depth + 1);
            if (size.preferred <= 0) {
                continue;
            }
            ComputedStyle childStyle =
                    styles.style(child.tag.equals("#text") ? child.parent : child);
            int column = cursor % tracks.size();
            String start = childStyle.get("grid-column-start");
            if (start.matches("[1-9][0-9]*")) {
                column = Math.min(tracks.size() - 1, Integer.parseInt(start) - 1);
            }
            minimum[column] = Math.max(minimum[column], size.minimum);
            preferred[column] = Math.max(preferred[column], size.preferred);
            cursor++;
        }
        double min = 0;
        double max = 0;
        for (int index = 0; index < tracks.size(); index++) {
            String value = tracks.get(index);
            GridTracks.Track track =
                    GridTracks.parse(
                            value,
                            number ->
                                    CssValues.length(
                                            number,
                                            Double.NaN,
                                            metrics.font(style).size(),
                                            styles.rootFontSize,
                                            config.pageWidth,
                                            config.pageHeight,
                                            0));
            double lower =
                    track.minimumKind().equals("max-content") ? preferred[index] : minimum[index];
            min += Math.max(track.minimum(), track.intrinsic() ? lower : 0);
            max +=
                    Math.max(
                            track.minimum(),
                            Math.min(
                                    track.maximum(),
                                    track.intrinsic() || track.fraction() > 0
                                            ? preferred[index]
                                            : track.minimum()));
        }
        double gap = length(style, "column-gap", 0) * Math.max(0, tracks.size() - 1);
        return new Sizes(min + gap, max + gap);
    }

    private Sizes outer(HtmlNode node, int depth) {
        Sizes size = measure(node, depth);
        if (node.tag.equals("#text")) {
            return size;
        }
        ComputedStyle style = styles.style(node);
        if (style == null || style.is("display", "none")) {
            return size;
        }
        double extras = 0;
        for (String side : List.of("left", "right")) {
            extras += length(style, "margin-" + side, 0);
            if (!style.is("box-sizing", "border-box") || style.get("width").isBlank()) {
                extras += length(style, "padding-" + side, 0);
                if (!style.get("border-" + side + "-style", "none").equals("none")) {
                    extras += length(style, "border-" + side + "-width", 0);
                }
            }
        }
        return new Sizes(Math.max(0, size.minimum + extras), Math.max(0, size.preferred + extras));
    }

    private double length(ComputedStyle style, String property, double fallback) {
        return CssValues.length(
                style.get(property),
                Double.NaN,
                metrics.font(style).size(),
                styles.rootFontSize,
                config.pageWidth,
                config.pageHeight,
                fallback);
    }

    private void flow(Line line, HtmlNode node, int depth) {
        if (depth > config.maxDepth) {
            throw new PdfConversionException("Intrinsic inline depth exceeded");
        }
        ComputedStyle style = styles.style(node.tag.equals("#text") ? node.parent : node);
        if (style == null || style.is("display", "none")) {
            return;
        }
        if (node.tag.equals("#text")) {
            text(line, node.text, style);
        } else if (node.tag.equals("br")) {
            line.breakLine();
        } else if (style.is("display", "inline")
                && !node.tag.equals("img")
                && !node.tag.equals("svg")) {
            generated(line, node, true);
            for (HtmlNode child : node.children) {
                flow(line, child, depth + 1);
            }
            generated(line, node, false);
        } else {
            Sizes size = outer(node, depth + 1);
            boolean inline =
                    style.get("display").startsWith("inline")
                            || node.tag.equals("img")
                            || node.tag.equals("svg");
            if (!inline) {
                line.breakLine();
            }
            line.minimum = Math.max(line.minimum, size.minimum);
            line.currentLine += size.preferred;
            line.breakWord();
            if (!inline) {
                line.breakLine();
            }
        }
    }

    private void generated(Line line, HtmlNode node, boolean leading) {
        String value = (leading ? before : after).get(node);
        ComputedStyle style = (leading ? styles.before : styles.after).get(node);
        if (value != null && style != null && !style.is("display", "none")) {
            text(line, value, style);
        }
    }

    private void text(Line line, String text, ComputedStyle style) {
        boolean pre =
                style.is("white-space", "pre")
                        || style.is("white-space", "pre-wrap")
                        || style.is("white-space", "break-spaces");
        boolean newline = pre || style.is("white-space", "pre-line");
        boolean nowrap = style.is("white-space", "pre") || style.is("white-space", "nowrap");
        Matcher tokens = Pattern.compile("\\r\\n|\\r|\\n|[^\\s]+|[\\t \\f]+").matcher(text);
        while (tokens.find()) {
            String value = tokens.group();
            if ((value.equals("\n") || value.equals("\r") || value.equals("\r\n")) && newline) {
                line.breakLine();
                continue;
            }
            boolean whitespace = value.isBlank();
            if (whitespace && !pre) {
                if (line.currentLine > 0 && !line.space) {
                    line.currentLine += width(" ", style);
                }
                line.space = true;
                if (!nowrap) {
                    line.breakWord();
                }
                continue;
            }
            value = pre ? value.replace("\t", "    ") : value;
            double width = width(value, style);
            if (!nowrap
                    && (style.is("word-break", "break-all")
                            || style.is("overflow-wrap", "anywhere"))) {
                Matcher clusters = Pattern.compile("\\X").matcher(value);
                while (clusters.find()) {
                    line.minimum = Math.max(line.minimum, width(clusters.group(), style));
                }
                line.breakWord();
            } else if (nowrap) {
                if (line.space && line.currentWord > 0) {
                    line.currentWord += width(" ", style);
                }
                line.currentWord += width;
            } else {
                List<String> segments = LineBreaks.segments(value);
                for (int index = 0; index < segments.size(); index++) {
                    if (index > 0) {
                        line.breakWord();
                    }
                    line.currentWord +=
                            segments.size() == 1 ? width : width(segments.get(index), style);
                }
            }
            line.currentLine += width;
            line.space = false;
        }
    }

    private double width(String text, ComputedStyle style) {
        text =
                text.replace(
                                dtm.pdfloom.css.GeneratedContent.PAGE,
                                Integer.toString(pageNumber > 0 ? pageNumber : pageGuess))
                        .replace(
                                dtm.pdfloom.css.GeneratedContent.PAGES,
                                Integer.toString(pageGuess));
        String transformed =
                style.is("text-transform", "uppercase")
                        ? text.toUpperCase(java.util.Locale.ROOT)
                        : style.is("text-transform", "lowercase")
                                ? text.toLowerCase(java.util.Locale.ROOT)
                                : text;
        double width =
                metrics.runs(style, transformed).stream()
                        .mapToDouble(run -> metrics.width(run.text(), run.font()))
                        .sum();
        return width
                + length(style, "letter-spacing", 0)
                        * transformed.codePointCount(0, transformed.length())
                + length(style, "word-spacing", 0)
                        * transformed.chars().filter(character -> character == ' ').count();
    }

    private static final class Line {
        private double minimum;
        private double preferred;
        private double currentLine;
        private double currentWord;
        private boolean space;

        private void breakWord() {
            minimum = Math.max(minimum, currentWord);
            currentWord = 0;
        }

        private void breakLine() {
            breakWord();
            preferred = Math.max(preferred, currentLine);
            currentLine = 0;
            space = false;
        }

        private Sizes finish() {
            breakLine();
            return new Sizes(minimum, Math.max(minimum, preferred));
        }
    }
}
