package dtm.pdfloom.css;

import com.helger.css.decl.*;
import com.helger.css.parser.ParseException;
import com.helger.css.parser.Token;
import com.helger.css.reader.CSSReader;
import com.helger.css.reader.CSSReaderSettings;
import com.helger.css.reader.errorhandler.ICSSInterpretErrorHandler;
import com.helger.css.reader.errorhandler.ICSSParseErrorHandler;
import com.helger.css.writer.CSSWriterSettings;

import dtm.pdfloom.config.*;
import dtm.pdfloom.exception.PdfConversionException;
import dtm.pdfloom.html.*;
import dtm.pdfloom.resource.ResourceLoader;
import dtm.pdfloom.style.*;

import java.net.URI;
import java.util.*;
import java.util.regex.*;

public final class CascadeStyleResolver implements StyleResolver {
    private record Declaration(String key, String value, boolean important) {
    }

    private record Rule(
            String selector,
            String pseudo,
            List<Declaration> declarations,
            long specificity,
            int order,
            List<Integer> layer) {
            }

    private record Winner(
            String value,
            boolean important,
            long specificity,
            int order,
            List<Integer> layer,
            boolean inline)
            implements Comparable<Winner> {
        public int compareTo(Winner o) {
            return new CssPriority(important, inline, layer, specificity, order)
                    .compareTo(
                            new CssPriority(
                                    o.important, o.inline, o.layer, o.specificity, o.order));
        }
    }

    private static final Pattern VIEWPORT_UNIT =
            Pattern.compile(
                    "(?i)(?<![\\w.-])[-+]?(?:\\d+\\.?\\d*|\\.\\d+)"
                            + "(?:[sld]?v[hw]|vmin|vmax|vi|vb)(?![\\w./-])");
    private static final Set<String> SVG_PROPERTIES =
            Set.of(
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
                    "text-anchor",
                    "dominant-baseline");
    private static final Set<String> INHERITED =
            Set.of(
                    "color",
                    "font-family",
                    "font-size",
                    "font-weight",
                    "font-style",
                    "font-kerning",
                    "font-variant-ligatures",
                    "text-shadow",
                    "line-height",
                    "letter-spacing",
                    "word-spacing",
                    "text-align",
                    "text-transform",
                    "white-space",
                    "word-break",
                    "overflow-wrap",
                    "direction",
                    "visibility",
                    "list-style-type",
                    "list-style-image",
                    "list-style-position",
                    "orphans",
                    "widows",
                    "caption-side");
    private static final Set<String> SUPPORTED =
            new HashSet<>(
                    Arrays.asList(
                            ("display width height min-width max-width min-height max-height margin"
                                 + " padding border border-width border-style border-color"
                                 + " border-radius background background-color background-image"
                                 + " background-size background-position background-repeat"
                                 + " box-sizing overflow position top right bottom left z-index"
                                 + " float clear opacity transform transform-origin color font"
                                 + " font-family font-size font-weight font-style line-height"
                                 + " letter-spacing word-spacing text-align vertical-align"
                                 + " text-indent text-transform text-decoration white-space"
                                 + " word-break word-wrap overflow-wrap direction visibility"
                                 + " list-style list-style-type list-style-image"
                                 + " list-style-position border-collapse border-spacing"
                                 + " table-layout content counter-reset counter-increment"
                                 + " counter-set break-before break-after break-inside"
                                 + " page-break-before page-break-after page-break-inside orphans"
                                 + " widows page size flex flex-grow flex-shrink flex-basis"
                                 + " flex-direction flex-wrap flex-flow justify-content align-items"
                                 + " align-self align-content order gap row-gap column-gap"
                                 + " grid-template-columns grid-template-rows grid-column grid-row"
                                 + " grid-auto-flow grid-auto-columns grid-auto-rows"
                                 + " grid-template-areas grid-area grid-row-start grid-row-end"
                                 + " grid-column-start grid-column-end object-fit object-position"
                                 + " string-set caption-side font-kerning font-variant-ligatures"
                                 + " margin-break all text-overflow box-shadow text-shadow"
                                 + " background-position background-size background-repeat"
                                 + " background-origin background-clip column-count column-width"
                                 + " columns column-fill column-span column-rule column-rule-width"
                                 + " column-rule-style column-rule-color")
                                    .split(" ")));
    private final PdfConversionConfig config;
    private final ResourceLoader loader;
    private final List<Rule> rules = new ArrayList<>();
    private final Set<String> active = new HashSet<>(), reported = new HashSet<>();
    private final StyleSheet result = new StyleSheet();
    private final Map<String, List<Integer>> layers = new LinkedHashMap<>();
    private final Map<String, Integer> layerChildren = new HashMap<>();
    private double rootFontSize = 12;
    private URI currentOrigin;
    private int sequence;

    public CascadeStyleResolver(PdfConversionConfig c, ResourceLoader loader) {
        config = c;
        this.loader = loader;
    }

    private void warn(String code, String message) {
        if (config.unsupportedCssPolicy == PdfConversionConfig.UnsupportedCssPolicy.FAIL
                && (code.startsWith("UNSUPPORTED")
                        || code.startsWith("PARTIAL")
                        || code.equals("CSS_SKIPPED_SYNTAX"))) {
            throw new PdfConversionException(code + ": " + message + " at " + currentOrigin);
        }
        if (reported.add(code + message)) {
            config.report(
                    new Diagnostic(
                            Diagnostic.Severity.WARNING,
                            code,
                            message
                                    + " at "
                                    + (currentOrigin == null ? "inline document" : currentOrigin)));
        }
    }

