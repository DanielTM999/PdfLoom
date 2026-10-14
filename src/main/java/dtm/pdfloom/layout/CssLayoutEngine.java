package dtm.pdfloom.layout;

import dtm.pdfloom.config.*;
import dtm.pdfloom.css.*;
import dtm.pdfloom.exception.PdfConversionException;
import dtm.pdfloom.font.TextMetrics;
import dtm.pdfloom.html.*;
import dtm.pdfloom.image.ImageStore;
import dtm.pdfloom.render.Paint;
import dtm.pdfloom.style.*;

import java.net.URI;
import java.util.*;
import java.util.regex.*;

public final class CssLayoutEngine implements LayoutEngine {
    private final PdfConversionConfig config;
    private final StyleSheet css;
    private final TextMetrics metrics;
    private final ImageStore images;
    private final URI origin;
    private final Map<HtmlNode, String> before = new IdentityHashMap<>(),
            after = new IdentityHashMap<>();
    private final Set<String> warnings = new HashSet<>();
    private final Map<HtmlNode, List<HtmlNode>> anonymousItems = new IdentityHashMap<>();
    private final Map<HtmlNode, double[]> collapsedInsets = new IdentityHashMap<>();
    private final Map<HtmlNode, GridDefinition.Axis> subgridColumns = new IdentityHashMap<>();
    private final Map<HtmlNode, GridDefinition.Axis> subgridRows = new IdentityHashMap<>();
    private final Map<HtmlNode, Double> subgridColumnGaps = new IdentityHashMap<>();
    private final Map<HtmlNode, Double> subgridRowGaps = new IdentityHashMap<>();
    private final int pageGuess;
    private final int pageNumber;
    private final Map<String, Integer> targetPages;
    private final Map<String, Map<String, List<Integer>>> targetCounters = new HashMap<>();
    private final List<ContentRequest> generatedRequests = new ArrayList<>();
    private final double pageContentHeight;
    private final dtm.pdfloom.page.PageSpec initialPage;
    private final Map<TextMetrics.Font, TextMetrics.Bounds> xHeights = new HashMap<>();
    private final IntrinsicMeasurements intrinsicMeasurements;

    private record Piece(
            String text,
            ComputedStyle style,
            String href,
            boolean newline,
            ImageStore.Image image,
            double iw,
            double ih,
            LayoutResult atomic) {
        Piece(
                String text,
                ComputedStyle style,
                String href,
                boolean newline,
                ImageStore.Image image,
                double iw,
                double ih) {
            this(text, style, href, newline, image, iw, ih, null);
        }
    }

    private record Cell(HtmlNode node, int row, int col, int rows, int cols, LayoutResult layout) {
    }

    public CssLayoutEngine(
            PdfConversionConfig c,
            StyleSheet css,
            TextMetrics m,
            ImageStore images,
            URI origin,
            int pageGuess) {
        this(c, css, m, images, origin, pageGuess, Map.of());
    }

    public CssLayoutEngine(
            PdfConversionConfig c,
            StyleSheet css,
            TextMetrics m,
            ImageStore images,
            URI origin,
            int pageGuess,
            Map<String, Integer> targetPages) {
        this(c, css, m, images, origin, pageGuess, targetPages, 0);
    }

    public CssLayoutEngine(
            PdfConversionConfig c,
            StyleSheet css,
            TextMetrics m,
            ImageStore images,
            URI origin,
            int pageGuess,
            Map<String, Integer> targetPages,
            int pageNumber) {
        config = c;
        this.css = css;
        metrics = m;
        this.images = images;
        this.origin = origin;
        this.pageGuess = pageGuess;
        this.pageNumber = pageNumber;
        this.targetPages = Map.copyOf(targetPages);
        intrinsicMeasurements =
                new IntrinsicMeasurements(
                        c, css, m, images, origin, before, after, pageGuess, pageNumber);
        initialPage = dtm.pdfloom.page.PageSpec.resolve(c, css, 1, "", false);
        pageContentHeight = initialPage.contentHeight();
    }

    private void warn(String code, String message) {
        if (warnings.add(code + message)) {
            config.report(new Diagnostic(Diagnostic.Severity.WARNING, code, message));
        }
    }

    @Override
    public LayoutResult layout(HtmlParser.Document doc, double width) {
        prepareCounters(doc.body());
        LayoutResult r = box(doc.body(), width, null, 0);
        r.height = Math.max(r.height, 1);
        return r;
    }

    public LayoutResult layoutElement(HtmlNode node, double width, HtmlNode documentBody) {
        prepareCounters(documentBody);
        return box(node, width, null, 0);
    }

    private ComputedStyle style(HtmlNode n) {
        if (n.tag.equals("#text")) {
            return css.style(n.parent);
        }
        return css.style(n);
    }

    private double em(ComputedStyle s) {
        return metrics.font(s).size();
    }

    private CssNumericValue.Context unitContext(ComputedStyle style, double base) {
        TextMetrics.Font font = metrics.font(style);
        TextMetrics.Bounds x =
                xHeights.computeIfAbsent(font, selected -> metrics.bounds("x", selected));
        return new CssNumericValue.Context(
                base,
                font.size(),
                css.rootFontSize,
                initialPage.width(),
                initialPage.height(),
                x.ascent() + x.descent(),
                metrics.width("0", font));
    }

    private double len(ComputedStyle style, String property, double base, double fallback) {
        return CssValues.length(style.get(property), unitContext(style, base), fallback);
    }

    private double edge(ComputedStyle s, String kind, String side, double base) {
        String v = s.get(kind + "-" + side);
        if (v.isBlank()) {
            List<String> t = CssValues.tokens(s.get(kind));
            if (!t.isEmpty()) {
                int i = List.of("top", "right", "bottom", "left").indexOf(side);
                v =
                        t.get(
                                i == 0
                                        ? 0
                                        : i == 1
                                                ? Math.min(1, t.size() - 1)
                                                : i == 2
                                                        ? Math.min(2, t.size() - 1)
                                                        : t.size() == 4
                                                                ? 3
                                                                : Math.min(1, t.size() - 1));
            }
        }
        return CssValues.length(v, unitContext(s, base), 0);
    }

    private double border(ComputedStyle s, String side) {
        if (Set.of("none", "hidden").contains(s.get("border-" + side + "-style", "none"))) {
            return 0;
        }
        String w = s.get("border-" + side + "-width", "0");
        return switch (w) {
            case "thin" -> .75;
            case "medium" -> 2.25;
            case "thick" -> 3.75;
            default -> Math.max(0, CssValues.length(w, unitContext(s, 0), 0));
        };
    }

    private double opacity(ComputedStyle s) {
        try {
            return Math.max(0, Math.min(1, Double.parseDouble(s.get("opacity", "1"))));
        } catch (Exception e) {
            return 1;
        }
    }

    private String normalizeShadow(ComputedStyle style, String property) {
        String value = style.get(property, "none");
        if (value.isBlank() || value.equals("none")) {
            return "none";
        }
        List<String> shadows = new ArrayList<>();
        for (String shadow : CssValues.commaSeparated(value)) {
            List<String> tokens = new ArrayList<>();
            for (String token : CssValues.tokens(shadow)) {
                if (token.equals("currentcolor")) {
                    tokens.add(style.get("color", "black"));
                } else if (token.matches("[+-]?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+).*")
                        || token.startsWith("calc(")
                        || token.startsWith("var(")) {
                    double length =
                            CssValues.length(
                                    token,
                                    0,
                                    em(style),
                                    css.rootFontSize,
                                    config.pageWidth,
                                    config.pageHeight,
                                    Double.NaN);
                    if (!Double.isFinite(length)) {
                        throw new PdfConversionException("Invalid shadow length: " + token);
                    }
                    tokens.add(length + "pt");
                } else {
                    tokens.add(token);
                }
            }
            if (tokens.stream()
                    .noneMatch(token -> !token.endsWith("pt") && !token.equals("inset"))) {
                tokens.add(style.get("color", "black"));
            }
            shadows.add(String.join(" ", tokens));
        }
        return String.join(",", shadows);
    }

    private Paint.Rectangle rectangle(ComputedStyle s, double x, double y, double w, double h) {
        List<Paint.Border> b = new ArrayList<>();
        for (String side : List.of("top", "right", "bottom", "left")) {
            b.add(
                    new Paint.Border(
                            border(s, side),
                            s.get("border-" + side + "-color", s.get("color", "black")),
                            s.get("border-" + side + "-style", "none")));
        }
        return new Paint.Rectangle(
                x,
                y,
                w,
                h,
                s.get("background-color", "transparent"),
                b,
                len(s, "border-radius", w, 0),
                paintOpacity(s),
                normalizeShadow(s, "box-shadow"));
    }

    private LayoutResult box(HtmlNode n, double available, Double forcedWidth, int depth) {
        if (depth > config.maxDepth) {
            throw new PdfConversionException("Layout depth limit exceeded");
        }
        ComputedStyle s = style(n);
        LayoutResult out = new LayoutResult();
        out.root = new LayoutBox(n, s);
        if (s == null || s.is("display", "none")) {
            return out;
        }
        double ml = edge(s, "margin", "left", available),
                mr = edge(s, "margin", "right", available),
                mt = edge(s, "margin", "top", available),
                mb = edge(s, "margin", "bottom", available);
        boolean collapsedTable =
                (s.is("display", "table") || s.is("display", "inline-table"))
                        && s.is("border-collapse", "collapse");
        double borderScale = collapsedCell(n) || collapsedTable ? .5 : 1;
        double paddingScale = collapsedTable ? 0 : 1;
        double[] insets =
                collapsedCell(n) && collapsedInsets.containsKey(n)
                        ? collapsedInsets.get(n)
                        : new double[] {
                            border(s, "top") * borderScale,
                            border(s, "right") * borderScale,
                            border(s, "bottom") * borderScale,
                            border(s, "left") * borderScale
                        };
        double pl = edge(s, "padding", "left", available) * paddingScale + insets[3],
                pr = edge(s, "padding", "right", available) * paddingScale + insets[1],
                pt = edge(s, "padding", "top", available) * paddingScale + insets[0],
                pb = edge(s, "padding", "bottom", available) * paddingScale + insets[2];
        ImageStore.Image replaced =
                (n.tag.equals("svg")
                                || Set.of("img", "svg").contains(n.tag) && !n.attr("src").isBlank())
                        ? loadImage(n)
                        : null;
        double naturalWidth = available - ml - mr;
        if (replaced != null) {
            double imageHeight = usedHeight(n);
            double intrinsicWidth = attrDimension(n, "width", replaced.width() * .75);
            if (!Double.isNaN(imageHeight)) {
                intrinsicWidth =
                        Math.max(0, imageHeight - (s.is("box-sizing", "border-box") ? pt + pb : 0))
                                * replaced.width()
                                / replaced.height();
            }
            naturalWidth = intrinsicWidth + pl + pr;
        }
        double specified = len(s, "width", available, Double.NaN),
                w =
                        forcedWidth != null
                                ? forcedWidth
                                : Double.isNaN(specified)
                                        ? naturalWidth
                                        : specified
                                                + (s.is("box-sizing", "border-box")
                                                                || collapsedTable
                                                        ? 0
                                                        : pl + pr);
        w =
                Math.max(
                        len(s, "min-width", available, 0)
                                + (s.is("box-sizing", "border-box") ? 0 : pl + pr),
                        Math.min(
                                w,
                                len(s, "max-width", available, Double.MAX_VALUE)
                                        + (s.is("box-sizing", "border-box") ? 0 : pl + pr)));
        w = Math.max(.1, w);
        boolean flexItem =
                n.parent != null
                        && style(n.parent) != null
                        && Set.of("flex", "inline-flex").contains(style(n.parent).get("display"));
        if (!flexItem && s.is("margin-left", "auto") && s.is("margin-right", "auto")) {
            ml = Math.max(0, (available - w) / 2);
        } else if (!flexItem && s.is("margin-left", "auto")) {
            ml = Math.max(0, available - w - mr);
        }
        double cw = Math.max(.1, w - pl - pr);
        LayoutResult inner;
        if (Set.of("img", "svg").contains(n.tag)) {
            double imageHeight = usedHeight(n);
            double contentHeight =
                    Double.isNaN(imageHeight)
                            ? attrDimension(
                                    n,
                                    "height",
                                    replaced == null
                                            ? 0
                                            : cw * replaced.height() / replaced.width())
                            : Math.max(
                                    0,
                                    imageHeight - (s.is("box-sizing", "border-box") ? pt + pb : 0));
            double min = len(s, "min-height", pageContentHeight, 0);
            double max = len(s, "max-height", pageContentHeight, Double.POSITIVE_INFINITY);
            if (s.is("box-sizing", "border-box")) {
                min = Math.max(0, min - pt - pb);
                max = Math.max(0, max - pt - pb);
            }
            inner = image(n, s, cw, Math.max(min, Math.min(max, contentHeight)), replaced);
        } else if (s.is("display", "table") || s.is("display", "inline-table")) {
            inner = table(n, cw, depth + 1);
        } else if (s.is("display", "flex") || s.is("display", "inline-flex")) {
            inner = flex(n, cw, depth + 1);
        } else if (s.is("display", "grid") || s.is("display", "inline-grid")) {
            inner = grid(n, cw, depth + 1);
        } else if ((!s.get("column-count").isBlank()
                        && !s.is("column-count", "auto")
                        && !s.is("column-count", "1"))
                || (!s.get("column-width").isBlank() && !s.is("column-width", "auto"))) {
            inner = columns(n, cw, depth + 1);
        } else {
            inner = flow(n, cw, depth + 1);
        }
        if ((s.is("display", "table") || s.is("display", "inline-table")) && inner.root != null) {
            w = Math.max(w, inner.root.width + pl + pr);
        }
        double contentH = inner.height;
        double wanted = usedHeight(n);
        if (!Double.isNaN(wanted)) {
            double specifiedHeight =
                    s.is("box-sizing", "border-box") ? Math.max(0, wanted - pt - pb) : wanted;
            contentH =
                    s.is("display", "table-cell")
                            ? Math.max(contentH, specifiedHeight)
                            : specifiedHeight;
        }
        double minHeight = len(s, "min-height", config.pageHeight, 0);
        double maxHeight = len(s, "max-height", config.pageHeight, Double.POSITIVE_INFINITY);
        if (s.is("box-sizing", "border-box")) {
            minHeight = Math.max(0, minHeight - pt - pb);
            maxHeight = Math.max(0, maxHeight - pt - pb);
        }
        contentH = Math.max(minHeight, Math.min(maxHeight, contentH));
        double h = pt + contentH + pb;
        double dx = ml, dy = mt;
        if (s.is("position", "relative")) {
            dx += len(s, "left", available, 0) - len(s, "right", available, 0);
            dy += len(s, "top", h, 0) - len(s, "bottom", h, 0);
        }
        String background = s.get("background-image");
        if (!background.isBlank() && !background.equals("none")) {
            ImageStore.Image raster =
                    dtm.pdfloom.image.BackgroundPainter.paint(
                            s,
                            w,
                            h,
                            new double[] {
                                border(s, "top"),
                                border(s, "right"),
                                border(s, "bottom"),
                                border(s, "left")
                            },
                            new double[] {
                                edge(s, "padding", "top", available),
                                edge(s, "padding", "right", available),
                                edge(s, "padding", "bottom", available),
                                edge(s, "padding", "left", available)
                            },
                            em(s),
                            css.rootFontSize,
                            images,
                            origin);
            if (raster != null) {
                out.commands.add(new Paint.Image(dx, dy, w, h, raster.key(), paintOpacity(s), ""));
            }
            Map<String, String> borderValues = new HashMap<>(s.values());
            borderValues.put("background-color", "transparent");
            out.commands.add(rectangle(new ComputedStyle(borderValues), dx, dy, w, h));
        } else if (collapsedTable) {
            Paint.Rectangle tableBackground = rectangle(s, dx, dy, w, h);
            out.commands.add(
                    new Paint.Rectangle(
                            tableBackground.x(),
                            tableBackground.y(),
                            tableBackground.width(),
                            tableBackground.height(),
                            tableBackground.fill(),
                            java.util.Collections.nCopies(
                                    4, new Paint.Border(0, "transparent", "none")),
                            tableBackground.radius(),
                            tableBackground.opacity(),
                            tableBackground.shadow()));
        } else {
            out.commands.add(rectangle(s, dx, dy, w, h));
        }
        if (!n.attr("id").isBlank() || n.tag.matches("h[1-6]")) {
            String id =
                    n.attr("id").isBlank()
                            ? "heading-" + Integer.toHexString(System.identityHashCode(n))
                            : n.attr("id");
            out.commands.add(
                    new Paint.Destination(
                            dx,
                            dy,
                            id,
                            n.tag.matches("h[1-6]") ? Integer.parseInt(n.tag.substring(1)) : 0,
                            n.textContent().strip()));
        }
        if (!s.get("string-set").isBlank()) {
            List<String> tokens = CssValues.tokens(s.get("string-set"));
            if (tokens.size() > 1) {
                String content = s.get("string-set").substring(tokens.get(0).length()).trim();
                out.strings.add(
                        new LayoutResult.NamedString(
                                tokens.get(0),
                                dy,
                                content.equals("content(text)") || content.equals("content()")
                                        ? n.textContent().strip()
                                        : GeneratedContent.evaluate(content, n, Map.of())));
            }
        }
        out.append(inner, dx + pl, dy + pt);
        boolean keepMargin = s.is("margin-break", "keep");
        boolean avoidBreak = s.is("break-inside", "avoid") || s.is("page-break-inside", "avoid");
        if (!s.get("margin-break").isBlank()
                && !Set.of("auto", "keep").contains(s.get("margin-break"))) {
            warn("UNSUPPORTED_MARGIN_BREAK", s.get("margin-break"));
        }
        if (keepMargin && !avoidBreak) {
            warn("PARTIAL_MARGIN_BREAK", "Keeping margins currently requires an indivisible block");
        }
        out.breaks.add(keepMargin && avoidBreak ? 0d : mt);
        out.breaks.add(mt + h);
        out.height = mt + h + mb;
        out.root.x = dx;
        out.root.y = dy;
        out.root.width = w;
        out.root.height = h;
        out.root.naturalHeight = pt + inner.height + pb;
        if (inner.root != null) {
            out.root.children.add(inner.root);
        }
        if (avoidBreak) {
            out.avoid.add(new LayoutResult.Interval(keepMargin ? 0 : mt, mt + h));
        }
        String bb = s.get("break-before", s.get("page-break-before")),
                ba = s.get("break-after", s.get("page-break-after"));
        if (Set.of("page", "always", "left", "right").contains(bb)) {
            out.forced.add(0d);
        }
        if (Set.of("page", "always", "left", "right").contains(ba)) {
            out.forced.add(out.height);
        }
        if (bb.equals("left") || bb.equals("right")) {
            out.parity.add(new LayoutResult.BreakParity(0, bb));
        }
        if (ba.equals("left") || ba.equals("right")) {
            out.parity.add(new LayoutResult.BreakParity(out.height, ba));
        }
        if (bb.equals("avoid")) {
            out.avoid.add(new LayoutResult.Interval(-.01, Math.min(out.height, mt + h)));
        }
        if (ba.equals("avoid")) {
            out.avoid.add(new LayoutResult.Interval(Math.max(0, mt + h - .01), out.height + .01));
        }
        if (!s.get("page").isBlank() && !s.is("page", "auto")) {
            out.pageNames.add(0, new LayoutResult.PageSwitch(0, s.get("page")));
            out.pageNames.add(new LayoutResult.PageSwitch(out.height, enclosingPage(n)));
            out.forced.add(0d);
            out.forced.add(out.height);
        }
        if (s.is("visibility", "hidden")) {
            out.commands.clear();
        }
        if (Set.of("hidden", "clip", "scroll", "auto").contains(s.get("overflow"))
                || (!s.get("transform").isBlank() && !s.is("transform", "none"))) {
            boolean clip = !s.get("overflow").isBlank() && !s.is("overflow", "visible");
            if (clip && Double.isNaN(wanted)) {
                warn("AUTO_HEIGHT_CLIP", "Overflow clipping uses the content-derived height");
            }
            double clipH =
                    !Double.isNaN(wanted)
                            ? (s.is("box-sizing", "border-box") ? wanted : wanted + pt + pb)
                            : h;
            List<Paint> commands = List.copyOf(out.commands);
            out.commands.clear();
            out.commands.add(
                    new Paint.Group(
                            dx,
                            dy,
                            w,
                            Math.max(0, clipH),
                            commands,
                            clip,
                            s.get("transform"),
                            s.get("transform-origin", "50% 50%")));
            if (clip) {
                double lower = dy;
                double upper = dy + Math.max(0, clipH);
                List<LayoutResult.Interval> visibleLines =
                        out.lines.stream()
                                .filter(line -> line.end() > lower && line.start() < upper)
                                .map(
                                        line ->
                                                new LayoutResult.Interval(
                                                        Math.max(lower, line.start()),
                                                        Math.min(upper, line.end())))
                                .toList();
                out.lines.clear();
                out.lines.addAll(visibleLines);
                out.breaks.removeIf(value -> value < lower || value > upper);
                out.breaks.add(upper);
            }
        }
        boolean positioned = !s.get("position", "static").equals("static");
        boolean flexOrGridItem =
                n.parent != null
                        && style(n.parent) != null
                        && Set.of("flex", "inline-flex", "grid", "inline-grid")
                                .contains(style(n.parent).get("display"));
        boolean explicitZ = !s.get("z-index", "auto").equals("auto") && !s.get("z-index").isBlank();
        boolean transformed = !s.get("transform").isBlank() && !s.is("transform", "none");
        if (positioned || transformed || opacity(s) < 1 || (flexOrGridItem && explicitZ)) {
            int order = explicitZ && (positioned || flexOrGridItem) ? zIndex(s) : 0;
            boolean context =
                    explicitZ || transformed || opacity(s) < 1 || s.is("position", "fixed");
            List<Paint> commands = List.copyOf(out.commands);
            out.commands.clear();
            out.commands.add(new Paint.Layer(dx, dy, w, h, commands, order, context, opacity(s)));
        }
        return out;
    }

    private int zIndex(ComputedStyle style) {
        try {
            return Integer.parseInt(style.get("z-index"));
        } catch (NumberFormatException exception) {
            warn("INVALID_Z_INDEX", style.get("z-index"));
            return 0;
        }
    }

    private String enclosingPage(HtmlNode node) {
        for (HtmlNode parent = node.parent; parent != null; parent = parent.parent) {
            ComputedStyle computed = style(parent);
            if (computed != null && !computed.get("page").isBlank() && !computed.is("page", "auto")) {
                return computed.get("page");
            }
        }
        return "";
    }

    private boolean collapsedCell(HtmlNode node) {
        if (!style(node).is("display", "table-cell")) {
            return false;
        }
        for (HtmlNode parent = node.parent; parent != null; parent = parent.parent) {
            ComputedStyle computed = style(parent);
            if (computed != null
                    && (computed.is("display", "table")
                            || computed.is("display", "inline-table"))) {
                return computed.is("border-collapse", "collapse");
            }
        }
        return false;
    }

    private double usedHeight(HtmlNode node) {
        ComputedStyle computed = style(node);
        String value = computed.get("height");
        double basis = pageContentHeight;
        if (value.contains("%")
                && !computed.is("position", "absolute")
                && !computed.is("position", "fixed")
                && node.parent != null
                && !node.parent.tag.equals("html")) {
            basis = usedHeight(node.parent);
            if (Double.isNaN(basis)) {
                return Double.NaN;
            }
        }
        return CssValues.length(
                value,
                basis,
                em(computed),
                css.rootFontSize,
                config.pageWidth,
                config.pageHeight,
                Double.NaN);
    }

    private double paintOpacity(ComputedStyle style) {
        return style.is("display", "inline") ? opacity(style) : 1;
    }

    private ImageStore.Image loadImage(HtmlNode node) {
        return node.tag.equals("svg")
                ? images.inlineSvg(node.svgMarkup(css.styles), origin)
                : images.load(node.attr("src"), origin);
    }

    private LayoutResult image(
            HtmlNode n, ComputedStyle s, double width, double height, ImageStore.Image im) {
        LayoutResult result = new LayoutResult();
        if (im == null || width <= 0 || height <= 0) {
            return result;
        }
        String fit = s.get("object-fit", "fill");
        if (!fit.isBlank()
                && !Set.of("fill", "contain", "cover", "none", "scale-down").contains(fit)) {
            warn("INVALID_OBJECT_FIT", fit);
            fit = "fill";
        }
        String position = s.get("object-position", "50% 50%");
        if (CssValues.tokens(position).size() > 4) {
            warn("UNSUPPORTED_OBJECT_POSITION", position);
            position = "50% 50%";
        }
        dtm.pdfloom.image.ImagePlacement placement =
                dtm.pdfloom.image.ImagePlacement.resolve(
                        im.width() * .75,
                        im.height() * .75,
                        width,
                        height,
                        fit,
                        position,
                        em(s),
                        css.rootFontSize,
                        config.pageWidth,
                        config.pageHeight);
        Paint.Image paint =
                new Paint.Image(
                        placement.x(),
                        placement.y(),
                        placement.width(),
                        placement.height(),
                        im.key(),
                        paintOpacity(s),
                        href(n));
        result.commands.add(
                new Paint.Group(0, 0, width, height, List.of(paint), true, "none", "50% 50%"));
        result.height = height;
        result.breaks.add(0d);
        result.breaks.add(height);
        result.lines.add(new LayoutResult.Interval(0, height));
        return result;
    }

    private double attrDimension(HtmlNode n, String a, double fallback) {
        try {
            return Double.parseDouble(n.attr(a)) * .75;
        } catch (Exception e) {
            return fallback;
        }
    }

    private String href(HtmlNode n) {
        for (HtmlNode p = n; p != null; p = p.parent) {
            if (p.tag.equals("a")) {
                return p.attr("href");
            }
        }
        return "";
    }

    private boolean inline(HtmlNode n) {
        if (n.tag.equals("#text")) {
            return true;
        }
        String d = style(n).get("display");
        return Set.of("inline", "inline-block", "inline-flex", "inline-grid", "inline-table")
                .contains(d);
    }

    private LayoutResult flow(HtmlNode n, double width, int depth) {
        LayoutResult r = new LayoutResult();
        r.root = new LayoutBox(n, style(n));
        List<Piece> pending = new ArrayList<>();
        double y = 0;
        double floatLeft = 0, floatRight = 0, floatEnd = 0;
        double previousBottom = 0;
        addGenerated(pending, n, true);
        Piece outsideMarker = null;
        if (n.tag.equals("li")) {
            String marker = "• ";
            if (n.parent != null && n.parent.tag.equals("ol")) {
                int step = n.parent.attributes.containsKey("reversed") ? -1 : 1;
                int index =
                        step < 0
                                ? (int)
                                        n.parent.children.stream()
                                                .filter(child -> child.tag.equals("li"))
                                                .count()
                                : 1;
                try {
                    if (!n.parent.attr("start").isBlank()) {
                        index = Integer.parseInt(n.parent.attr("start"));
                    }
                } catch (NumberFormatException exception) {
                    warn("INVALID_LIST_START", n.parent.attr("start"));
                }
                for (HtmlNode c : n.parent.children) {
                    if (c.tag.equals("li")) {
                        if (!c.attr("value").isBlank()) {
                            try {
                                index = Integer.parseInt(c.attr("value"));
                            } catch (NumberFormatException exception) {
                                warn("INVALID_LIST_VALUE", c.attr("value"));
                            }
                        }
                        if (c == n) {
                            break;
                        }
                        index += step;
                    }
                }
                marker =
                        GeneratedContent.format(index, style(n).get("list-style-type", "decimal"))
                                + ". ";
            } else if (style(n).is("list-style-type", "none")) {
                marker = "";
            } else if (style(n).is("list-style-type", "square")) {
                marker = "▪ ";
            }
            Matcher markerUrl =
                    Pattern.compile("url\\(\\s*['\"]?(.*?)['\"]?\\s*\\)")
                            .matcher(style(n).get("list-style-image"));
            Piece piece = new Piece(marker, style(n), "", false, null, 0, 0);
            if (markerUrl.find()) {
                ImageStore.Image image = images.load(markerUrl.group(1), origin);
                if (image != null) {
                    piece =
                            new Piece(
                                    "",
                                    style(n),
                                    "",
                                    false,
                                    image,
                                    image.width() * .75,
                                    image.height() * .75);
                }
            }
            if (style(n).is("list-style-position", "inside")) {
                pending.add(piece);
            } else {
                outsideMarker = piece;
            }
        }
        for (HtmlNode child : n.children) {
            ComputedStyle s = style(child);
            if (s == null || s.is("display", "none")) {
                continue;
            }
            if (!child.tag.equals("#text") && s.get("position").startsWith("running(")) {
                String run = s.get("position");
                String runName = run.substring(run.indexOf('(') + 1, run.lastIndexOf(')')).trim();
                LayoutResult placed = box(child, width, null, depth);
                r.running.add(
                        new LayoutResult.Running(
                                runName, y, placed.height, List.copyOf(placed.commands), child));
                continue;
            }
            if (!child.tag.equals("#text")
                    && (s.is("position", "absolute") || s.is("position", "fixed"))) {
                LayoutResult placed = box(child, width, null, depth);
                double containingHeight =
                        s.is("position", "fixed") ? pageContentHeight : usedHeight(n);
                if (Double.isNaN(containingHeight)) {
                    containingHeight = pageContentHeight;
                }
                double xx = len(s, "left", width, Double.NaN),
                        yy = len(s, "top", containingHeight, Double.NaN);
                if (Double.isNaN(xx)) {
                    xx =
                            s.get("right").isBlank()
                                    ? 0
                                    : width - len(s, "right", width, 0) - placed.root.width;
                }
                if (Double.isNaN(yy)) {
                    yy =
                            s.get("bottom").isBlank()
                                    ? y
                                    : containingHeight
                                            - len(s, "bottom", config.pageHeight, 0)
                                            - placed.height;
                }
                if (s.is("position", "fixed")) {
                    for (Paint p : placed.commands) {
                        r.fixed.add(p.move(xx, yy));
                    }
                } else {
                    r.append(placed, xx, yy);
                }
                continue;
            }
            if (!child.tag.equals("#text") && (s.is("float", "left") || s.is("float", "right"))) {
                if (y >= floatEnd - .001) {
                    floatLeft = floatRight = 0;
                }
                if (!s.get("clear").isBlank() && !s.is("clear", "none")) {
                    y = Math.max(y, floatEnd);
                    floatLeft = floatRight = 0;
                }
                double specifiedFloat = len(s, "width", width, Double.NaN);
                double fw =
                        Double.isNaN(specifiedFloat)
                                ? Math.min(width / 2, intrinsic(child, false))
                                : specifiedFloat
                                        + (s.is("box-sizing", "border-box")
                                                ? 0
                                                : edge(s, "padding", "left", width)
                                                        + edge(s, "padding", "right", width)
                                                        + border(s, "left")
                                                        + border(s, "right"));
                double margins =
                        edge(s, "margin", "left", width) + edge(s, "margin", "right", width);
                if (fw + margins + floatLeft + floatRight > width && floatEnd > y + .001) {
                    y = floatEnd;
                    floatLeft = floatRight = 0;
                }
                LayoutResult floated = box(child, width, fw, depth);
                double occupied = floated.root.width + margins;
                double x = s.is("float", "right") ? width - occupied - floatRight : floatLeft;
                r.append(floated, x, y);
                if (s.is("float", "right")) {
                    floatRight += occupied;
                } else {
                    floatLeft += occupied;
                }
                floatEnd = Math.max(floatEnd, y + floated.height);
                continue;
            }
            if (inline(child)) {
                collect(child, pending, depth, width);
                continue;
            }
            if (!pending.isEmpty()) {
                LayoutResult text =
                        lines(pending, Math.max(.1, width - floatLeft - floatRight), style(n));
                r.append(text, floatLeft, y);
                y += text.height;
                pending.clear();
                if (text.height > .001) {
                    previousBottom = 0;
                }
            }
            if (y >= floatEnd) {
                floatLeft = floatRight = 0;
            }
            if (!s.get("clear").isBlank() && !s.is("clear", "none")) {
                y = Math.max(y, floatEnd);
                floatLeft = floatRight = 0;
            }
            double top = edge(s, "margin", "top", width);
            double collapsed =
                    Math.min(Math.max(0, top), Math.max(0, previousBottom))
                            + Math.max(Math.min(0, top), Math.min(0, previousBottom));
            LayoutResult block =
                    box(child, Math.max(.1, width - floatLeft - floatRight), null, depth);
            if (!block.forced.contains(0d) && !r.forced.contains(y)) {
                y -= collapsed;
            }
            r.append(block, floatLeft, y);
            r.root.children.add(block.root);
            y += block.height;
            previousBottom = edge(s, "margin", "bottom", width);
        }
        addGenerated(pending, n, false);
        if (!pending.isEmpty()) {
            LayoutResult text =
                    lines(pending, Math.max(.1, width - floatLeft - floatRight), style(n));
            r.append(text, floatLeft, y);
            y += text.height;
        }
        r.height = Math.max(y, floatEnd);
        if (outsideMarker != null) {
            double baseline = firstBaseline(r);
            if (outsideMarker.image != null) {
                double shift = Math.max(0, outsideMarker.ih - baseline);
                if (shift > 0) {
                    List<Paint> shifted =
                            r.commands.stream().map(command -> command.move(0, shift)).toList();
                    r.commands.clear();
                    r.commands.addAll(shifted);
                    r.height += shift;
                    baseline += shift;
                }
                r.commands.add(
                        0,
                        new Paint.Image(
                                -outsideMarker.iw - em(style(n)) / 2,
                                baseline - outsideMarker.ih,
                                outsideMarker.iw,
                                outsideMarker.ih,
                                outsideMarker.image.key(),
                                paintOpacity(style(n)),
                                ""));
            } else if (!outsideMarker.text.isBlank()) {
                TextMetrics.Font font = metrics.font(style(n), outsideMarker.text);
                double markerWidth = textWidth(outsideMarker.text.stripTrailing(), font, style(n));
                Paint.Text marker =
                        textCommand(
                                -markerWidth - em(style(n)) / 2,
                                outsideMarker.text.stripTrailing(),
                                style(n),
                                font,
                                markerWidth,
                                "");
                r.commands.add(0, marker.move(0, baseline - marker.y() - marker.ascent()));
            }
        }
        return r;
    }