    @Override
    public StyleSheet resolve(HtmlParser.Document document, URI origin) {
        result.elements = document.elements();
        for (HtmlNode n : document.elements()) {
            if (n.tag.equals("style") && media(n.attr("media"))) {
                read(n.textContent(), origin, 0);
            }
            if (n.tag.equals("link")
                    && Arrays.asList(n.attr("rel").toLowerCase(Locale.ROOT).split("\\s+"))
                            .contains("stylesheet")
                    && media(n.attr("media"))) {
                ResourceLoader.Data d = loader.load(n.attr("href"), origin);
                if (d != null) {
                    read(new String(d.bytes(), config.charset), d.uri(), 0);
                }
            }
        }
        for (HtmlNode n : document.elements()) {
            Map<String, String> parent =
                    n.parent == null || result.style(n.parent) == null
                            ? Map.of()
                            : result.style(n.parent).values();
            Map<String, String> values = defaults(n);
            parent.forEach(
                    (k, v) -> {
                        if ((INHERITED.contains(k)
                                        || SVG_PROPERTIES.contains(k)
                                        || k.startsWith("--"))
                                && !uaOverride(n, k)) {
                            values.put(k, v);
                        }
                    });
            if (svgNode(n)) {
                Set<String> presentation = new HashSet<>(SUPPORTED);
                presentation.addAll(SVG_PROPERTIES);
                for (String property : presentation) {
                    if (!n.attr(property).isBlank()) {
                        values.put(property, n.attr(property));
                    }
                }
            }
            Map<String, List<Winner>> selected = new HashMap<>();
            for (Rule r : rules) {
                if (!r.pseudo.isEmpty()) {
                    continue;
                }
                try {
                    if (n.matches(r.selector)) {
                        apply(selected, r.declarations, r.specificity, r.order, r.layer, false);
                    }
                } catch (IllegalArgumentException e) {
                    warn("UNSUPPORTED_SELECTOR", r.selector);
                }
            }
            if (!n.attr("style").isBlank()) {
                CascadingStyleSheet inline = parseCss("x{" + n.attr("style") + "}");
                if (inline == null) {
                    throw new PdfConversionException("Invalid inline CSS on " + n.tag);
                }
                apply(
                        selected,
                        declarations(inline.getAllStyleRules().get(0).getAllDeclarations(), origin),
                        1L << 60,
                        ++sequence,
                        List.of(Integer.MAX_VALUE),
                        true);
            }
            String fontExpression = finish(values, parent, selected);
            if (!selected.containsKey("font-size") && !uaOverride(n, "font-size")) {
                fontExpression = "inherit";
            }
            result.fontExpressions.put(n, fontExpression);
            if (n.tag.equals("html")) {
                rootFontSize =
                        CssValues.length(
                                values.get("font-size"),
                                12,
                                12,
                                12,
                                config.pageWidth,
                                config.pageHeight,
                                12);
                result.rootFontSize = rootFontSize;
            }
            result.styles.put(n, new ComputedStyle(values));
            for (String pseudo : List.of("before", "after")) {
                Map<String, List<Winner>> sel = new HashMap<>();
                for (Rule r : rules) {
                    if (r.pseudo.equals(pseudo)) {
                        try {
                            if (n.matches(r.selector)) {
                                apply(sel, r.declarations, r.specificity, r.order, r.layer, false);
                            }
                        } catch (IllegalArgumentException e) {
                            warn("UNSUPPORTED_SELECTOR", r.selector);
                        }
                    }
                }
                if (pseudo.equals("before")
                        && !n.tag.equals("meta")
                        && !n.attr("content").isBlank()) {
                    apply(
                            sel,
                            List.of(new Declaration("content", n.attr("content").strip(), false)),
                            1L << 60,
                            ++sequence,
                            List.of(Integer.MAX_VALUE),
                            true);
                }
                if (!sel.isEmpty()) {
                    Map<String, String> pv = new HashMap<>();
                    values.forEach(
                            (k, v) -> {
                                if (INHERITED.contains(k)
                                        || SVG_PROPERTIES.contains(k)
                                        || k.startsWith("--")) {
                                    pv.put(k, v);
                                }
                            });
                    pv.put("display", "inline");
                    finish(pv, values, sel);
                    (pseudo.equals("before") ? result.before : result.after)
                            .put(n, new ComputedStyle(pv));
                }
            }
        }
        return result;
    }

    private boolean media(String m) {
        return CssConditions.media(m, config);
    }

    private CascadingStyleSheet parseCss(String source) {
        CSSReaderSettings settings = new CSSReaderSettings().setFallbackCharset(config.charset);
        settings.setCustomErrorHandler(
                new ICSSParseErrorHandler() {
                    public void onCSSParseError(ParseException e, Token token) {
                        warn("CSS_PARSE_RECOVERY", e.getMessage());
                    }

                    public void onCSSUnexpectedRule(Token token, String rule, String message) {
                        warn("CSS_UNEXPECTED_RULE", rule + ": " + message);
                    }

                    public void onCSSDeprecatedProperty(Token token, Token next) {
                        warn("CSS_DEPRECATED_SYNTAX", token.image);
                    }

                    public void onCSSBrowserCompliantSkip(
                            ParseException e, Token start, Token end) {
                        warn("CSS_SKIPPED_SYNTAX", e.getMessage());
                    }

                    public void onIllegalCharacter(char character) {
                        warn("CSS_ILLEGAL_CHARACTER", "U+" + Integer.toHexString(character));
                    }
                });
        settings.setInterpretErrorHandler(
                new ICSSInterpretErrorHandler() {
                    public void onCSSInterpretationWarning(String message) {
                        warn("CSS_INTERPRETATION", message);
                    }

                    public void onCSSInterpretationError(String message) {
                        throw new PdfConversionException("CSS interpretation failed: " + message);
                    }
                });
        settings.setCustomExceptionHandler(
                e -> {
                    throw new PdfConversionException("CSS parsing failed", e);
                });
        return CSSReader.readFromStringReader(source, settings);
    }