    private void addGenerated(List<Piece> p, HtmlNode n, boolean first) {
        ComputedStyle s = (first ? css.before : css.after).get(n);
        String text = (first ? before : after).getOrDefault(n, "");
        if (s != null && !text.isEmpty()) {
            p.add(new Piece(text, s, href(n), false, null, 0, 0));
        }
    }

    private void collect(HtmlNode n, List<Piece> p, int depth, double available) {
        if (depth > config.maxDepth) {
            throw new PdfConversionException("Inline depth limit exceeded");
        }
        ComputedStyle s = style(n);
        if (s == null || s.is("display", "none")) {
            return;
        }
        if (n.tag.equals("#text")) {
            p.add(new Piece(n.text, s, href(n), false, null, 0, 0));
            return;
        }
        if (!n.attr("id").isBlank() || n.tag.equals("a") && !n.attr("name").isBlank()) {
            String id = n.attr("id").isBlank() ? n.attr("name") : n.attr("id");
            LayoutResult anchor = new LayoutResult();
            anchor.commands.add(new Paint.Destination(0, 0, id, 0, ""));
            p.add(new Piece("", s, "", false, null, 0, 0, anchor));
        }
        if (n.tag.equals("br")) {
            p.add(new Piece("", s, "", true, null, 0, 0));
            return;
        }
        if (Set.of("img", "svg").contains(n.tag)) {
            LayoutResult atomic = box(n, available, null, depth + 1);
            double width =
                    atomic.root.width
                            + edge(s, "margin", "left", available)
                            + edge(s, "margin", "right", available);
            p.add(new Piece("", s, href(n), false, null, width, atomic.height, atomic));
            return;
        }
        if (Set.of("inline-block", "inline-flex", "inline-grid", "inline-table")
                .contains(s.get("display"))) {
            double extras =
                    edge(s, "padding", "left", available)
                            + edge(s, "padding", "right", available)
                            + border(s, "left")
                            + border(s, "right");
            double margins =
                    edge(s, "margin", "left", available) + edge(s, "margin", "right", available);
            double minimum = intrinsic(n, true) + extras;
            double preferred = intrinsic(n, false) + extras;
            double natural = Math.min(Math.max(minimum, available - margins), preferred);
            LayoutResult atomic =
                    box(n, available, s.get("width").isBlank() ? natural : null, depth + 1);
            double w =
                    atomic.root.width
                            + edge(s, "margin", "left", available)
                            + edge(s, "margin", "right", available);
            p.add(new Piece("", s, href(n), false, null, w, atomic.height, atomic));
            return;
        }
        addGenerated(p, n, true);
        for (HtmlNode c : n.children) {
            collect(c, p, depth + 1, available);
        }
        addGenerated(p, n, false);
    }

    private String transform(String t, ComputedStyle s) {
        return switch (s.get("text-transform")) {
            case "uppercase" -> t.toUpperCase(Locale.ROOT);
            case "lowercase" -> t.toLowerCase(Locale.ROOT);
            case "capitalize" -> {
                StringBuilder b = new StringBuilder();
                boolean start = true;
                for (int cp : t.codePoints().toArray()) {
                    b.appendCodePoint(start ? Character.toTitleCase(cp) : cp);
                    start = Character.isWhitespace(cp);
                }
                yield b.toString();
            }
            default -> t;
        };
    }

    private String measurable(String text) {
        return text.replace(
                        GeneratedContent.PAGE,
                        Integer.toString(pageNumber > 0 ? pageNumber : pageGuess))
                .replace(GeneratedContent.PAGES, Integer.toString(pageGuess));
    }

    private double textWidth(String t, TextMetrics.Font f, ComputedStyle s) {
        double letter = len(s, "letter-spacing", 0, 0), word = len(s, "word-spacing", 0, 0);
        return metrics.width(measurable(t), f)
                + letter * t.codePointCount(0, t.length())
                + word * t.chars().filter(c -> c == ' ').count();
    }

    private double lineHeight(ComputedStyle s) {
        double e = em(s);
        String v = s.get("line-height", "normal");
        if (v.equals("normal")) {
            return e * 1.2;
        }
        try {
            if (v.matches("[0-9.]+")) {
                return Double.parseDouble(v) * e;
            }
        } catch (Exception ignored) {
        }
        return Math.max(
                e * .1,
                CssValues.length(
                        v, e, e, css.rootFontSize, config.pageWidth, config.pageHeight, e * 1.2));
    }

    private LayoutResult lines(List<Piece> pieces, double width, ComputedStyle container) {
        LayoutResult result = new LayoutResult();
        List<Paint> line = new ArrayList<>();
        double indent = len(container, "text-indent", width, 0),
                x = indent,
                y = 0,
                lh = lineHeight(container);
        boolean first = true, pendingSpace = false;
        List<Double> ends = new ArrayList<>();
        for (Piece piece : pieces) {
            ComputedStyle s = piece.style;
            TextMetrics.Font font = metrics.font(s, piece.text == null ? "" : piece.text);
            String ws = s.get("white-space", "normal");
            boolean pre = ws.startsWith("pre") || ws.equals("break-spaces"),
                    wrap = !ws.equals("nowrap") && !ws.equals("pre");
            if (piece.newline) {
                finishLine(result, line, y, lh, width, x, container);
                y += lh;
                ends.add(y);
                x = 0;
                lh = lineHeight(container);
                first = false;
                pendingSpace = false;
                continue;
            }
            if (piece.image != null || piece.atomic != null) {
                if (x + piece.iw > width && !line.isEmpty()) {
                    finishLine(result, line, y, lh, width, x, container);
                    y += lh;
                    ends.add(y);
                    x = 0;
                    lh = lineHeight(container);
                    first = false;
                }
                if (piece.atomic != null) {
                    double atomicX = x;
                    line.add(
                            new Paint.Group(
                                    x,
                                    0,
                                    piece.iw,
                                    piece.ih,
                                    piece.atomic.commands.stream()
                                            .map(paint -> paint.move(atomicX, 0))
                                            .toList(),
                                    false,
                                    "none",
                                    "50% 50%"));
                } else {
                    line.add(
                            new Paint.Image(
                                    x,
                                    0,
                                    piece.iw,
                                    piece.ih,
                                    piece.image.key(),
                                    paintOpacity(s),
                                    piece.href));
                }
                x += piece.iw;
                lh = Math.max(lh, piece.ih);
                continue;
            }
            String text = transform(piece.text, s);
            Matcher tokens = Pattern.compile("\\r\\n|\\r|\\n|[^\\s]+|[\\t \\f]+").matcher(text);
            Deque<String> pendingTokens = new ArrayDeque<>();
            while (tokens.find()) {
                pendingTokens.add(tokens.group());
            }
            while (!pendingTokens.isEmpty()) {
                String token = pendingTokens.poll();
                boolean newline = token.equals("\n") || token.equals("\r") || token.equals("\r\n"),
                        space = token.isBlank();
                if (newline && (pre || ws.equals("pre-line"))) {
                    finishLine(result, line, y, lh, width, x, container);
                    y += lh;
                    ends.add(y);
                    x = 0;
                    lh = lineHeight(container);
                    first = false;
                    pendingSpace = false;
                    continue;
                }
                if (space && !pre) {
                    pendingSpace = !line.isEmpty();
                    continue;
                }
                if (pre) {
                    token = token.replace("\t", "    ");
                }
                if (wrap) {
                    List<String> segments = LineBreaks.segments(token);
                    if (segments.size() > 1) {
                        double whole =
                                textWidth(token, metrics.font(s, token), s)
                                        + (pendingSpace
                                                ? textWidth(" ", metrics.font(s, " "), s)
                                                : 0);
                        if (x + whole > width) {
                            for (int index = segments.size() - 1; index >= 0; index--) {
                                pendingTokens.addFirst(segments.get(index));
                            }
                            continue;
                        }
                    }
                }
                List<TextMetrics.FontRun> fontRuns = metrics.runs(s, token);
                font = fontRuns.isEmpty() ? metrics.font(s) : fontRuns.get(0).font();
                token =
                        fontRuns.stream()
                                .map(TextMetrics.FontRun::text)
                                .collect(java.util.stream.Collectors.joining());
                if (pendingSpace) {
                    double sw = textWidth(" ", font, s);
                    if (wrap && x + sw + textWidth(token, font, s) > width && !line.isEmpty()) {
                        finishLine(result, line, y, lh, width, x, container);
                        y += lh;
                        ends.add(y);
                        x = 0;
                        lh = lineHeight(container);
                        first = false;
                    } else if (!line.isEmpty()) {
                        line.add(textCommand(x, " ", s, font, sw, piece.href));
                        x += sw;
                    }
                    pendingSpace = false;
                }
                double tw =
                        fontRuns.stream()
                                .mapToDouble(run -> textWidth(run.text(), run.font(), s))
                                .sum();
                if (wrap && x + tw > width && !line.isEmpty()) {
                    finishLine(result, line, y, lh, width, x, container);
                    y += lh;
                    ends.add(y);
                    x = 0;
                    lh = lineHeight(container);
                    first = false;
                }
                if (wrap
                        && tw > width
                        && (s.is("word-break", "break-all")
                                || Set.of("anywhere", "break-word")
                                        .contains(s.get("overflow-wrap")))) {
                    Matcher graphemes = Pattern.compile("\\X").matcher(token);
                    while (graphemes.find()) {
                        String glyph = graphemes.group();
                        font = metrics.font(s, glyph);
                        double gw = textWidth(glyph, font, s);
                        if (x + gw > width && !line.isEmpty()) {
                            finishLine(result, line, y, lh, width, x, container);
                            y += lh;
                            ends.add(y);
                            x = 0;
                            lh = lineHeight(container);
                        }
                        line.add(textCommand(x, glyph, s, font, gw, piece.href));
                        x += gw;
                        lh = Math.max(lh, lineHeight(s));
                    }
                } else {
                    if (tw > width + .01) {
                        warn(
                                "TEXT_OVERFLOW",
                                "Unbreakable text wider than available line; use"
                                        + " overflow-wrap:anywhere");
                    }
                    for (TextMetrics.FontRun run : fontRuns) {
                        double runWidth = textWidth(run.text(), run.font(), s);
                        line.add(textCommand(x, run.text(), s, run.font(), runWidth, piece.href));
                        x += runWidth;
                    }
                    lh = Math.max(lh, lineHeight(s));
                }
            }
        }
        if (!line.isEmpty()) {
            finishLine(result, line, y, lh, width, x, container, true);
            y += lh;
            ends.add(y);
        }
        result.height = y;
        int orphans = integer(container.get("orphans", "2"), 2),
                widows = integer(container.get("widows", "2"), 2);
        if (ends.size() > 1) {
            if (orphans > 1) {
                result.avoid.add(
                        new LayoutResult.Interval(
                                0, ends.get(Math.min(ends.size(), orphans) - 1) - .001));
            }
            if (widows > 1) {
                int index = Math.max(0, ends.size() - widows);
                double start = index == 0 ? 0 : ends.get(index - 1);
                result.avoid.add(new LayoutResult.Interval(start + .001, y));
            }
        }
        return result;
    }

    private Paint.Text textCommand(
            double x, String token, ComputedStyle s, TextMetrics.Font f, double w, String link) {
        TextMetrics.Bounds ink = metrics.bounds(measurable(token), f);
        double h = lineHeight(s);
        double top = (h - f.ascent() - f.descent()) / 2 + f.ascent() - ink.ascent();
        return new Paint.Text(
                x,
                top,
                w,
                ink.ascent() + ink.descent(),
                token,
                f.key(),
                f.size(),
                s.get("color", "black"),
                len(s, "letter-spacing", 0, 0),
                len(s, "word-spacing", 0, 0),
                s.get("text-decoration"),
                link,
                paintOpacity(s),
                ink.ascent(),
                h,
                normalizeShadow(s, "text-shadow"));
    }

    private void finishLine(
            LayoutResult r,
            List<Paint> commands,
            double y,
            double h,
            double width,
            double used,
            ComputedStyle s) {
        finishLine(r, commands, y, h, width, used, s, false);
    }