    private void read(String css, URI origin, int depth) {
        if (depth > 32) {
            throw new PdfConversionException("CSS import depth exceeded");
        }
        String key = origin == null ? "inline:" + css.hashCode() : origin.toString();
        if (!active.add(key)) {
            throw new PdfConversionException("CSS import cycle: " + key);
        }
        try {
            URI previousOrigin = currentOrigin;
            currentOrigin = origin;
            CascadingStyleSheet sheet = parseCss(css);
            if (sheet == null) {
                throw new PdfConversionException("CSS parsing failed: " + origin);
            }
            for (CSSImportRule i : sheet.getAllImportRules()) {
                if (i.getAllMediaQueries().isEmpty()
                        || i.getAllMediaQueries().stream()
                                .anyMatch(
                                        m -> media(m.getAsCSSString(new CSSWriterSettings(), 0)))) {
                    ResourceLoader.Data d = loader.load(i.getLocationString(), origin);
                    if (d != null) {
                        read(new String(d.bytes(), config.charset), d.uri(), depth + 1);
                    }
                }
            }
            readRules(sheet.getAllRules(), origin, "", List.of());
            currentOrigin = previousOrigin;
        } finally {
            active.remove(key);
        }
    }

    private void readRules(List<?> items, URI origin, String layer, List<String> parents) {
        for (Object item : items) {
            if (item instanceof CSSStyleRule s) {
                List<Declaration> d = declarations(s.getAllDeclarations(), origin);
                for (CSSSelector selector : s.getAllSelectors()) {
                    String text = selector.getAsCSSString(new CSSWriterSettings(), 0).trim(),
                            pseudo = "";
                    if (!parents.isEmpty()) {
                        String parent = ":is(" + String.join(",", parents) + ")";
                        text = text.contains("&") ? text.replace("&", parent) : parent + " " + text;
                    }
                    Matcher pm = Pattern.compile("::?(before|after)$").matcher(text);
                    if (pm.find()) {
                        pseudo = pm.group(1);
                        text = text.substring(0, pm.start());
                        if (text.isEmpty()) {
                            text = "*";
                        }
                    }
                    rules.add(
                            new Rule(
                                    text,
                                    pseudo,
                                    d,
                                    specificity(text) + (pseudo.isEmpty() ? 0 : 1),
                                    ++sequence,
                                    layerOrder(layer)));
                }
                if (s.hasRules()) {
                    List<String> selectors = new ArrayList<>();
                    for (CSSSelector selector : s.getAllSelectors()) {
                        String text = selector.getAsCSSString(new CSSWriterSettings(), 0).strip();
                        if (!parents.isEmpty()) {
                            String parent = ":is(" + String.join(",", parents) + ")";
                            text =
                                    text.contains("&")
                                            ? text.replace("&", parent)
                                            : parent + " " + text;
                        }
                        selectors.add(text);
                    }
                    readRules(s.getAllRules(), origin, layer, selectors);
                }
            } else if (item instanceof CSSMediaRule m) {
                if (m.getAllMediaQueries().stream()
                        .anyMatch(q -> media(q.getAsCSSString(new CSSWriterSettings(), 0)))) {
                    readRules(m.getAllRules(), origin, layer, parents);
                }
            } else if (item instanceof CSSLayerRule l) {
                List<String> names = l.getAllSelectors();
                if (names.isEmpty()) {
                    names = List.of("$anonymous" + (++sequence));
                }
                for (String name : names) {
                    String qualified = layer.isEmpty() ? name : layer + "." + name;
                    registerLayer(qualified);
                    if (l.hasRules()) {
                        readRules(l.getAllRules(), origin, qualified, parents);
                    }
                }
            } else if (item instanceof CSSSupportsRule supports) {
                String expression = supports.getAsCSSString(new CSSWriterSettings(), 0);
                expression =
                        expression
                                .substring(
                                        expression.indexOf("supports") + 8, expression.indexOf('{'))
                                .strip();
                if (CssConditions.evaluate(expression, this::supports)) {
                    readRules(supports.getAllRules(), origin, layer, parents);
                }
            } else if (item instanceof CSSPageRule p) {
                Map<String, String> v = new HashMap<>();
                Map<String, ComputedStyle> boxes = new LinkedHashMap<>();
                Map<String, Boolean> important = new HashMap<>();
                Map<String, Map<String, Boolean>> marginImportant = new LinkedHashMap<>();
                for (ICSSPageRuleMember member : p.getAllMembers()) {
                    if (member instanceof CSSDeclaration d) {
                        for (Declaration dec : declarations(List.of(d), origin)) {
                            if (!important.getOrDefault(dec.key, false) || dec.important) {
                                v.put(dec.key, dec.value);
                                important.put(dec.key, dec.important);
                            }
                        }
                    } else if (member instanceof CSSPageMarginBlock b) {
                        String name = b.getPageMarginSymbol().replace("@", "");
                        Map<String, String> bv =
                                new HashMap<>(
                                        boxes.getOrDefault(name, new ComputedStyle(Map.of()))
                                                .values());
                        Map<String, Boolean> bi =
                                marginImportant.computeIfAbsent(name, key -> new HashMap<>());
                        for (Declaration dec : declarations(b.getAllDeclarations(), origin)) {
                            if (!bi.getOrDefault(dec.key, false) || dec.important) {
                                bv.put(dec.key, dec.value);
                                bi.put(dec.key, dec.important);
                            }
                        }
                        boxes.put(name, new ComputedStyle(bv));
                    }
                }
                List<String> selectors = p.getAllSelectors();
                if (selectors.isEmpty()) {
                    selectors = List.of("");
                }
                for (String selector : selectors) {
                    result.pages.add(
                            new StyleSheet.PageRule(
                                    selector,
                                    new ComputedStyle(v),
                                    Map.copyOf(boxes),
                                    important,
                                    marginImportant,
                                    layerOrder(layer)));
                }
            } else if (item instanceof CSSFontFaceRule f) {
                Map<String, String> v = new HashMap<>();
                for (Declaration d : declarations(f.getAllDeclarations(), origin)) {
                    v.put(d.key, d.value);
                }
                Matcher url =
                        Pattern.compile("url\\(\\s*(['\"]?)(.*?)\\1\\s*\\)")
                                .matcher(v.getOrDefault("src", ""));
                if (url.find()) {
                    result.fonts.add(
                            new StyleSheet.FontFace(
                                    CssValues.unquote(v.getOrDefault("font-family", "")),
                                    weight(v.getOrDefault("font-weight", "400")),
                                    v.getOrDefault("font-style", "").equals("italic"),
                                    url.group(2),
                                    origin));
                } else {
                    warn("UNSUPPORTED_FONT_SOURCE", v.toString());
                }
            } else {
                warn("UNSUPPORTED_AT_RULE", item.getClass().getSimpleName());
            }
        }
    }

    private List<Declaration> declarations(List<CSSDeclaration> input, URI origin) {
        List<Declaration> out = new ArrayList<>();
        for (CSSDeclaration d : input) {
            String k = d.getProperty().toLowerCase(Locale.ROOT), v = d.getExpressionAsCSSString();
            if (!k.startsWith("--")
                    && !SUPPORTED.contains(k)
                    && !SVG_PROPERTIES.contains(k)
                    && !k.equals("src")
                    && !k.matches(
                            "(?:margin|padding|border)-(?:top|right|bottom|left)(?:-(?:color|style|width))?")) {
                warn("UNSUPPORTED_PROPERTY", k);
            }
            if (!k.startsWith("--") && VIEWPORT_UNIT.matcher(v).find()) {
                warn(
                        "UNSUPPORTED_VIEWPORT_UNIT",
                        k + ": " + v + " ignored; paged media has no viewport");
                continue;
            }
            if (k.equals("grid-auto-flow")
                    && !Set.of(
                                    "row",
                                    "column",
                                    "dense",
                                    "row dense",
                                    "dense row",
                                    "column dense",
                                    "dense column")
                            .contains(v)) {
                warn("UNSUPPORTED_GRID_FLOW", v);
            }
            Matcher urls = Pattern.compile("url\\(\\s*(['\"]?)(.*?)\\1\\s*\\)").matcher(v);
            StringBuffer normalized = new StringBuffer();
            while (urls.find()) {
                String ref = urls.group(2);
                if (origin != null
                        && !ref.startsWith("/")
                        && !ref.startsWith("data:")
                        && !URI.create(ref.replace(" ", "%20")).isAbsolute()) {
                    ref = origin.resolve(ref.replace(" ", "%20")).toString();
                }
                urls.appendReplacement(
                        normalized, Matcher.quoteReplacement("url(\"" + ref + "\")"));
            }
            urls.appendTail(normalized);
            v = normalized.toString();
            expand(out, k, v, d.isImportant());
        }
        return out;
    }

    private void registerLayer(String name) {
        if (layers.containsKey(name)) {
            return;
        }
        int separator = name.lastIndexOf('.');
        String parent = separator < 0 ? "" : name.substring(0, separator);
        List<Integer> order = new ArrayList<>();
        if (!parent.isEmpty()) {
            registerLayer(parent);
            order.addAll(layers.get(parent));
        }
        int child = layerChildren.getOrDefault(parent, 0);
        layerChildren.put(parent, child + 1);
        order.add(child);
        layers.put(name, List.copyOf(order));
    }

    private List<Integer> layerOrder(String name) {
        List<Integer> order = new ArrayList<>();
        if (!name.isEmpty()) {
            registerLayer(name);
            order.addAll(layers.get(name));
        }
        order.add(Integer.MAX_VALUE);
        return List.copyOf(order);
    }

    private boolean supports(String expression) {
        int separator = expression.indexOf(':');
        if (separator < 1) {
            return false;
        }
        String property = expression.substring(0, separator).strip();
        String value = expression.substring(separator + 1).strip();
        if (property.startsWith("--")) {
            return !value.isBlank();
        }
        if (!SUPPORTED.contains(property) || value.isBlank()) {
            return false;
        }
        if (Set.of("initial", "inherit", "unset", "revert", "revert-layer").contains(value)) {
            return true;
        }
        if (property.equals("display")) {
            return Set.of(
                            "none",
                            "block",
                            "inline",
                            "inline-block",
                            "flex",
                            "inline-flex",
                            "grid",
                            "inline-grid",
                            "table",
                            "inline-table",
                            "table-row",
                            "table-cell",
                            "table-row-group",
                            "table-header-group",
                            "table-footer-group",
                            "table-caption",
                            "list-item")
                    .contains(value);
        }
        if (property.equals("position")) {
            return Set.of("static", "relative", "absolute", "fixed").contains(value);
        }
        if (property.equals("margin-break")) {
            return Set.of("auto", "keep").contains(value);
        }
        if (property.equals("object-fit")) {
            return Set.of("fill", "contain", "cover", "none", "scale-down").contains(value);
        }
        if (property.equals("grid-auto-flow")) {
            return Set.of(
                            "row",
                            "column",
                            "dense",
                            "row dense",
                            "dense row",
                            "column dense",
                            "dense column")
                    .contains(value);
        }
        if (property.matches(
                "(?:min-|max-)?(?:width|height)|font-size|(?:margin|padding)(?:-.+)?")) {
            return Set.of("auto", "none", "min-content", "max-content").contains(value)
                    || CssValues.tokens(value).stream()
                            .allMatch(
                                    token ->
                                            Double.isFinite(
                                                    CssValues.length(
                                                            token,
                                                            100,
                                                            12,
                                                            12,
                                                            config.pageWidth,
                                                            config.pageHeight,
                                                            Double.NaN)));
        }
        if (property.startsWith("background")) {
            return !value.contains("image-set(") && !value.contains("cross-fade(");
        }
        if (property.equals("color") || property.endsWith("-color")) {
            return dtm.pdfloom.render.CssColor.recognized(value);
        }
        CascadingStyleSheet parsed = parseCss("x{" + property + ":" + value + "}");
        return parsed != null
                && !parsed.getAllStyleRules().isEmpty()
                && parsed.getAllStyleRules().get(0).hasDeclarations();
    }