    private void finishLine(
            LayoutResult r,
            List<Paint> commands,
            double y,
            double h,
            double width,
            double used,
            ComputedStyle s,
            boolean last) {
        if (used > width
                && s.is("text-overflow", "ellipsis")
                && Set.of("hidden", "clip", "auto", "scroll").contains(s.get("overflow"))) {
            TextMetrics.Font font = metrics.font(s, "\u2026");
            String marker = metrics.supportedText("\u2026", font);
            double markerWidth = textWidth(marker, font, s);
            double limit = Math.max(0, width - markerWidth);
            List<Paint> visible = new ArrayList<>();
            double end = 0;
            for (Paint command : commands) {
                if (command.x() + command.width() <= limit) {
                    visible.add(command);
                    end = Math.max(end, command.x() + command.width());
                } else if (command instanceof Paint.Text text && command.x() < limit) {
                    TextMetrics.Font original =
                            new TextMetrics.Font(
                                    text.font(),
                                    text.size(),
                                    text.ascent(),
                                    Math.max(0, text.height() - text.ascent()));
                    Matcher clusters = Pattern.compile("\\X").matcher(text.value());
                    StringBuilder prefix = new StringBuilder();
                    double measured = 0;
                    while (clusters.find()) {
                        String candidate = prefix.toString() + clusters.group();
                        double advance =
                                metrics.width(candidate, original)
                                        + text.letterSpacing()
                                                * candidate.codePointCount(0, candidate.length());
                        if (text.x() + advance > limit) {
                            break;
                        }
                        prefix.append(clusters.group());
                        measured = advance;
                    }
                    if (!prefix.isEmpty()) {
                        visible.add(text.content(prefix.toString(), measured));
                    }
                    end = text.x() + measured;
                    break;
                } else {
                    break;
                }
            }
            visible.add(textCommand(end, marker, s, font, markerWidth, ""));
            commands.clear();
            commands.addAll(visible);
            used = end + markerWidth;
        }
        boolean rtl = s.is("direction", "rtl");
        if (!commands.isEmpty()) {
            StringBuilder text = new StringBuilder();
            int[] starts = new int[commands.size()];
            for (int index = 0; index < commands.size(); index++) {
                starts[index] = text.length();
                text.append(
                        commands.get(index) instanceof Paint.Text value ? value.value() : "\uFFFC");
            }
            java.text.Bidi bidi =
                    new java.text.Bidi(
                            text.toString(),
                            rtl
                                    ? java.text.Bidi.DIRECTION_RIGHT_TO_LEFT
                                    : java.text.Bidi.DIRECTION_LEFT_TO_RIGHT);
            if (!bidi.isLeftToRight()) {
                byte[] levels = new byte[commands.size()];
                Paint[] ordered = commands.toArray(Paint[]::new);
                for (int index = 0; index < ordered.length; index++) {
                    levels[index] = (byte) bidi.getLevelAt(starts[index]);
                }
                java.text.Bidi.reorderVisually(levels, 0, ordered, 0, ordered.length);
                double position = commands.stream().mapToDouble(Paint::x).min().orElse(0);
                commands.clear();
                for (Paint command : ordered) {
                    commands.add(command.move(position - command.x(), 0));
                    position += command.width();
                }
            }
        }
        long spaces =
                commands.stream()
                        .filter(
                                command ->
                                        command instanceof Paint.Text text
                                                && text.value().equals(" "))
                        .count();
        double spacing =
                s.is("text-align", "justify") && !last && spaces > 0
                        ? Math.max(0, width - used) / spaces
                        : 0;
        double shift =
                s.is("text-align", "center")
                        ? (width - used) / 2
                        : s.is("text-align", "right")
                                        || s.is("text-align", "end") && !rtl
                                        || (s.get("text-align").isBlank()
                                                        || s.is("text-align", "start"))
                                                && rtl
                                ? width - used
                                : 0;
        double accumulated = 0;
        for (Paint p : commands) {
            if (p instanceof Paint.Text t) {
                r.commands.add(t.move(shift + accumulated, y + (h - t.lineHeight()) / 2));
                if (t.value().equals(" ")) {
                    accumulated += spacing;
                }
            } else {
                r.commands.add(p.move(shift, y + (h - p.height()) / 2));
            }
        }
        commands.clear();
        r.breaks.add(y);
        r.breaks.add(y + h);
        r.lines.add(new LayoutResult.Interval(y, y + h));
    }

    private int integer(String value, int fallback) {
        try {
            return Math.max(1, Math.min(10000, Integer.parseInt(value)));
        } catch (Exception e) {
            return fallback;
        }
    }

    private double intrinsic(HtmlNode node, boolean minimum) {
        IntrinsicMeasurements.Sizes sizes = intrinsicMeasurements.measure(node);
        return minimum ? sizes.minimum() : sizes.preferred();
    }

    private List<HtmlNode> layoutItems(HtmlNode container) {
        List<HtmlNode> cached = anonymousItems.get(container);
        if (cached != null) {
            return cached;
        }
        List<HtmlNode> items = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (HtmlNode child : container.children) {
            if (child.tag.equals("#text")) {
                text.append(child.text);
                continue;
            }
            addAnonymousItem(container, items, text);
            if (!style(child).is("display", "none")) {
                items.add(child);
            }
        }
        addAnonymousItem(container, items, text);
        List<HtmlNode> result = List.copyOf(items);
        anonymousItems.put(container, result);
        return result;
    }

    private void addAnonymousItem(HtmlNode container, List<HtmlNode> items, StringBuilder text) {
        if (!text.toString().isBlank()) {
            HtmlNode anonymous = HtmlNode.anonymousText(text.toString(), container);
            Map<String, String> inherited = new HashMap<>();
            for (String property :
                    List.of(
                            "color",
                            "font-family",
                            "font-size",
                            "font-weight",
                            "font-style",
                            "line-height",
                            "letter-spacing",
                            "word-spacing",
                            "text-align",
                            "text-transform",
                            "white-space",
                            "word-break",
                            "overflow-wrap",
                            "direction",
                            "visibility")) {
                String value = style(container).get(property);
                if (!value.isBlank()) {
                    inherited.put(property, value);
                }
            }
            inherited.put("display", "block");
            css.styles.put(anonymous, new ComputedStyle(inherited));
            items.add(anonymous);
        }
        text.setLength(0);
    }

    private List<HtmlNode> elementChildren(HtmlNode n) {
        return n.children.stream()
                .filter(c -> !c.tag.equals("#text") && !style(c).is("display", "none"))
                .toList();
    }

    private void rows(HtmlNode n, List<HtmlNode> rows) {
        for (HtmlNode c : elementChildren(n)) {
            if (style(c).is("display", "table-row")) {
                rows.add(c);
            } else if (Set.of("table-row-group", "table-header-group", "table-footer-group")
                    .contains(style(c).get("display"))) {
                rows(c, rows);
            } else if (style(c).is("display", "table-cell")) {
                rows.add(n);
                return;
            }
        }
    }

    private LayoutResult table(HtmlNode n, double width, int depth) {
        List<HtmlNode> captions =
                elementChildren(n).stream()
                        .filter(c -> style(c).is("display", "table-caption"))
                        .toList();
        if (captions.isEmpty()) {
            return tableGrid(n, width, depth);
        }
        List<HtmlNode> rows = new ArrayList<>();
        rows(n, rows);
        rows.sort(
                Comparator.comparingInt(
                        row -> {
                            String display =
                                    row.parent == null ? "" : style(row.parent).get("display");
                            return display.equals("table-header-group")
                                    ? 0
                                    : display.equals("table-footer-group") ? 2 : 1;
                        }));
        if (rows.isEmpty()) {
            return tableGrid(n, width, depth);
        }
        LayoutResult out = new LayoutResult();
        double y = 0;
        for (HtmlNode caption : captions) {
            if (!style(caption).is("caption-side", "bottom")) {
                LayoutResult c = box(caption, width, null, depth);
                out.append(c, 0, y);
                y += c.height;
            }
        }
        LayoutResult table = tableGrid(n, width, depth);
        out.append(table, 0, y);
        y += table.height;
        for (HtmlNode caption : captions) {
            if (style(caption).is("caption-side", "bottom")) {
                LayoutResult c = box(caption, width, null, depth);
                out.append(c, 0, y);
                y += c.height;
            }
        }
        out.height = y;
        out.root = table.root;
        return out;
    }