    private void expand(List<Declaration> out, String k, String v, boolean imp) {
        out.add(new Declaration(k, v, imp));
        if (Set.of("initial", "inherit", "unset", "revert", "revert-layer").contains(v)) {
            List<String> properties =
                    switch (k) {
                        case "font" ->
                                List.of(
                                        "font-style",
                                        "font-weight",
                                        "font-size",
                                        "line-height",
                                        "font-family",
                                        "font-kerning",
                                        "font-variant-ligatures");
                        case "flex" -> List.of("flex-grow", "flex-shrink", "flex-basis");
                        case "background" ->
                                List.of(
                                        "background-color",
                                        "background-image",
                                        "background-position",
                                        "background-size",
                                        "background-repeat");
                        case "list-style" ->
                                List.of(
                                        "list-style-type",
                                        "list-style-position",
                                        "list-style-image");
                        case "gap" -> List.of("row-gap", "column-gap");
                        case "all" ->
                                SUPPORTED.stream()
                                        .filter(
                                                property ->
                                                        !property.equals("direction")
                                                                && !property.equals("all"))
                                        .toList();
                        default -> List.of();
                    };
            if (!properties.isEmpty()) {
                for (String property : properties) {
                    out.add(new Declaration(property, v, imp));
                }
                return;
            }
        }
        if (k.equals("margin")
                || k.equals("padding")
                || k.equals("border-width")
                || k.equals("border-color")
                || k.equals("border-style")) {
            List<String> t = CssValues.tokens(v);
            if (t.isEmpty() || t.size() > 4) {
                return;
            }
            String[] sides = {"top", "right", "bottom", "left"};
            for (int i = 0; i < 4; i++) {
                String value =
                        t.get(
                                i == 0
                                        ? 0
                                        : i == 1
                                                ? Math.min(1, t.size() - 1)
                                                : i == 2
                                                        ? t.size() > 2 ? 2 : 0
                                                        : t.size() == 4
                                                                ? 3
                                                                : Math.min(1, t.size() - 1));
                String key =
                        k.startsWith("border-")
                                ? "border-" + sides[i] + k.substring(6)
                                : k + "-" + sides[i];
                out.add(new Declaration(key, value, imp));
            }
        } else if (k.equals("border") || k.matches("border-(top|right|bottom|left)")) {
            List<String> t = CssValues.tokens(v);
            for (String side :
                    k.equals("border")
                            ? List.of("top", "right", "bottom", "left")
                            : List.of(k.substring(7))) {
                out.add(
                        new Declaration(
                                "border-" + side + "-width",
                                v.equals("none") ? "0" : "medium",
                                imp));
                out.add(new Declaration("border-" + side + "-style", "none", imp));
                for (String token : t) {
                    String suffix =
                            token.matches(
                                                    "[+-]?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)(?:px|pt|mm|cm|in|em|rem|pc)?")
                                            || token.startsWith("calc(")
                                            || Set.of("thin", "medium", "thick").contains(token)
                                    ? "width"
                                    : Set.of(
                                                            "solid", "dashed", "dotted", "double",
                                                            "none", "hidden", "groove", "ridge",
                                                            "inset", "outset")
                                                    .contains(token)
                                            ? "style"
                                            : "color";
                    out.add(new Declaration("border-" + side + "-" + suffix, token, imp));
                }
            }
        } else if (k.equals("background")) {
            out.add(new Declaration("background-color", "transparent", imp));
            List<String> images = new ArrayList<>();
            List<String> positions = new ArrayList<>();
            List<String> sizes = new ArrayList<>();
            List<String> repeats = new ArrayList<>();
            List<String> origins = new ArrayList<>();
            List<String> clips = new ArrayList<>();
            for (String layer : CssValues.commaSeparated(v)) {
                String image = "none";
                List<String> position = new ArrayList<>();
                List<String> size = new ArrayList<>();
                List<String> repeat = new ArrayList<>();
                List<String> boxes = new ArrayList<>();
                boolean afterSlash = false;
                for (String token :
                        CssValues.tokens(String.join(" / ", CssConditions.split(layer, "/")))) {
                    if (token.startsWith("url(") || token.contains("gradient(")) {
                        image = token;
                    } else if (token.equals("/")) {
                        afterSlash = true;
                    } else if (Set.of(
                                    "repeat", "no-repeat", "repeat-x", "repeat-y", "round", "space")
                            .contains(token)) {
                        repeat.add(token);
                    } else if (Set.of("border-box", "padding-box", "content-box").contains(token)) {
                        boxes.add(token);
                    } else if (token.matches("#.*|rgba?\\(.*|hsla?\\(.*")
                            || Set.of(
                                            "white",
                                            "black",
                                            "red",
                                            "green",
                                            "blue",
                                            "gray",
                                            "grey",
                                            "transparent",
                                            "yellow",
                                            "orange",
                                            "purple",
                                            "currentcolor")
                                    .contains(token)) {
                        out.add(new Declaration("background-color", token, imp));
                    } else if (!token.equals("none")) {
                        (afterSlash ? size : position).add(token);
                    }
                }
                images.add(image);
                positions.add(position.isEmpty() ? "0% 0%" : String.join(" ", position));
                sizes.add(size.isEmpty() ? "auto" : String.join(" ", size));
                repeats.add(repeat.isEmpty() ? "repeat" : String.join(" ", repeat));
                origins.add(boxes.isEmpty() ? "padding-box" : boxes.get(0));
                clips.add(boxes.isEmpty() ? "border-box" : boxes.get(boxes.size() - 1));
            }
            out.add(new Declaration("background-image", String.join(",", images), imp));
            out.add(new Declaration("background-position", String.join(",", positions), imp));
            out.add(new Declaration("background-size", String.join(",", sizes), imp));
            out.add(new Declaration("background-repeat", String.join(",", repeats), imp));
            out.add(new Declaration("background-origin", String.join(",", origins), imp));
            out.add(new Declaration("background-clip", String.join(",", clips), imp));
        } else if (k.equals("grid-column") || k.equals("grid-row")) {
            String[] axis = v.split("/", 2);
            out.add(new Declaration(k + "-start", axis[0].strip(), imp));
            out.add(new Declaration(k + "-end", axis.length > 1 ? axis[1].strip() : "auto", imp));
        } else if (k.equals("grid-area")) {
            String[] parts = v.split("/");
            String[] keys = {
                "grid-row-start", "grid-column-start", "grid-row-end", "grid-column-end"
            };
            for (int index = 0; index < keys.length; index++) {
                out.add(
                        new Declaration(
                                keys[index],
                                index < parts.length
                                        ? parts[index].strip()
                                        : parts.length == 1 && v.matches("[a-zA-Z_][\\w-]*")
                                                ? v
                                                : "auto",
                                imp));
            }
        } else if (k.equals("gap")) {
            List<String> t = CssValues.tokens(v);
            if (!t.isEmpty()) {
                out.add(new Declaration("row-gap", t.get(0), imp));
                out.add(new Declaration("column-gap", t.get(t.size() > 1 ? 1 : 0), imp));
            }
        } else if (k.equals("word-wrap")) {
            out.add(new Declaration("overflow-wrap", v, imp));
        } else if (k.equals("font")) {
            Matcher font =
                    Pattern.compile(
                                    "(?i)([0-9.]+(?:px|pt|em|rem|%|mm|cm|in|pc))(?:\\s*/\\s*([0-9.]+(?:px|pt|em|rem|%|mm|cm|in|pc)?))?\\s+(.+)$")
                            .matcher(v);
            if (font.find()) {
                out.add(new Declaration("font-size", font.group(1), imp));
                out.add(
                        new Declaration(
                                "line-height",
                                font.group(2) == null ? "normal" : font.group(2),
                                imp));
                out.add(new Declaration("font-family", font.group(3), imp));
                out.add(new Declaration("font-weight", "400", imp));
                out.add(new Declaration("font-style", "normal", imp));
                for (String prefix : CssValues.tokens(v.substring(0, font.start()))) {
                    if (Set.of("italic", "oblique").contains(prefix)) {
                        out.add(new Declaration("font-style", "italic", imp));
                    } else if (prefix.matches("[1-9]00")
                            || Set.of("bold", "bolder", "lighter").contains(prefix)) {
                        out.add(new Declaration("font-weight", prefix, imp));
                    } else if (!prefix.equals("normal")) {
                        warn("PARTIAL_FONT_SHORTHAND", prefix);
                    }
                }
            } else {
                warn("UNSUPPORTED_FONT_SHORTHAND", v);
            }
        } else if (k.equals("list-style")) {
            out.add(new Declaration("list-style-image", "none", imp));
            out.add(new Declaration("list-style-position", "outside", imp));
            out.add(new Declaration("list-style-type", "disc", imp));
            for (String token : CssValues.tokens(v)) {
                if (Set.of(
                                "none",
                                "disc",
                                "circle",
                                "square",
                                "decimal",
                                "decimal-leading-zero",
                                "lower-alpha",
                                "upper-alpha",
                                "lower-roman",
                                "upper-roman")
                        .contains(token)) {
                    out.add(new Declaration("list-style-type", token, imp));
                } else if (token.startsWith("url(")) {
                    out.add(new Declaration("list-style-image", token, imp));
                } else if (token.equals("inside") || token.equals("outside")) {
                    out.add(new Declaration("list-style-position", token, imp));
                } else {
                    warn("PARTIAL_LIST_STYLE", token);
                }
            }
        } else if (k.equals("column-rule")) {
            List<Declaration> border = new ArrayList<>();
            expand(border, "border-left", v, imp);
            for (Declaration declaration : border) {
                if (declaration.key.startsWith("border-left-")) {
                    out.add(
                            new Declaration(
                                    declaration.key.replace("border-left-", "column-rule-"),
                                    declaration.value,
                                    imp));
                }
            }
        } else if (k.equals("columns")) {
            out.add(new Declaration("column-count", "auto", imp));
            out.add(new Declaration("column-width", "auto", imp));
            for (String token : CssValues.tokens(v)) {
                out.add(
                        new Declaration(
                                token.matches("[1-9][0-9]*") ? "column-count" : "column-width",
                                token,
                                imp));
            }
        } else if (k.equals("flex-flow")) {
            out.add(new Declaration("flex-direction", "row", imp));
            out.add(new Declaration("flex-wrap", "nowrap", imp));
            for (String token : CssValues.tokens(v)) {
                if (Set.of("row", "row-reverse", "column", "column-reverse").contains(token)) {
                    out.add(new Declaration("flex-direction", token, imp));
                } else if (Set.of("nowrap", "wrap", "wrap-reverse").contains(token)) {
                    out.add(new Declaration("flex-wrap", token, imp));
                }
            }
        } else if (k.equals("flex")) {
            List<String> t = CssValues.tokens(v);
            String grow = "1", shrink = "1", basis = "0%";
            if (v.equals("none")) {
                grow = "0";
                shrink = "0";
                basis = "auto";
            } else if (v.equals("auto")) {
                basis = "auto";
            } else if (v.equals("initial")) {
                grow = "0";
                basis = "auto";
            } else if (!t.isEmpty()) {
                if (t.get(0).matches("[0-9.]+")) {
                    grow = t.get(0);
                } else {
                    basis = t.get(0);
                }
                if (t.size() > 1) {
                    if (t.get(1).matches("[0-9.]+")) {
                        shrink = t.get(1);
                    } else {
                        basis = t.get(1);
                    }
                }
                if (t.size() > 2) {
                    basis = t.get(2);
                }
            }
            out.add(new Declaration("flex-grow", grow, imp));
            out.add(new Declaration("flex-shrink", shrink, imp));
            out.add(new Declaration("flex-basis", basis, imp));
        }
    }

    private void apply(
            Map<String, List<Winner>> map,
            List<Declaration> declarations,
            long spec,
            int order,
            List<Integer> layer,
            boolean inline) {
        for (Declaration d : declarations) {
            Winner w = new Winner(d.value, d.important, spec, order, layer, inline);
            map.computeIfAbsent(d.key, key -> new ArrayList<>()).add(w);
        }
    }

    private String finish(
            Map<String, String> values,
            Map<String, String> parent,
            Map<String, List<Winner>> selected) {
        selected.forEach(
                (key, candidates) -> {
                    List<Winner> sorted = new ArrayList<>(candidates);
                    Collections.reverse(sorted);
                    sorted.sort(Comparator.reverseOrder());
                    while (!sorted.isEmpty()) {
                        Winner winner = sorted.get(0);
                        if (winner.value.equals("revert")) {
                            break;
                        }
                        if (winner.value.equals("revert-layer")) {
                            sorted.removeIf(
                                    candidate ->
                                            candidate.inline == winner.inline
                                                    && candidate.layer.equals(winner.layer));
                        } else {
                            values.put(key, winner.value);
                            break;
                        }
                    }
                });
        Map<String, String> custom = new HashMap<>();
        values.forEach(
                (k, v) -> {
                    if (k.startsWith("--")) {
                        custom.put(k, v);
                    }
                });
        for (Map.Entry<String, List<Winner>> entry : new ArrayList<>(selected.entrySet())) {
            String property = entry.getKey();
            if (!Set.of(
                            "background",
                            "font",
                            "margin",
                            "padding",
                            "border",
                            "flex",
                            "gap",
                            "grid-column",
                            "grid-row",
                            "grid-area")
                    .contains(property)) {
                continue;
            }
            Winner winner = entry.getValue().stream().max(Comparator.naturalOrder()).orElseThrow();
            if (!winner.value.contains("var(")) {
                continue;
            }
            String resolved = variables(winner.value, custom, new HashSet<>(), 0);
            if (resolved.isBlank()) {
                continue;
            }
            List<Declaration> expanded = new ArrayList<>();
            expand(expanded, property, resolved, winner.important);
            for (Declaration declaration : expanded) {
                Winner current =
                        selected.getOrDefault(declaration.key, List.of()).stream()
                                .max(Comparator.naturalOrder())
                                .orElse(null);
                if (current == null || winner.compareTo(current) >= 0) {
                    values.put(declaration.key, declaration.value);
                }
            }
        }
        for (String k : new ArrayList<>(values.keySet())) {
            String v = values.get(k);
            if (!k.startsWith("--")) {
                v = variables(v, custom, new HashSet<>(), 0);
            }
            if (v.equals("inherit")
                    || (v.equals("unset")
                            && (INHERITED.contains(k)
                                    || SVG_PROPERTIES.contains(k)
                                    || k.startsWith("--")))) {
                if (parent.containsKey(k)) {
                    values.put(k, parent.get(k));
                } else {
                    values.remove(k);
                }
            } else if (v.equals("initial") || v.equals("unset") || v.isEmpty()) {
                values.remove(k);
            } else {
                values.put(k, v);
            }
        }
        String fs = values.getOrDefault("font-size", "12pt");
        double parentSize =
                CssValues.length(
                        parent.get("font-size"),
                        12,
                        12,
                        12,
                        config.pageWidth,
                        config.pageHeight,
                        12);
        double size =
                CssValues.length(
                        fs,
                        parentSize,
                        parentSize,
                        rootFontSize,
                        config.pageWidth,
                        config.pageHeight,
                        parentSize);
        values.put("font-size", size + "pt");
        return fs;
    }