    private LayoutResult tableGrid(HtmlNode n, double width, int depth) {
        LayoutResult out = new LayoutResult();
        List<HtmlNode> rows = new ArrayList<>();
        rows(n, rows);
        rows.sort(
                Comparator.comparingInt(
                        row -> {
                            String display =
                                    row.parent == null ? "" : style(row.parent).get("display");
                            return display.equals("table-header-group")
                                    ? 0
                                    : display.equals("table-footer-group") ? 2 : 1;
                        }));
        if (rows.isEmpty()) {
            List<HtmlNode> cells =
                    elementChildren(n).stream()
                            .filter(c -> style(c).is("display", "table-cell"))
                            .toList();
            if (!cells.isEmpty()) {
                rows.add(n);
            } else {
                return flow(n, width, depth);
            }
        }
        Set<Long> occupied = new HashSet<>();
        List<Cell> cells = new ArrayList<>();
        int cols = 0;
        for (int ri = 0; ri < rows.size(); ri++) {
            int col = 0;
            for (HtmlNode cn : elementChildren(rows.get(ri))) {
                if (!style(cn).is("display", "table-cell")) {
                    continue;
                }
                while (occupied.contains(((long) ri << 32) | col)) {
                    col++;
                }
                int cs = Math.min(256, integer(cn.attr("colspan"), 1)),
                        rs = Math.min(rows.size() - ri, integer(cn.attr("rowspan"), 1));
                if (cn.attr("rowspan").equals("0")) {
                    rs = 1;
                    while (ri + rs < rows.size()
                            && rows.get(ri + rs).parent == rows.get(ri).parent) {
                        rs++;
                    }
                }
                if (col + cs > 512) {
                    throw new PdfConversionException("Table column limit exceeded");
                }
                for (int rr = ri; rr < ri + rs; rr++) {
                    for (int cc = col; cc < col + cs; cc++) {
                        occupied.add(((long) rr << 32) | cc);
                    }
                }
                cells.add(new Cell(cn, ri, col, rs, cs, null));
                col += cs;
                cols = Math.max(cols, col);
            }
        }
        if (cols == 0) {
            return out;
        }
        if (cols > 512) {
            throw new PdfConversionException("Table column limit exceeded");
        }
        double spacing =
                style(n).is("border-collapse", "collapse")
                        ? 0
                        : len(style(n), "border-spacing", width, 0);
        double available = Math.max(.1, width - spacing * (cols + 1));
        double[] widths = new double[cols], min = new double[cols];
        List<HtmlNode> columnSpecs = new ArrayList<>();
        for (HtmlNode child : elementChildren(n)) {
            if (child.tag.equals("col")) {
                for (int index = 0;
                        index < Math.min(256, integer(child.attr("span"), 1));
                        index++) {
                    columnSpecs.add(child);
                }
            }
            if (child.tag.equals("colgroup")) {
                if (elementChildren(child).isEmpty()) {
                    for (int index = 0;
                            index < Math.min(256, integer(child.attr("span"), 1));
                            index++) {
                        columnSpecs.add(child);
                    }
                } else {
                    for (HtmlNode column : elementChildren(child)) {
                        for (int index = 0;
                                index < Math.min(256, integer(column.attr("span"), 1));
                                index++) {
                            columnSpecs.add(column);
                        }
                    }
                }
            }
        }
        for (int i = 0; i < Math.min(cols, columnSpecs.size()); i++) {
            widths[i] = len(style(columnSpecs.get(i)), "width", available, 0);
        }
        if (style(n).is("table-layout", "fixed")) {
            widths = fixedTableWidths(cells, widths, available);
        } else {
            for (Cell c : cells) {
                double extras =
                        edge(style(c.node), "padding", "left", width)
                                + edge(style(c.node), "padding", "right", width)
                                + border(style(c.node), "left")
                                + border(style(c.node), "right");
                double m = intrinsic(c.node, true) + extras,
                        preferred =
                                len(
                                        style(c.node),
                                        "width",
                                        available,
                                        Math.min(intrinsic(c.node, false) + extras, available));
                for (int j = c.col; j < c.col + c.cols; j++) {
                    min[j] = Math.max(min[j], m / c.cols);
                    widths[j] = Math.max(widths[j], Math.max(min[j], preferred / c.cols));
                }
            }
            double total = Arrays.stream(widths).sum();
            if (total == 0) {
                Arrays.fill(widths, available / cols);
            } else if (total > available) {
                double minTotal = Arrays.stream(min).sum();
                if (minTotal > available) {
                    if (minTotal + spacing * (cols + 1) > initialPage.contentWidth() + .01) {
                        warn(
                                "TABLE_MIN_CONTENT_OVERFLOW",
                                "Table "
                                        + n.tag
                                        + (n.attr("id").isBlank() ? "" : "#" + n.attr("id"))
                                        + " requires "
                                        + minTotal
                                        + "pt minimum column width; "
                                        + available
                                        + "pt available; printable width "
                                        + initialPage.contentWidth()
                                        + "pt. Minimum widths preserved");
                    }
                    widths = min.clone();
                } else {
                    double slack = total - minTotal;
                    for (int i = 0; i < cols; i++) {
                        widths[i] =
                                min[i]
                                        + (widths[i] - min[i])
                                                * (available - minTotal)
                                                / Math.max(.001, slack);
                    }
                }
            } else {
                for (int i = 0; i < cols; i++) {
                    widths[i] += (available - total) / cols;
                }
            }
        }
        CollapsedBorders collapsedBorders =
                style(n).is("border-collapse", "collapse")
                        ? new CollapsedBorders(style(n).is("direction", "rtl"))
                        : null;
        if (collapsedBorders != null) {
            collapsedBorders.add(
                    0,
                    0,
                    rows.size(),
                    cols,
                    rectangle(style(n), 0, 0, 1, 1).borders(),
                    0);
            for (int row = 0; row < rows.size(); row++) {
                collapsedBorders.add(
                        row,
                        0,
                        1,
                        cols,
                        rectangle(style(rows.get(row)), 0, 0, 1, 1).borders(),
                        4);
            }
            Set<HtmlNode> groups = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            for (int row = 0; row < rows.size(); row++) {
                HtmlNode group = rows.get(row).parent;
                if (group != null
                        && group != n
                        && Set.of("table-row-group", "table-header-group", "table-footer-group")
                                .contains(style(group).get("display"))
                        && groups.add(group)) {
                    int last = row + 1;
                    while (last < rows.size() && rows.get(last).parent == group) {
                        last++;
                    }
                    collapsedBorders.add(
                            row,
                            0,
                            last - row,
                            cols,
                            rectangle(style(group), 0, 0, 1, 1).borders(),
                            3);
                }
            }
            int column = 0;
            for (HtmlNode definition : elementChildren(n)) {
                if (definition.tag.equals("colgroup")) {
                    List<HtmlNode> childColumns =
                            elementChildren(definition).stream()
                                    .filter(child -> child.tag.equals("col"))
                                    .toList();
                    int groupSpan =
                            childColumns.isEmpty()
                                    ? integer(definition.attr("span"), 1)
                                    : childColumns.stream()
                                            .mapToInt(child -> integer(child.attr("span"), 1))
                                            .sum();
                    if (column < cols) {
                        collapsedBorders.add(
                                0,
                                column,
                                rows.size(),
                                Math.min(groupSpan, cols - column),
                                rectangle(style(definition), 0, 0, 1, 1).borders(),
                                1);
                    }
                    for (HtmlNode child : childColumns) {
                        int span = Math.min(integer(child.attr("span"), 1), cols - column);
                        if (span > 0) {
                            collapsedBorders.add(
                                    0,
                                    column,
                                    rows.size(),
                                    span,
                                    rectangle(style(child), 0, 0, 1, 1).borders(),
                                    2);
                        }
                        column += Math.max(0, span);
                    }
                    if (childColumns.isEmpty()) {
                        column += groupSpan;
                    }
                } else if (definition.tag.equals("col") && column < cols) {
                    int span = Math.min(integer(definition.attr("span"), 1), cols - column);
                    collapsedBorders.add(
                            0,
                            column,
                            rows.size(),
                            span,
                            rectangle(style(definition), 0, 0, 1, 1).borders(),
                            2);
                    column += span;
                }
            }
            for (Cell c : cells) {
                collapsedBorders.add(
                        c.row,
                        c.col,
                        c.rows,
                        c.cols,
                        rectangle(style(c.node), 0, 0, 1, 1).borders(),
                        5);
            }
            for (Cell c : cells) {
                collapsedInsets.put(c.node, collapsedBorders.halves(c.row, c.col, c.rows, c.cols));
            }
        }
        double[] heights = new double[rows.size()];
        List<Cell> measured = new ArrayList<>();
        for (Cell c : cells) {
            double w = spacing * (c.cols - 1);
            for (int j = c.col; j < c.col + c.cols; j++) {
                w += widths[j];
            }
            LayoutResult l = box(c.node, w, w, depth);
            measured.add(new Cell(c.node, c.row, c.col, c.rows, c.cols, l));
            if (c.rows == 1) {
                heights[c.row] = Math.max(heights[c.row], l.height);
            }
        }
        for (Cell c : measured) {
            if (c.rows > 1) {
                double current = 0;
                for (int j = c.row; j < c.row + c.rows; j++) {
                    current += heights[j];
                }
                double deficit = c.layout.height - current - spacing * (c.rows - 1);
                if (deficit > 0) {
                    for (int j = c.row; j < c.row + c.rows; j++) {
                        heights[j] += deficit / c.rows;
                    }
                }
            }
        }
        double[] ys = new double[rows.size() + 1], xs = new double[cols + 1];
        ys[0] = spacing;
        xs[0] = spacing;
        for (int i = 0; i < rows.size(); i++) {
            ys[i + 1] = ys[i] + heights[i] + spacing;
        }
        for (int i = 0; i < cols; i++) {
            xs[i + 1] = xs[i] + widths[i] + spacing;
        }
        Set<HtmlNode> paintedGroups = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (int index = 0; index < rows.size(); index++) {
            HtmlNode group = rows.get(index).parent;
            if (group != null
                    && group != n
                    && Set.of("table-row-group", "table-header-group", "table-footer-group")
                            .contains(style(group).get("display"))
                    && paintedGroups.add(group)) {
                int last = index + 1;
                while (last < rows.size() && rows.get(last).parent == group) {
                    last++;
                }
                Paint.Rectangle background =
                        rectangle(style(group), 0, ys[index], width, ys[last] - ys[index]);
                out.commands.add(
                        new Paint.Rectangle(
                                background.x(),
                                background.y(),
                                background.width(),
                                background.height(),
                                background.fill(),
                                java.util.Collections.nCopies(
                                        4, new Paint.Border(0, "transparent", "none")),
                                background.radius(),
                                background.opacity(),
                                background.shadow()));
            }
        }
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i) != n) {
                Paint.Rectangle rowBackground =
                        rectangle(style(rows.get(i)), 0, ys[i], width, heights[i]);
                out.commands.add(
                        style(n).is("border-collapse", "collapse")
                                ? new Paint.Rectangle(
                                        rowBackground.x(),
                                        rowBackground.y(),
                                        rowBackground.width(),
                                        rowBackground.height(),
                                        rowBackground.fill(),
                                        java.util.Collections.nCopies(
                                                4, new Paint.Border(0, "transparent", "none")),
                                        rowBackground.radius(),
                                        rowBackground.opacity(),
                                        rowBackground.shadow())
                                : rowBackground);
            }
            out.breaks.add(ys[i]);
            out.breaks.add(ys[i + 1]);
            out.avoid.add(new LayoutResult.Interval(ys[i], ys[i + 1]));
        }
        int preludeCount = out.commands.size();
        List<Paint> header = new ArrayList<>(), footer = new ArrayList<>();
        double hh = 0, fh = 0;
        for (Cell c : measured) {
            double h = ys[c.row + c.rows] - ys[c.row] - spacing;
            LayoutResult l = c.layout;
            if (collapsedBorders != null) {
                for (int index = 0; index < l.commands.size(); index++) {
                    Paint command = l.commands.get(index);
                    if (command instanceof Paint.Rectangle rectangle && index == 0) {
                        l.commands.set(
                                index,
                                new Paint.Rectangle(
                                        rectangle.x(),
                                        rectangle.y(),
                                        rectangle.width(),
                                        rectangle.height(),
                                        rectangle.fill(),
                                        java.util.Collections.nCopies(
                                                4, new Paint.Border(0, "transparent", "none")),
                                        rectangle.radius(),
                                        rectangle.opacity(),
                                        rectangle.shadow()));
                    }
                }
            }
            if (!l.commands.isEmpty() && l.commands.get(0) instanceof Paint.Rectangle bg) {
                l.commands.set(
                        0,
                        new Paint.Rectangle(
                                bg.x(),
                                bg.y(),
                                bg.width(),
                                h,
                                bg.fill(),
                                bg.borders(),
                                bg.radius(),
                                bg.opacity(),
                                bg.shadow()));
            }
            double shift =
                    style(c.node).is("vertical-align", "middle")
                            ? (h - l.root.naturalHeight) / 2
                            : style(c.node).is("vertical-align", "bottom")
                                    ? h - l.root.naturalHeight
                                    : 0;
            if (shift > 0) {
                for (int i = 1; i < l.commands.size(); i++) {
                    l.commands.set(i, l.commands.get(i).move(0, shift));
                }
                List<Double> moved = l.breaks.stream().map(v -> v + shift).toList();
                l.breaks.clear();
                l.breaks.addAll(moved);
                for (int i = 0; i < l.lines.size(); i++) {
                    dtm.pdfloom.layout.LayoutResult.Interval line = l.lines.get(i);
                    l.lines.set(
                            i, new LayoutResult.Interval(line.start() + shift, line.end() + shift));
                }
            }
            int from = out.commands.size();
            out.append(l, xs[c.col], ys[c.row]);
            HtmlNode row = rows.get(c.row);
            String group = row.parent == null ? "" : style(row.parent).get("display");
            if (group.equals("table-header-group")) {
                header.addAll(out.commands.subList(from, out.commands.size()));
                hh = Math.max(hh, ys[c.row + c.rows]);
            } else if (group.equals("table-footer-group")) {
                footer.addAll(out.commands.subList(from, out.commands.size()));
                fh += c.rows == 1 && c.col == 0 ? heights[c.row] + spacing : 0;
            }
            if (c.rows > 1) {
                out.avoid.add(new LayoutResult.Interval(ys[c.row], ys[c.row + c.rows]));
            }
        }

        if (hh > 0) {
            List<Paint> background = new ArrayList<>();
            for (int index = 0; index < preludeCount; index++) {
                Paint command = out.commands.get(index);
                if (command.y() >= -.001 && command.y() + command.height() <= hh + .001) {
                    background.add(command);
                }
            }
            header.addAll(0, background);
        }
        if (fh > 0) {
            List<Paint> background = new ArrayList<>();
            for (int index = 0; index < preludeCount; index++) {
                Paint command = out.commands.get(index);
                if (command.y() >= ys[rows.size()] - fh - .001) {
                    background.add(command);
                }
            }
            footer.addAll(0, background);
        }
        if (collapsedBorders != null) {
            List<Paint> borders = collapsedBorders.paint(xs, ys);
            out.commands.addAll(borders);
            for (Paint command : borders) {
                Paint.BorderLine line = (Paint.BorderLine) command;
                if (hh > 0
                        && Math.min(line.y1(), line.y2()) >= -.001
                        && Math.max(line.y1(), line.y2()) <= hh + .001) {
                    header.add(command);
                }
                if (fh > 0 && Math.min(line.y1(), line.y2()) >= ys[rows.size()] - fh - .001) {
                    footer.add(command);
                }
            }
        }
        removeCrossingBreaks(out);
        out.height = ys[rows.size()];
        out.root = new LayoutBox(n, style(n));
        out.root.width = xs[cols] + spacing;
        if (!header.isEmpty() || !footer.isEmpty()) {
            double footerStart = ys[rows.size()] - fh;
            out.tables.add(
                    new LayoutResult.TableRepeat(
                            0,
                            out.height,
                            hh,
                            List.copyOf(header),
                            fh,
                            footer.stream().map(p -> p.move(0, -footerStart)).toList()));
        }
        return out;
    }

    private double[] fixedTableWidths(List<Cell> cells, double[] initial, double available) {
        double[] widths = initial.clone();
        boolean[] assigned = new boolean[widths.length];
        for (int column = 0; column < widths.length; column++) {
            assigned[column] = widths[column] > 0;
        }
        for (Cell cell : cells) {
            if (cell.row != 0 || style(cell.node).get("width").isBlank()) {
                continue;
            }
            ComputedStyle style = style(cell.node);
            double hint = len(style, "width", available, Double.NaN);
            if (Double.isNaN(hint)) {
                continue;
            }
            if (!style.is("box-sizing", "border-box")) {
                hint +=
                        edge(style, "padding", "left", available)
                                + edge(style, "padding", "right", available)
                                + border(style, "left")
                                + border(style, "right");
            }
            int missing = 0;
            for (int column = cell.col; column < cell.col + cell.cols; column++) {
                if (assigned[column]) {
                    hint -= widths[column];
                } else {
                    missing++;
                }
            }
            if (missing > 0) {
                for (int column = cell.col; column < cell.col + cell.cols; column++) {
                    if (!assigned[column]) {
                        widths[column] = Math.max(0, hint) / missing;
                        assigned[column] = true;
                    }
                }
            }
        }
        int missing = 0;
        for (boolean value : assigned) {
            if (!value) {
                missing++;
            }
        }
        double remaining = available - Arrays.stream(widths).sum();
        if (remaining < -.01) {
            warn("TABLE_FIXED_WIDTH_OVERFLOW", "Explicit column widths exceed the table width");
        }
        for (int column = 0; column < widths.length; column++) {
            if (!assigned[column]) {
                widths[column] = Math.max(.1, remaining / Math.max(1, missing));
            } else if (missing == 0 && remaining > 0) {
                widths[column] += remaining / widths.length;
            }
        }
        return widths;
    }

    private void removeCrossingBreaks(LayoutResult out) {
        List<LayoutResult.Interval> lines = new ArrayList<>(out.lines);
        lines.sort(Comparator.comparingDouble(LayoutResult.Interval::start));
        Iterator<Double> iterator = out.breaks.iterator();
        int index = 0;
        double furthest = Double.NEGATIVE_INFINITY;
        while (iterator.hasNext()) {
            double y = iterator.next();
            while (index < lines.size() && lines.get(index).start() < y - .001) {
                furthest = Math.max(furthest, lines.get(index++).end());
            }
            if (y < furthest - .001) {
                iterator.remove();
            }
        }
    }

    private LayoutResult columns(HtmlNode node, double width, int depth) {
        ComputedStyle style = style(node);
        double gap = len(style, "column-gap", width, em(style));
        int count = integer(style.get("column-count"), 1);
        double preferredWidth = len(style, "column-width", width, Double.NaN);
        if (Double.isFinite(preferredWidth)) {
            int fitting = Math.max(1, (int) Math.floor((width + gap) / (preferredWidth + gap)));
            count =
                    style.get("column-count").isBlank() || style.is("column-count", "auto")
                            ? fitting
                            : Math.min(count, fitting);
        }
        if (count > 256) {
            throw new PdfConversionException("Column count limit exceeded");
        }
        double columnWidth = Math.max(.1, (width - gap * (count - 1)) / count);
        List<List<HtmlNode>> sections = new ArrayList<>();
        List<HtmlNode> pending = new ArrayList<>();
        LayoutResult result = new LayoutResult();
        double y = 0;
        for (HtmlNode child : node.children) {
            if (!child.tag.equals("#text") && style(child).is("column-span", "all")) {
                if (!pending.isEmpty()) {
                    sections.add(pending);
                    pending = new ArrayList<>();
                }
                sections.add(List.of(child));
            } else {
                pending.add(child);
            }
        }
        if (!pending.isEmpty()) {
            sections.add(pending);
        }
        for (List<HtmlNode> section : sections) {
            if (section.size() == 1
                    && !section.get(0).tag.equals("#text")
                    && style(section.get(0)).is("column-span", "all")) {
                LayoutResult full = box(section.get(0), width, null, depth + 1);
                result.append(full, 0, y);
                y += full.height;
                continue;
            }
            HtmlNode group = HtmlNode.anonymousBox(section, node);
            Map<String, String> values = new HashMap<>(style.values());
            values.put("column-count", "1");
            values.put("column-width", "auto");
            css.styles.put(group, new ComputedStyle(values));
            LayoutResult content = flow(group, columnWidth, depth + 1);
            double explicitHeight = usedHeight(node);
            double cap =
                    Double.isFinite(explicitHeight) && explicitHeight > 0
                            ? explicitHeight
                            : pageContentHeight;
            double height =
                    style.is("column-fill", "auto")
                            ? cap
                            : Math.min(cap, Math.max(lineHeight(style), content.height / count));
            List<dtm.pdfloom.page.RenderPage> fragments = List.of();
            for (int attempt = 0; attempt < 32; attempt++) {
                dtm.pdfloom.page.PageSpec spec =
                        new dtm.pdfloom.page.PageSpec(
                                columnWidth, Math.max(.1, height), 0, 0, 0, 0, Map.of());
                fragments =
                        new dtm.pdfloom.pagination.FragmentPaginator(config, css, spec)
                                .paginate(content);
                if (height >= cap - .001 || fragments.size() <= count) {
                    break;
                }
                height = Math.min(cap, height + lineHeight(style));
            }
            double rowHeight = height;
            for (int index = 0; index < fragments.size(); index++) {
                dtm.pdfloom.page.RenderPage fragment = fragments.get(index);
                int column = index % count;
                int row = index / count;
                double x = column * (columnWidth + gap);
                double top = y + row * rowHeight;
                List<Paint> commands =
                        fragment.commands().stream().map(command -> command.move(x, top)).toList();
                result.commands.add(
                        new Paint.Group(
                                x, top, columnWidth, height, commands, true, "none", "50% 50%"));
                for (LayoutResult.Interval line : content.lines) {
                    if (line.start() >= fragment.flowStart() - .001
                            && line.end() <= fragment.flowEnd() + .001) {
                        result.lines.add(
                                new LayoutResult.Interval(
                                        top + line.start() - fragment.flowStart(),
                                        top + line.end() - fragment.flowStart()));
                    }
                }
                for (LayoutResult.NamedString assignment : content.strings) {
                    if (assignment.at() >= fragment.flowStart()
                            && assignment.at() < fragment.flowEnd()) {
                        result.strings.add(
                                new LayoutResult.NamedString(
                                        assignment.name(),
                                        top + assignment.at() - fragment.flowStart(),
                                        assignment.value()));
                    }
                }
                for (LayoutResult.Running assignment : content.running) {
                    if (assignment.at() >= fragment.flowStart()
                            && assignment.at() < fragment.flowEnd()) {
                        result.running.add(
                                new LayoutResult.Running(
                                        assignment.name(),
                                        top + assignment.at() - fragment.flowStart(),
                                        assignment.height(),
                                        assignment.commands(),
                                        assignment.source()));
                    }
                }
            }
            double rule = len(style, "column-rule-width", gap, 0);
            if (rule > 0
                    && !style.is("column-rule-style", "none")
                    && !style.get("column-rule-style").isBlank()) {
                List<Paint.Border> borders =
                        java.util.Collections.nCopies(
                                4, new Paint.Border(0, "transparent", "none"));
                int rows = (fragments.size() + count - 1) / count;
                for (int row = 0; row < rows; row++) {
                    for (int column = 1; column < count; column++) {
                        result.commands.add(
                                new Paint.Rectangle(
                                        column * columnWidth + (column - .5) * gap - rule / 2,
                                        y + row * rowHeight,
                                        rule,
                                        height,
                                        style.get("column-rule-color", style.get("color", "black")),
                                        borders,
                                        0,
                                        1));
                    }
                }
            }
            int rows = Math.max(1, (fragments.size() + count - 1) / count);
            for (int row = 0; row <= rows; row++) {
                result.breaks.add(y + row * rowHeight);
            }
            y += rows * rowHeight;
        }
        result.height = y;
        removeCrossingBreaks(result);
        return result;
    }

    private LayoutResult flex(HtmlNode n, double width, int depth) {
        ComputedStyle s = style(n);
        List<HtmlNode> children = new ArrayList<>(layoutItems(n));
        children.sort(
                Comparator.comparingInt(
                        c -> {
                            try {
                                return Integer.parseInt(style(c).get("order", "0"));
                            } catch (Exception e) {
                                return 0;
                            }
                        }));
        if (s.get("flex-direction").startsWith("column")) {
            if (!s.get("flex-wrap", "nowrap").equals("nowrap") && Double.isFinite(usedHeight(n))) {
                return wrappedFlexColumns(n, children, width, depth);
            }
            if (s.is("flex-direction", "column-reverse")) {
                Collections.reverse(children);
            }
            LayoutResult r = new LayoutResult();
            double gap = len(s, "row-gap", width, 0), y = 0;
            double target = usedHeight(n);
            if (!Double.isNaN(target) && s.is("box-sizing", "border-box")) {
                target -=
                        edge(s, "padding", "top", width)
                                + edge(s, "padding", "bottom", width)
                                + border(s, "top")
                                + border(s, "bottom");
            }
            List<LayoutResult> layouts = new ArrayList<>();
            double[] sizes = new double[children.size()];
            double sum = 0, grow = 0, shrink = 0;
            for (int i = 0; i < children.size(); i++) {
                HtmlNode child = children.get(i);
                ComputedStyle cs = style(child);
                String align = cs.get("align-self", s.get("align-items", "stretch"));
                Double childWidth = align.equals("stretch") ? width : null;
                LayoutResult c = box(child, width, childWidth, depth);
                layouts.add(c);
                sizes[i] =
                        CssValues.length(
                                cs.get("flex-basis"),
                                Double.isNaN(target) ? 0 : target,
                                em(cs),
                                css.rootFontSize,
                                config.pageWidth,
                                config.pageHeight,
                                c.height);
                sizes[i] = Math.max(0, sizes[i]);
                sum += sizes[i];
                grow += factor(cs, "flex-grow", 0);
                shrink += factor(cs, "flex-shrink", 1) * sizes[i];
            }
            double[] minimums = new double[sizes.length];
            double[] maximums = new double[sizes.length];
            double[] growth = new double[sizes.length];
            double[] shrinkage = new double[sizes.length];
            for (int index = 0; index < sizes.length; index++) {
                ComputedStyle childStyle = style(children.get(index));
                minimums[index] =
                        len(
                                childStyle,
                                "min-height",
                                Double.isNaN(target) ? 0 : target,
                                layouts.get(index).height);
                maximums[index] =
                        len(
                                childStyle,
                                "max-height",
                                Double.isNaN(target) ? 0 : target,
                                Double.POSITIVE_INFINITY);
                growth[index] = factor(childStyle, "flex-grow", 0);
                shrinkage[index] = factor(childStyle, "flex-shrink", 1) * sizes[index];
            }
            distributeFlex(
                    sizes,
                    minimums,
                    maximums,
                    growth,
                    shrinkage,
                    Double.isNaN(target)
                            ? sum
                            : Math.max(0, target) - gap * Math.max(0, sizes.length - 1));
            double remaining =
                    Double.isNaN(target)
                            ? 0
                            : Math.max(
                                    0,
                                    target
                                            - Arrays.stream(sizes).sum()
                                            - gap * Math.max(0, children.size() - 1));
            if (s.is("justify-content", "center")) {
                y = remaining / 2;
            } else if (Set.of("end", "flex-end").contains(s.get("justify-content"))) {
                y = remaining;
            }
            if (s.is("justify-content", "space-between") && children.size() > 1) {
                gap += remaining / (children.size() - 1);
            }
            if (s.is("justify-content", "space-around") && !children.isEmpty()) {
                gap += remaining / children.size();
                y = remaining / children.size() / 2;
            }
            if (s.is("justify-content", "space-evenly") && !children.isEmpty()) {
                gap += remaining / (children.size() + 1);
                y = remaining / (children.size() + 1);
            }
            for (int i = 0; i < children.size(); i++) {
                LayoutResult c = layouts.get(i);
                stretchHeight(c, sizes[i]);
                String align =
                        style(children.get(i)).get("align-self", s.get("align-items", "stretch"));
                double x =
                        align.equals("center")
                                ? (width - c.root.width) / 2
                                : Set.of("end", "flex-end").contains(align)
                                        ? width - c.root.width
                                        : 0;
                r.append(c, x, y);
                y += sizes[i] + gap;
            }
            r.height = Math.max(Double.isNaN(target) ? 0 : target, Math.max(0, y - gap));
            return r;
        }
        if (s.is("flex-direction", "row-reverse")) {
            Collections.reverse(children);
        }
        double gap = len(s, "column-gap", width, 0);
        List<List<HtmlNode>> rows = new ArrayList<>();
        List<HtmlNode> row = new ArrayList<>();
        double used = 0;
        for (HtmlNode child : children) {
            double basis = basis(child, width);
            if (!row.isEmpty()
                    && !s.get("flex-wrap", "nowrap").equals("nowrap")
                    && used + gap + basis > width) {
                rows.add(row);
                row = new ArrayList<>();
                used = 0;
            }
            row.add(child);
            used += basis + (row.size() > 1 ? gap : 0);
        }
        if (!row.isEmpty()) {
            rows.add(row);
        }
        record FlexLine(
                List<HtmlNode> items,
                double[] widths,
                List<LayoutResult> layouts,
                double height,
                double x,
                double gap) {
                }
        List<FlexLine> flexLines = new ArrayList<>();
        for (List<HtmlNode> items : rows) {
            double[] widths = new double[items.size()];
            double sum = 0, grow = 0, shrink = 0;
            for (int i = 0; i < items.size(); i++) {
                ComputedStyle cs = style(items.get(i));
                widths[i] = basis(items.get(i), width);
                sum += widths[i];
                grow += factor(cs, "flex-grow", 0);
                shrink += factor(cs, "flex-shrink", 1) * widths[i];
            }
            double target = width - gap * Math.max(0, items.size() - 1);
            double[] minimums = new double[items.size()];
            double[] maximums = new double[items.size()];
            double[] growth = new double[items.size()];
            double[] shrinkage = new double[items.size()];
            for (int i = 0; i < items.size(); i++) {
                ComputedStyle cs = style(items.get(i));
                double extras = flexExtras(cs, width);
                minimums[i] =
                        len(
                                        cs,
                                        "min-width",
                                        width,
                                        cs.is("overflow", "hidden")
                                                ? 0
                                                : Math.max(
                                                        0,
                                                        Math.min(
                                                                        widths[i],
                                                                        intrinsic(
                                                                                        items.get(
                                                                                                i),
                                                                                        true)
                                                                                + extras)
                                                                - extras))
                                + extras;
                maximums[i] = len(cs, "max-width", width, Double.POSITIVE_INFINITY) + extras;
                growth[i] = factor(cs, "flex-grow", 0);
                shrinkage[i] = factor(cs, "flex-shrink", 1) * widths[i];
            }
            distributeFlex(widths, minimums, maximums, growth, shrinkage, target);
            List<LayoutResult> layouts = new ArrayList<>();
            double h = 0;
            for (int i = 0; i < items.size(); i++) {
                ComputedStyle childStyle = style(items.get(i));
                double margins =
                        edge(childStyle, "margin", "left", width)
                                + edge(childStyle, "margin", "right", width);
                LayoutResult l = box(items.get(i), width, Math.max(.1, widths[i] - margins), depth);
                layouts.add(l);
                h = Math.max(h, l.height);
            }
            double baseline = 0;
            double descent = 0;
            for (int index = 0; index < layouts.size(); index++) {
                String align =
                        style(items.get(index)).get("align-self", s.get("align-items", "stretch"));
                if (align.contains("baseline")) {
                    baseline = Math.max(baseline, firstBaseline(layouts.get(index)));
                    descent =
                            Math.max(
                                    descent,
                                    layouts.get(index).height - firstBaseline(layouts.get(index)));
                }
            }
            h = Math.max(h, baseline + descent);
            double occupied = Arrays.stream(widths).sum() + gap * Math.max(0, items.size() - 1),
                    remaining = Math.max(0, width - occupied),
                    x =
                            s.is("justify-content", "center")
                                    ? remaining / 2
                                    : Set.of("end", "flex-end").contains(s.get("justify-content"))
                                            ? remaining
                                            : 0;
            double actualGap = gap;
            if (s.is("justify-content", "space-between") && items.size() > 1) {
                actualGap += remaining / (items.size() - 1);
            }
            if (s.is("justify-content", "space-around") && items.size() > 0) {
                actualGap += remaining / items.size();
                x = remaining / items.size() / 2;
            }
            if (s.is("justify-content", "space-evenly") && !items.isEmpty()) {
                actualGap += remaining / (items.size() + 1);
                x = remaining / (items.size() + 1);
            }
            flexLines.add(new FlexLine(items, widths, layouts, h, x, actualGap));
        }
        if (s.is("flex-wrap", "wrap-reverse")) {
            Collections.reverse(flexLines);
        }
        double rowGap = len(s, "row-gap", width, 0);
        double natural =
                flexLines.stream().mapToDouble(FlexLine::height).sum()
                        + rowGap * Math.max(0, flexLines.size() - 1);
        double targetHeight = usedHeight(n);
        if (Double.isNaN(targetHeight)) {
            targetHeight = natural;
        }
        if (s.is("box-sizing", "border-box")) {
            targetHeight -=
                    edge(s, "padding", "top", width)
                            + edge(s, "padding", "bottom", width)
                            + border(s, "top")
                            + border(s, "bottom");
        }
        double crossFree = Math.max(0, targetHeight - natural);
        String crossAlign = s.get("align-content", "stretch");
        double lineExtra =
                crossAlign.equals("stretch") && !flexLines.isEmpty()
                        ? crossFree / flexLines.size()
                        : 0;
        double y =
                crossAlign.equals("center")
                        ? crossFree / 2
                        : Set.of("end", "flex-end").contains(crossAlign) ? crossFree : 0;
        if (crossAlign.equals("space-between") && flexLines.size() > 1) {
            rowGap += crossFree / (flexLines.size() - 1);
        }
        if (crossAlign.equals("space-around") && !flexLines.isEmpty()) {
            rowGap += crossFree / flexLines.size();
            y = crossFree / flexLines.size() / 2;
        }
        if (crossAlign.equals("space-evenly") && !flexLines.isEmpty()) {
            rowGap += crossFree / (flexLines.size() + 1);
            y = crossFree / (flexLines.size() + 1);
        }
        LayoutResult out = new LayoutResult();
        for (FlexLine line : flexLines) {
            double x = line.x();
            double h = line.height() + lineExtra;
            double baseline = 0;
            int autoMargins = 0;
            for (int index = 0; index < line.items().size(); index++) {
                ComputedStyle itemStyle = style(line.items().get(index));
                if (itemStyle
                        .get("align-self", s.get("align-items", "stretch"))
                        .contains("baseline")) {
                    baseline = Math.max(baseline, firstBaseline(line.layouts().get(index)));
                }
                autoMargins += itemStyle.is("margin-left", "auto") ? 1 : 0;
                autoMargins += itemStyle.is("margin-right", "auto") ? 1 : 0;
            }
            double autoShare =
                    autoMargins == 0
                            ? 0
                            : Math.max(
                                            0,
                                            width
                                                    - Arrays.stream(line.widths()).sum()
                                                    - gap * Math.max(0, line.items().size() - 1))
                                    / autoMargins;
            if (autoMargins > 0) {
                x = 0;
            }
            for (int index = 0; index < line.layouts().size(); index++) {
                LayoutResult item = line.layouts().get(index);
                ComputedStyle childStyle = style(line.items().get(index));
                String align = childStyle.get("align-self", s.get("align-items", "stretch"));
                if (align.equals("stretch") && childStyle.get("height").isBlank()) {
                    stretchHeight(item, h);
                }
                double dy =
                        align.equals("center")
                                ? (h - item.height) / 2
                                : Set.of("end", "flex-end").contains(align) ? h - item.height : 0;
                if (align.contains("baseline")) {
                    dy = baseline - firstBaseline(item);
                }
                if (childStyle.is("margin-left", "auto")) {
                    x += autoShare;
                }
                out.append(item, x, y + dy);
                x += line.widths()[index] + (autoMargins > 0 ? gap : line.gap());
                if (childStyle.is("margin-right", "auto")) {
                    x += autoShare;
                }
            }
            out.breaks.add(y);
            out.breaks.add(y + h);
            y += h + rowGap;
        }
        out.height = Math.max(natural, targetHeight);
        return out;
    }

    private LayoutResult wrappedFlexColumns(
            HtmlNode node, List<HtmlNode> children, double width, int depth) {
        ComputedStyle style = style(node);
        double target = usedHeight(node);
        if (style.is("box-sizing", "border-box")) {
            target -=
                    edge(style, "padding", "top", width)
                            + edge(style, "padding", "bottom", width)
                            + border(style, "top")
                            + border(style, "bottom");
        }
        double rowGap = len(style, "row-gap", width, 0);
        double columnGap = len(style, "column-gap", width, 0);
        List<List<HtmlNode>> columns = new ArrayList<>();
        List<Double> columnWidths = new ArrayList<>();
        List<HtmlNode> column = new ArrayList<>();
        double occupied = 0;
        double cross = 0;
        for (HtmlNode child : children) {
            ComputedStyle childStyle = style(child);
            double naturalWidth =
                    Math.min(width, intrinsic(child, false) + flexExtras(childStyle, width));
            LayoutResult measured =
                    box(
                            child,
                            width,
                            childStyle.get("width").isBlank() ? Math.max(.1, naturalWidth) : null,
                            depth);
            double basis =
                    CssValues.length(
                            childStyle.get("flex-basis"),
                            target,
                            em(childStyle),
                            css.rootFontSize,
                            config.pageWidth,
                            config.pageHeight,
                            measured.height);
            double minimum = len(childStyle, "min-height", target, measured.height);
            double maximum = len(childStyle, "max-height", target, Double.POSITIVE_INFINITY);
            double outer = Math.max(minimum, Math.min(maximum, basis));
            if (!column.isEmpty() && occupied + rowGap + outer > target) {
                columns.add(column);
                columnWidths.add(cross);
                column = new ArrayList<>();
                occupied = 0;
                cross = 0;
            }
            occupied += outer + (column.isEmpty() ? 0 : rowGap);
            column.add(child);
            cross =
                    Math.max(
                            cross,
                            measured.root.width
                                    + edge(childStyle, "margin", "left", width)
                                    + edge(childStyle, "margin", "right", width));
        }
        if (!column.isEmpty()) {
            columns.add(column);
            columnWidths.add(cross);
        }
        if (style.is("flex-wrap", "wrap-reverse")) {
            Collections.reverse(columns);
            Collections.reverse(columnWidths);
        }
        double occupiedWidth =
                columnWidths.stream().mapToDouble(Double::doubleValue).sum()
                        + columnGap * Math.max(0, columns.size() - 1);
        double free = Math.max(0, width - occupiedWidth);
        String align = style.get("align-content", "stretch");
        double extra = align.equals("stretch") && !columns.isEmpty() ? free / columns.size() : 0;
        double x =
                align.equals("center")
                        ? free / 2
                        : Set.of("end", "flex-end").contains(align) ? free : 0;
        if (align.equals("space-between") && columns.size() > 1) {
            columnGap += free / (columns.size() - 1);
        } else if (align.equals("space-around") && !columns.isEmpty()) {
            columnGap += free / columns.size();
            x = free / columns.size() / 2;
        } else if (align.equals("space-evenly") && !columns.isEmpty()) {
            columnGap += free / (columns.size() + 1);
            x = free / (columns.size() + 1);
        }
        LayoutResult result = new LayoutResult();
        for (int index = 0; index < columns.size(); index++) {
            HtmlNode group = HtmlNode.anonymousBox(columns.get(index), node.parent);
            Map<String, String> values = new HashMap<>(style.values());
            values.put("flex-wrap", "nowrap");
            values.put("height", target + "pt");
            values.put("box-sizing", "content-box");
            css.styles.put(group, new ComputedStyle(values));
            LayoutResult laidOut = flex(group, columnWidths.get(index) + extra, depth + 1);
            result.append(laidOut, x, 0);
            x += columnWidths.get(index) + extra + columnGap;
        }
        result.height = target;
        return result;
    }

    private double firstBaseline(LayoutResult result) {
        for (Paint command : result.commands) {
            Double baseline = firstBaseline(command);
            if (baseline != null) {
                return baseline;
            }
        }
        return result.height;
    }

    private Double firstBaseline(Paint command) {
        if (command instanceof Paint.Text text) {
            return text.y() + text.ascent();
        }
        List<Paint> children =
                command instanceof Paint.Group group
                        ? group.commands()
                        : command instanceof Paint.Layer layer ? layer.commands() : List.of();
        for (Paint child : children) {
            Double baseline = firstBaseline(child);
            if (baseline != null) {
                return baseline;
            }
        }
        return null;
    }

    private void stretchHeight(LayoutResult result, double height) {
        if (height <= result.height) {
            return;
        }
        double extra = height - result.height;
        if (!result.commands.isEmpty()) {
            result.commands.set(0, stretchBackground(result.commands.get(0), extra));
        }
        result.height = height;
        if (result.root != null) {
            result.root.height += extra;
        }
        result.breaks.add(height);
    }

    private Paint stretchBackground(Paint command, double extra) {
        if (command instanceof Paint.Rectangle rectangle) {
            return new Paint.Rectangle(
                    rectangle.x(),
                    rectangle.y(),
                    rectangle.width(),
                    rectangle.height() + extra,
                    rectangle.fill(),
                    rectangle.borders(),
                    rectangle.radius(),
                    rectangle.opacity(),
                    rectangle.shadow());
        }
        if (command instanceof Paint.Layer layer) {
            List<Paint> commands = new ArrayList<>(layer.commands());
            if (!commands.isEmpty()) {
                commands.set(0, stretchBackground(commands.get(0), extra));
            }
            return new Paint.Layer(
                    layer.x(),
                    layer.y(),
                    layer.width(),
                    layer.height() + extra,
                    commands,
                    layer.order(),
                    layer.context(),
                    layer.opacity());
        }
        if (command instanceof Paint.Group group) {
            List<Paint> commands = new ArrayList<>(group.commands());
            if (!commands.isEmpty()) {
                commands.set(0, stretchBackground(commands.get(0), extra));
            }
            return new Paint.Group(
                    group.x(),
                    group.y(),
                    group.width(),
                    group.height() + extra,
                    commands,
                    group.clip(),
                    group.transform(),
                    group.transformOrigin());
        }
        return command;
    }

    private void distributeFlex(
            double[] sizes,
            double[] minimums,
            double[] maximums,
            double[] growth,
            double[] shrinkage,
            double target) {
        double initialFree = target - Arrays.stream(sizes).sum();
        double[] factors = initialFree >= 0 ? growth : shrinkage;
        double[] bases = sizes.clone();
        boolean[] frozen = new boolean[sizes.length];
        for (int pass = 0; pass <= sizes.length; pass++) {
            double free = target;
            double total = 0;
            double factorSum = 0;
            for (int i = 0; i < sizes.length; i++) {
                free -= frozen[i] ? sizes[i] : bases[i];
                if (!frozen[i]) {
                    total += factors[i];
                    factorSum +=
                            initialFree >= 0
                                    ? growth[i]
                                    : bases[i] > 0 ? shrinkage[i] / bases[i] : 0;
                }
            }
            if (factorSum < 1 && Math.abs(initialFree * factorSum) < Math.abs(free)) {
                free = initialFree * factorSum;
            }
            boolean violation = false;
            for (int i = 0; i < sizes.length; i++) {
                if (frozen[i]) {
                    continue;
                }
                double proposed = bases[i] + (total > 0 ? free * factors[i] / total : 0);
                sizes[i] = Math.max(minimums[i], Math.min(maximums[i], proposed));
                if (Math.abs(sizes[i] - proposed) > .001 || factors[i] == 0) {
                    frozen[i] = true;
                    violation = true;
                }
            }
            if (!violation) {
                return;
            }
        }
    }

    private double factor(ComputedStyle s, String key, double fallback) {
        try {
            return Math.max(0, Double.parseDouble(s.get(key)));
        } catch (Exception e) {
            String flex = s.get("flex");
            List<String> parts = CssValues.tokens(flex);
            if (flex.equals("none")) {
                return 0;
            }
            if (!parts.isEmpty() && key.equals("flex-grow")) {
                try {
                    return Double.parseDouble(parts.get(0));
                } catch (Exception ignored) {
                }
            }
            if (parts.size() > 1 && key.equals("flex-shrink")) {
                try {
                    return Double.parseDouble(parts.get(1));
                } catch (Exception ignored) {
                }
            }
            return fallback;
        }
    }

    private double basis(HtmlNode n, double width) {
        ComputedStyle s = style(n);
        String value = s.get("flex-basis");
        List<String> parts = CssValues.tokens(s.get("flex"));
        if (value.isEmpty() && parts.size() > 2) {
            value = parts.get(2);
        }
        if (value.isEmpty() && parts.size() == 1 && parts.get(0).matches("[0-9.]+")) {
            value = "0";
        }
        return flexExtras(s, width)
                + Math.max(
                        .1,
                        CssValues.length(
                                value,
                                width,
                                em(s),
                                css.rootFontSize,
                                config.pageWidth,
                                config.pageHeight,
                                len(s, "width", width, Math.min(width, intrinsic(n, false)))));
    }

    private double flexExtras(ComputedStyle style, double width) {
        double margins =
                edge(style, "margin", "left", width) + edge(style, "margin", "right", width);
        return margins
                + (style.is("box-sizing", "border-box")
                        ? 0
                        : edge(style, "padding", "left", width)
                                + edge(style, "padding", "right", width)
                                + border(style, "left")
                                + border(style, "right"));
    }

    private LayoutResult grid(HtmlNode n, double width, int depth) {
        ComputedStyle s = style(n);
        List<HtmlNode> children = layoutItems(n);
        String template = s.get("grid-template-columns", "1fr");
        double gap = len(s, "column-gap", width, 0);
        if (template.startsWith("subgrid") && s.get("column-gap").isBlank()) {
            gap = subgridColumnGaps.getOrDefault(n, gap);
        }
        GridDefinition.Axis columnDefinition =
                template.startsWith("subgrid")
                        ? subgridColumns.getOrDefault(
                                n,
                                new GridDefinition.Axis(new ArrayList<>(), new LinkedHashMap<>()))
                        : GridDefinition.parse(
                                template,
                                width,
                                gap,
                                value ->
                                        CssValues.length(
                                                value,
                                                width,
                                                em(s),
                                                css.rootFontSize,
                                                config.pageWidth,
                                                config.pageHeight,
                                                0));
        GridDefinition.Axis rowDefinition =
                s.get("grid-template-rows").startsWith("subgrid")
                        ? subgridRows.getOrDefault(
                                n,
                                new GridDefinition.Axis(new ArrayList<>(), new LinkedHashMap<>()))
                        : GridDefinition.parse(
                                s.get("grid-template-rows"),
                                config.pageHeight,
                                len(s, "row-gap", width, 0),
                                value ->
                                        CssValues.length(
                                                value,
                                                config.pageHeight,
                                                em(s),
                                                css.rootFontSize,
                                                config.pageWidth,
                                                config.pageHeight,
                                                0));
        Map<String, GridDefinition.Area> areas = GridDefinition.areas(s.get("grid-template-areas"));
        GridDefinition.addAreas(columnDefinition, rowDefinition, areas);
        List<String> specs = new ArrayList<>(columnDefinition.tracks());
        if (specs.isEmpty() && areas.isEmpty()) {
            specs.add("1fr");
        }
        int cols = Math.max(1, specs.size());
        int rowCount = Math.max(1, rowDefinition.tracks().size());
        for (GridDefinition.Area area : areas.values()) {
            cols = Math.max(cols, area.column() + area.columns());
            rowCount = Math.max(rowCount, area.row() + area.rows());
        }
        for (HtmlNode child : children) {
            Axis axis = gridAxis(style(child), "column", columnDefinition);
            if (axis.start >= 0) {
                cols = Math.max(cols, axis.start + axis.span);
            }
            Axis rows = gridAxis(style(child), "row", rowDefinition);
            if (rows.start >= 0) {
                rowCount = Math.max(rowCount, rows.start + rows.span);
            }
        }
        boolean columnFlow = s.get("grid-auto-flow").contains("column");
        if (columnFlow) {
            cols = Math.max(cols, (children.size() + rowCount - 1) / rowCount);
        }
        if (cols > 256) {
            throw new PdfConversionException("Grid track limit exceeded");
        }
        while (specs.size() < cols) {
            specs.add(s.get("grid-auto-columns", "auto"));
        }
        List<GridTracks.Track> tracks = new ArrayList<>();
        for (String spec : specs) {
            tracks.add(
                    GridTracks.parse(
                            spec,
                            value ->
                                    CssValues.length(
                                            value,
                                            width,
                                            em(s),
                                            css.rootFontSize,
                                            config.pageWidth,
                                            config.pageHeight,
                                            0)));
        }
        record Placement(HtmlNode node, int row, int col, int rows, int cols) {
        }
        record Item(HtmlNode n, int row, int col, int rows, int cols, LayoutResult layout) {
        }
        List<Placement> placements = new ArrayList<>();
        Set<Long> used = new HashSet<>();
        for (HtmlNode child : children) {
            Axis column = gridAxis(style(child), "column", columnDefinition);
            Axis row = gridAxis(style(child), "row", rowDefinition);
            if (column.start >= 0 && row.start >= 0) {
                if (row.start + row.span > 10000) {
                    throw new PdfConversionException("Grid row limit exceeded");
                }
                for (int y = row.start; y < row.start + row.span; y++) {
                    for (int x = column.start;
                            x < Math.min(cols, column.start + column.span);
                            x++) {
                        used.add(((long) y << 32) | x);
                    }
                }
            }
        }
        int cursor = 0, maxRow = 0;
        for (HtmlNode child : children) {
            if (s.get("grid-auto-flow").contains("dense")) {
                cursor = 0;
            }
            Axis column = gridAxis(style(child), "column", columnDefinition),
                    rowAxis = gridAxis(style(child), "row", rowDefinition);
            int span = Math.min(cols, column.span),
                    col =
                            column.start >= 0
                                    ? Math.min(cols - 1, column.start)
                                    : columnFlow ? cursor / rowCount : cursor % cols;
            if (column.start >= 0) {
                span = Math.min(span, cols - col);
            }
            int row =
                    rowAxis.start >= 0
                            ? rowAxis.start
                            : columnFlow ? cursor % rowCount : cursor / cols;
            int tries = 0;
            while (col + span > cols
                    || columnFlow && row + rowAxis.span > rowCount && rowAxis.start < 0
                    || ((column.start < 0 || rowAxis.start < 0)
                            && occupied(used, row, col, rowAxis.span, span))) {
                if (++tries > config.maxElements + cols) {
                    throw new PdfConversionException("Grid placement limit exceeded");
                }
                if (columnFlow) {
                    if (rowAxis.start >= 0) {
                        col++;
                    } else {
                        row++;
                        if (row + rowAxis.span > rowCount) {
                            row = 0;
                            col++;
                        }
                    }
                    if (col + span > cols) {
                        throw new PdfConversionException("Grid implicit column limit reached");
                    }
                } else if (column.start >= 0) {
                    row++;
                } else {
                    col++;
                    if (col + span > cols) {
                        col = 0;
                        if (rowAxis.start < 0) {
                            row++;
                        } else {
                            throw new PdfConversionException(
                                    "Explicit grid row has insufficient columns");
                        }
                    }
                }
            }
            if (row + rowAxis.span > 10000) {
                throw new PdfConversionException("Grid row limit exceeded");
            }
            for (int j = 0; j < rowAxis.span; j++) {
                for (int i = 0; i < span; i++) {
                    used.add(((long) (row + j) << 32) | (col + i));
                }
            }
            placements.add(new Placement(child, row, col, rowAxis.span, span));
            maxRow = Math.max(maxRow, row + rowAxis.span - 1);
            cursor =
                    Math.max(
                            cursor,
                            columnFlow
                                    ? col * rowCount + row + rowAxis.span
                                    : row * cols + col + span);
        }
        boolean[] collapsed = new boolean[cols];
        int visibleColumns = cols;
        if (template.contains("auto-fit")) {
            for (int column = 0; column < cols; column++) {
                int current = column;
                if (placements.stream()
                        .noneMatch(item -> current >= item.col && current < item.col + item.cols)) {
                    collapsed[column] = true;
                    tracks.set(column, new GridTracks.Track(0, 0, 0, false));
                    visibleColumns--;
                }
            }
        }
        double[] contributions = new double[cols];
        for (Placement item : placements) {
            double present = gap * Math.max(0, item.cols - 1);
            int flexible = 0;
            for (int column = item.col; column < item.col + item.cols; column++) {
                GridTracks.Track track = tracks.get(column);
                present += Math.max(track.minimum(), contributions[column]);
                if (track.intrinsic() || track.fraction() > 0) {
                    flexible++;
                }
            }
            ComputedStyle childStyle = style(item.node);
            double extras =
                    edge(childStyle, "padding", "left", width)
                            + edge(childStyle, "padding", "right", width)
                            + border(childStyle, "left")
                            + border(childStyle, "right");
            double deficit = intrinsic(item.node, true) + extras - present;
            if (deficit > 0 && flexible > 0) {
                for (int column = item.col; column < item.col + item.cols; column++) {
                    GridTracks.Track track = tracks.get(column);
                    if (track.intrinsic() || track.fraction() > 0) {
                        contributions[column] =
                                Math.max(track.minimum(), contributions[column])
                                        + deficit / flexible;
                    }
                }
            }
        }
        double[] preferred = new double[cols];
        for (Placement item : placements) {
            double present = gap * Math.max(0, item.cols - 1);
            int flexible = 0;
            for (int column = item.col; column < item.col + item.cols; column++) {
                GridTracks.Track track = tracks.get(column);
                present += Math.max(track.minimum(), preferred[column]);
                if (track.intrinsic() || track.fraction() > 0) {
                    flexible++;
                }
            }
            ComputedStyle childStyle = style(item.node);
            double extras =
                    edge(childStyle, "padding", "left", width)
                            + edge(childStyle, "padding", "right", width)
                            + border(childStyle, "left")
                            + border(childStyle, "right");
            double deficit = intrinsic(item.node, false) + extras - present;
            if (deficit > 0 && flexible > 0) {
                for (int column = item.col; column < item.col + item.cols; column++) {
                    GridTracks.Track track = tracks.get(column);
                    if (track.intrinsic() || track.fraction() > 0) {
                        preferred[column] =
                                Math.max(track.minimum(), preferred[column]) + deficit / flexible;
                    }
                }
            }
        }
        for (Placement item : placements) {
            if (style(item.node).get("grid-template-columns").startsWith("subgrid")) {
                contributeSubgrid(
                        item.node, item.col, item.cols, tracks, contributions, gap, width, true, 0);
                contributeSubgrid(
                        item.node, item.col, item.cols, tracks, preferred, gap, width, false, 0);
            }
        }
        double[] widths =
                GridTracks.size(
                        tracks,
                        contributions,
                        preferred,
                        width - gap * Math.max(0, visibleColumns - 1));
        List<Item> items = new ArrayList<>();
        for (Placement item : placements) {
            double itemWidth = gap * (item.cols - 1);
            for (int column = item.col; column < item.col + item.cols; column++) {
                itemWidth += widths[column];
            }
            if (style(item.node).get("grid-template-columns").startsWith("subgrid")) {
                subgridColumns.put(
                        item.node,
                        inheritedGridAxis(columnDefinition, widths, item.col, item.cols));
                subgridColumnGaps.put(item.node, gap);
            }
            items.add(
                    new Item(
                            item.node,
                            item.row,
                            item.col,
                            item.rows,
                            item.cols,
                            box(item.node, itemWidth, itemWidth, depth)));
        }
        List<String> rowSpecs = rowDefinition.tracks();
        int count = Math.max(items.isEmpty() ? 0 : maxRow + 1, rowSpecs.size());
        double rg = len(s, "row-gap", width, 0);
        if (s.get("grid-template-rows").startsWith("subgrid") && s.get("row-gap").isBlank()) {
            rg = subgridRowGaps.getOrDefault(n, rg);
        }
        double target = usedHeight(n);
        if (!Double.isNaN(target) && s.is("box-sizing", "border-box")) {
            target -=
                    edge(s, "padding", "top", width)
                            + edge(s, "padding", "bottom", width)
                            + border(s, "top")
                            + border(s, "bottom");
        }
        List<GridTracks.Track> rowTracks = new ArrayList<>();
        double rowBasis = Double.isNaN(target) ? 0 : target;
        for (int index = 0; index < count; index++) {
            String spec =
                    index < rowSpecs.size() ? rowSpecs.get(index) : s.get("grid-auto-rows", "auto");
            rowTracks.add(
                    GridTracks.parse(
                            spec,
                            value ->
                                    CssValues.length(
                                            value,
                                            rowBasis,
                                            em(s),
                                            css.rootFontSize,
                                            config.pageWidth,
                                            config.pageHeight,
                                            0)));
        }
        double[] rowContributions = new double[count];
        for (Item item : items) {
            double present = rg * (item.rows - 1);
            int flexible = 0;
            for (int index = item.row; index < item.row + item.rows; index++) {
                present += Math.max(rowTracks.get(index).minimum(), rowContributions[index]);
                if (rowTracks.get(index).intrinsic() || rowTracks.get(index).fraction() > 0) {
                    flexible++;
                }
            }
            double deficit = item.layout.height - present;
            if (deficit > 0 && flexible > 0) {
                for (int index = item.row; index < item.row + item.rows; index++) {
                    GridTracks.Track track = rowTracks.get(index);
                    if (track.intrinsic() || track.fraction() > 0) {
                        rowContributions[index] =
                                Math.max(track.minimum(), rowContributions[index])
                                        + deficit / flexible;
                    }
                }
            } else if (deficit > .01) {
                warn("GRID_ITEM_OVERFLOW", "Grid item exceeds fixed row tracks: " + item.n.tag);
            }
        }
        double natural = 0;
        for (int index = 0; index < count; index++) {
            natural += Math.max(rowTracks.get(index).minimum(), rowContributions[index]);
        }
        double[] heights =
                GridTracks.size(
                        rowTracks,
                        rowContributions,
                        Double.isNaN(target) ? natural : target - rg * Math.max(0, count - 1));
        double[] ys = new double[count + 1], xs = new double[cols];
        for (int i = 0; i < count; i++) {
            ys[i + 1] = ys[i] + heights[i] + rg;
        }
        double columnX = 0;
        boolean priorVisible = false;
        for (int index = 0; index < cols; index++) {
            if (!collapsed[index]) {
                if (priorVisible) {
                    columnX += gap;
                }
                xs[index] = columnX;
                columnX += widths[index];
                priorVisible = true;
            } else {
                xs[index] = columnX;
            }
        }
        LayoutResult out = new LayoutResult();
        for (Item item : items) {
            LayoutResult itemLayout = item.layout;
            if (style(item.n).get("grid-template-rows").startsWith("subgrid")) {
                subgridRows.put(
                        item.n, inheritedGridAxis(rowDefinition, heights, item.row, item.rows));
                subgridRowGaps.put(item.n, rg);
                double itemWidth = gap * (item.cols - 1);
                for (int column = item.col; column < item.col + item.cols; column++) {
                    itemWidth += widths[column];
                }
                itemLayout = box(item.n, itemWidth, itemWidth, depth);
            }
            double area = ys[item.row + item.rows] - ys[item.row] - rg;
            String align = style(item.n).get("align-self", s.get("align-items", "stretch"));
            double dy =
                    align.equals("center")
                            ? (area - itemLayout.height) / 2
                            : Set.of("end", "flex-end").contains(align)
                                    ? area - itemLayout.height
                                    : 0;
            if (align.equals("stretch")) {
                stretchHeight(itemLayout, area);
            }
            out.append(itemLayout, xs[item.col], ys[item.row] + dy);
        }
        for (int i = 0; i < count; i++) {
            out.breaks.add(ys[i]);
            out.breaks.add(ys[i] + heights[i]);
        }
        out.height = count == 0 ? 0 : ys[count] - rg;
        return out;
    }

    private record Axis(int start, int span) {
    }

    private void contributeSubgrid(
            HtmlNode node,
            int start,
            int span,
            List<GridTracks.Track> tracks,
            double[] contributions,
            double gap,
            double width,
            boolean minimum,
            int depth) {
        if (depth > config.maxDepth) {
            throw new PdfConversionException("Subgrid depth exceeded");
        }
        GridDefinition.Axis axis =
                new GridDefinition.Axis(
                        new ArrayList<>(java.util.Collections.nCopies(span, "auto")),
                        new LinkedHashMap<>());
        int cursor = 0;
        for (HtmlNode child : layoutItems(node)) {
            Axis placement = gridAxis(style(child), "column", axis);
            int column = placement.start >= 0 ? placement.start : cursor % span;
            int count = Math.min(placement.span, span - Math.min(column, span - 1));
            column = Math.min(column, span - 1);
            cursor = column + count;
            double current = gap * Math.max(0, count - 1);
            int growing = 0;
            for (int index = start + column; index < start + column + count; index++) {
                current += Math.max(tracks.get(index).minimum(), contributions[index]);
                if (tracks.get(index).intrinsic() || tracks.get(index).fraction() > 0) {
                    growing++;
                }
            }
            double deficit = intrinsic(child, minimum) + flexExtras(style(child), width) - current;
            if (deficit > 0 && growing > 0) {
                for (int index = start + column; index < start + column + count; index++) {
                    if (tracks.get(index).intrinsic() || tracks.get(index).fraction() > 0) {
                        contributions[index] =
                                Math.max(tracks.get(index).minimum(), contributions[index])
                                        + deficit / growing;
                    }
                }
            }
            if (style(child).get("grid-template-columns").startsWith("subgrid")) {
                contributeSubgrid(
                        child,
                        start + column,
                        count,
                        tracks,
                        contributions,
                        gap,
                        width,
                        minimum,
                        depth + 1);
            }
        }
    }

    private GridDefinition.Axis inheritedGridAxis(
            GridDefinition.Axis parent, double[] sizes, int start, int span) {
        List<String> tracks = new ArrayList<>();
        for (int index = start; index < start + span; index++) {
            tracks.add(sizes[index] + "pt");
        }
        Map<String, List<Integer>> lines = new LinkedHashMap<>();
        parent.lines()
                .forEach(
                        (name, positions) -> {
                            List<Integer> shifted =
                                    positions.stream()
                                            .filter(
                                                    position ->
                                                            position >= start
                                                                    && position <= start + span)
                                            .map(position -> position - start)
                                            .toList();
                            if (!shifted.isEmpty()) {
                                lines.put(name, new ArrayList<>(shifted));
                            }
                        });
        return new GridDefinition.Axis(tracks, lines);
    }

    private Axis gridAxis(ComputedStyle style, String axis, GridDefinition.Axis definition) {
        String startValue = style.get("grid-" + axis + "-start", "auto");
        String endValue = style.get("grid-" + axis + "-end", "auto");
        int start = definition.line(startValue, false);
        int end = definition.line(endValue, true);
        int span = 1;
        Matcher matcher =
                Pattern.compile("span\\s+([1-9][0-9]*)")
                        .matcher(startValue.startsWith("span") ? startValue : endValue);
        if (matcher.find()) {
            span = Integer.parseInt(matcher.group(1));
            if (start < 0 && end >= 0) {
                start = Math.max(0, end - span);
            }
        } else if (start >= 0 && end >= 0) {
            span = Math.max(1, end - start);
        } else if (end >= 0) {
            start = Math.max(0, end - 1);
        }
        if (span > 256) {
            throw new PdfConversionException("Grid span limit exceeded");
        }
        if (start < 0 && !startValue.equals("auto") && !startValue.startsWith("span")) {
            warn("UNSUPPORTED_GRID_LINE", startValue);
        }
        return new Axis(start, span);
    }

    private List<String> gridTrackTokens(String template) {
        return GridTracks.expand(
                template,
                config.pageHeight,
                0,
                value ->
                        CssValues.length(
                                value,
                                config.pageHeight,
                                12,
                                12,
                                config.pageWidth,
                                config.pageHeight,
                                0));
    }

    private double gridFraction(String spec) {
        try {
            return Math.max(0, Double.parseDouble(spec.substring(0, spec.length() - 2)));
        } catch (NumberFormatException e) {
            warn("INVALID_GRID_FRACTION", spec);
            return 1;
        }
    }

    private boolean occupied(Set<Long> used, int row, int col, int rows, int cols) {
        for (int j = 0; j < rows; j++) {
            for (int i = 0; i < cols; i++) {
                if (used.contains(((long) (row + j) << 32) | (col + i))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static final class Counter {
        int value;
        final int depth;

        Counter(int value, int depth) {
            this.value = value;
            this.depth = depth;
        }
    }

    private record ContentRequest(
            HtmlNode node, String content, Map<String, List<Integer>> counters, boolean leading) {
            }

    private void prepareCounters(HtmlNode root) {
        before.clear();
        after.clear();
        targetCounters.clear();
        generatedRequests.clear();
        visitCounters(root, new HashMap<>(), 0);
        for (ContentRequest request : generatedRequests) {
            String value =
                    GeneratedContent.evaluate(
                            request.content(),
                            request.node(),
                            request.counters(),
                            targetPages,
                            targetCounters);
            (request.leading() ? before : after).put(request.node(), value);
        }
    }

    private Map<String, List<Integer>> counterValues(Map<String, List<Counter>> counters) {
        Map<String, List<Integer>> values = new HashMap<>();
        counters.forEach((name, c) -> values.put(name, c.stream().map(v -> v.value).toList()));
        return values;
    }

    private void visitCounters(HtmlNode n, Map<String, List<Counter>> scope, int depth) {
        if (depth > config.maxDepth) {
            throw new PdfConversionException("Counter depth limit exceeded");
        }
        if (n.tag.equals("#text")) {
            return;
        }
        ComputedStyle s = style(n);
        if (s == null || s.is("display", "none")) {
            return;
        }
        mutateCounters(s.get("counter-reset"), scope, 0, "reset", depth);
        mutateCounters(s.get("counter-set"), scope, 0, "set", depth);
        mutateCounters(s.get("counter-increment"), scope, 1, "increment", depth);
        Map<String, List<Integer>> values = Map.copyOf(counterValues(scope));
        if (!n.attr("id").isBlank()) {
            targetCounters.putIfAbsent(n.attr("id"), values);
        }
        if (n.tag.equals("a") && !n.attr("name").isBlank()) {
            targetCounters.putIfAbsent(n.attr("name"), values);
        }
        Map<String, List<Counter>> childrenScope = new HashMap<>(scope);
        if (css.before.containsKey(n)) {
            ComputedStyle pseudo = css.before.get(n);
            mutateCounters(pseudo.get("counter-reset"), childrenScope, 0, "reset", depth + 1);
            mutateCounters(pseudo.get("counter-set"), childrenScope, 0, "set", depth + 1);
            mutateCounters(
                    pseudo.get("counter-increment"), childrenScope, 1, "increment", depth + 1);
            generatedRequests.add(
                    new ContentRequest(
                            n,
                            pseudo.get("content"),
                            Map.copyOf(counterValues(childrenScope)),
                            true));
        }
        for (HtmlNode child : n.children) {
            visitCounters(child, childrenScope, depth + 1);
        }
        if (css.after.containsKey(n)) {
            ComputedStyle pseudo = css.after.get(n);
            mutateCounters(pseudo.get("counter-reset"), childrenScope, 0, "reset", depth + 1);
            mutateCounters(pseudo.get("counter-set"), childrenScope, 0, "set", depth + 1);
            mutateCounters(
                    pseudo.get("counter-increment"), childrenScope, 1, "increment", depth + 1);
            generatedRequests.add(
                    new ContentRequest(
                            n,
                            pseudo.get("content"),
                            Map.copyOf(counterValues(childrenScope)),
                            false));
        }
    }

    private void mutateCounters(
            String text,
            Map<String, List<Counter>> scope,
            int defaultValue,
            String operation,
            int depth) {
        List<String> tokens = CssValues.tokens(text);
        for (int i = 0; i < tokens.size(); i++) {
            String name = tokens.get(i);
            if (name.equals("none")) {
                continue;
            }
            int value = defaultValue;
            if (i + 1 < tokens.size() && tokens.get(i + 1).matches("-?\\d+")) {
                value = Integer.parseInt(tokens.get(++i));
            }
            List<Counter> list = scope.get(name);
            if (operation.equals("reset")) {
                list = list == null ? new ArrayList<>() : new ArrayList<>(list);
                list.removeIf(counter -> counter.depth >= depth);
                list.add(new Counter(value, depth));
                scope.put(name, list);
            } else {
                if (list == null) {
                    list = new ArrayList<>();
                    list.add(new Counter(0, depth));
                    scope.put(name, list);
                }
                Counter c = list.get(list.size() - 1);
                c.value = operation.equals("increment") ? Math.addExact(c.value, value) : value;
            }
        }
    }
}