    private String variables(String s, Map<String, String> vars, Set<String> visiting, int depth) {
        if (depth > 32) {
            return "";
        }
        int a = s.indexOf("var(");
        if (a < 0) {
            return s;
        }
        int b = a + 4, n = 1;
        for (; b < s.length() && n > 0; b++) {
            if (s.charAt(b) == '(') {
                n++;
            }
            if (s.charAt(b) == ')') {
                n--;
            }
        }
        if (n != 0) {
            return "";
        }
        String args = s.substring(a + 4, b - 1);
        int comma = args.indexOf(',');
        String name = (comma < 0 ? args : args.substring(0, comma)).trim();
        String value = "";
        if (visiting.add(name)) {
            if (vars.containsKey(name)) {
                value = variables(vars.get(name), vars, visiting, depth + 1);
            }
            visiting.remove(name);
        }
        if (value.isEmpty() && comma >= 0) {
            value = variables(args.substring(comma + 1).trim(), vars, visiting, depth + 1);
        }
        if (value.isEmpty()) {
            return "";
        }
        return variables(s.substring(0, a) + value + s.substring(b), vars, visiting, depth + 1);
    }

    public static int weight(String s) {
        if (s.equals("bold") || s.equals("bolder")) {
            return 700;
        }
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return 400;
        }
    }

    private boolean svgNode(HtmlNode node) {
        for (HtmlNode ancestor = node; ancestor != null; ancestor = ancestor.parent) {
            if (ancestor.tag.equals("svg")) {
                return true;
            }
        }
        return false;
    }

    private boolean uaOverride(HtmlNode n, String property) {
        if (svgNode(n) && !n.attr(property).isBlank()) {
            return true;
        }

        return switch (property) {
            case "font-size" -> n.tag.matches("h[1-6]");
            case "font-weight" ->
                    n.tag.matches("h[1-6]") || Set.of("strong", "b", "th").contains(n.tag);
            case "font-style" -> Set.of("em", "i", "cite").contains(n.tag);
            case "white-space" -> n.tag.equals("pre");
            case "text-align" -> n.tag.equals("th");
            case "color" -> n.tag.equals("a");
            default -> false;
        };
    }

    public static long specificity(String selector) {
        long score = 0;
        boolean typeAllowed = true;
        for (int i = 0; i < selector.length(); ) {
            char c = selector.charAt(i);
            if (Character.isWhitespace(c) || c == '>' || c == '+' || c == '~' || c == ',') {
                typeAllowed = true;
                i++;
                continue;
            }
            if (c == '[') {
                score += 1_000_000L;
                int depth = 1;
                char quote = 0;
                i++;
                while (i < selector.length() && depth > 0) {
                    char v = selector.charAt(i++);
                    if (v == '\\') {
                        i = Math.min(selector.length(), i + 1);
                        continue;
                    }
                    if (quote != 0) {
                        if (v == quote) {
                            quote = 0;
                        }
                    } else if (v == '\'' || v == '"') {
                        quote = v;
                    } else if (v == '[') {
                        depth++;
                    } else if (v == ']') {
                        depth--;
                    }
                }
                typeAllowed = false;
                continue;
            }
            if (c == '#' || c == '.') {
                score += c == '#' ? 1_000_000_000_000L : 1_000_000L;
                i = identifierEnd(selector, i + 1);
                typeAllowed = false;
                continue;
            }
            if (c == ':') {
                boolean element = i + 1 < selector.length() && selector.charAt(i + 1) == ':';
                i += element ? 2 : 1;
                int end = identifierEnd(selector, i);
                String name = selector.substring(i, end).toLowerCase(Locale.ROOT);
                i = end;
                boolean functional = i < selector.length() && selector.charAt(i) == '(';
                boolean max = Set.of("is", "not", "has").contains(name),
                        zero = name.equals("where");
                if (!max && !zero) {
                    score +=
                            element
                                            || Set.of(
                                                            "before",
                                                            "after",
                                                            "first-line",
                                                            "first-letter")
                                                    .contains(name)
                                    ? 1
                                    : 1_000_000L;
                }
                if (functional) {
                    int start = ++i, depth = 1;
                    char quote = 0;
                    for (; i < selector.length() && depth > 0; i++) {
                        char v = selector.charAt(i);
                        if (v == '\\') {
                            i++;
                            continue;
                        }
                        if (quote != 0) {
                            if (v == quote) {
                                quote = 0;
                            }
                        } else if (v == '\'' || v == '"') {
                            quote = v;
                        } else if (v == '(') {
                            depth++;
                        } else if (v == ')') {
                            depth--;
                        }
                    }
                    if (max) {
                        String args = selector.substring(start, Math.max(start, i - 1));
                        long best = 0;
                        int nested = 0, offset = 0;
                        for (int j = 0; j <= args.length(); j++) {
                            char v = j < args.length() ? args.charAt(j) : ',';
                            if (v == '(' || v == '[') {
                                nested++;
                            }
                            if (v == ')' || v == ']') {
                                nested--;
                            }
                            if (v == ',' && nested == 0) {
                                best = Math.max(best, specificity(args.substring(offset, j)));
                                offset = j + 1;
                            }
                        }
                        score += best;
                    }
                }
                typeAllowed = false;
                continue;
            }
            if (c == '*') {
                i++;
                typeAllowed = false;
                continue;
            }
            if (Character.isLetter(c) || c == '_' || c == '-' || c == '\\') {
                if (typeAllowed) {
                    score++;
                }
                i = identifierEnd(selector, i);
                typeAllowed = false;
            } else {
                i++;
            }
        }
        return score;
    }

    private static int identifierEnd(String s, int at) {
        int i = at;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\') {
                i = Math.min(s.length(), i + 2);
            } else if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c > 127) {
                i++;
            } else {
                break;
            }
        }
        return i;
    }

    private Map<String, String> defaults(HtmlNode n) {
        Map<String, String> v = new HashMap<>();
        v.put("font-family", config.defaultFont);
        v.put("font-size", "12pt");
        if (n.tag.equals("table")) {
            v.put("border-spacing", "2px");
        }
        v.put("color", "#000000");
        v.put(
                "display",
                switch (n.tag) {
                    case "head", "script", "style", "link", "meta", "title", "template" -> "none";
                    case "html",
                            "body",
                            "div",
                            "main",
                            "section",
                            "article",
                            "header",
                            "footer",
                            "nav",
                            "aside",
                            "p",
                            "h1",
                            "h2",
                            "h3",
                            "h4",
                            "h5",
                            "h6",
                            "ul",
                            "ol",
                            "dl",
                            "dt",
                            "dd",
                            "li",
                            "pre",
                            "blockquote",
                            "figure",
                            "figcaption",
                            "hr" ->
                            "block";
                    case "table" -> "table";
                    case "tr" -> "table-row";
                    case "td", "th" -> "table-cell";
                    case "thead" -> "table-header-group";
                    case "tbody" -> "table-row-group";
                    case "tfoot" -> "table-footer-group";
                    case "caption" -> "table-caption";
                    default -> "inline";
                });
        v.put("line-height", "normal");
        if (n.tag.equals("body")) {
            v.put("margin", "6pt");
        }
        if (Set.of("p", "ul", "ol", "pre", "blockquote", "figure", "dl").contains(n.tag)) {
            v.put("margin-top", "1em");
            v.put("margin-bottom", "1em");
        }
        if (Set.of("ul", "ol", "dd").contains(n.tag)) {
            v.put("padding-left", "30pt");
        }
        if (n.tag.matches("h[1-6]")) {
            v.put(
                    "font-size",
                    switch (n.tag) {
                        case "h1" -> "2em";
                        case "h2" -> "1.5em";
                        case "h3" -> "1.17em";
                        default -> "1em";
                    });
            v.put("font-weight", "700");
            v.put("margin-top", ".67em");
            v.put("margin-bottom", ".67em");
        }
        if (Set.of("strong", "b", "th").contains(n.tag)) {
            v.put("font-weight", "700");
        }
        if (Set.of("em", "i", "cite").contains(n.tag)) {
            v.put("font-style", "italic");
        }
        if (n.tag.equals("pre")) {
            v.put("white-space", "pre");
        }
        if (n.tag.equals("a")) {
            v.put("color", "#0000ee");
            v.put("text-decoration", "underline");
        }
        if (n.tag.equals("th")) {
            v.put("text-align", "center");
        }
        if (n.tag.equals("hr")) {
            v.put("border-top-width", "1pt");
            v.put("border-top-style", "solid");
        }
        return v;
    }
}
