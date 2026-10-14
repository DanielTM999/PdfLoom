package dtm.pdfloom.internal.pdfbox;

import dtm.pdfloom.config.*;
import dtm.pdfloom.css.*;
import dtm.pdfloom.exception.PdfConversionException;
import dtm.pdfloom.font.TextMetrics;
import dtm.pdfloom.resource.*;
import dtm.pdfloom.style.*;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.*;

import java.io.*;
import java.net.URI;
import java.util.*;

public final class PdfFontRegistry implements TextMetrics {
    private record Face(String family, int weight, boolean italic, String key) {
    }

    private final Map<String, PDType0Font> fonts = new HashMap<>();
    private final Map<String, java.awt.Font> shapingFonts = new HashMap<>();
    private final Set<String> outlinedFonts = new HashSet<>();
    private final Map<String, LogicalUnicodeFont> logicalFonts = new HashMap<>();
    private final List<Face> faces = new ArrayList<>();
    private final List<dtm.pdfloom.font.FontData> fontData = new ArrayList<>();
    private final Set<Integer> missing = new HashSet<>();
    private final Map<ComputedStyle, Font> selected = new IdentityHashMap<>();
    private final PdfConversionConfig config;
    private final PDDocument document;

    public PdfFontRegistry(
            PDDocument doc,
            PdfConversionConfig config,
            ResourceLoader loader,
            StyleSheet css,
            URI origin) {
        this.config = config;
        document = doc;
        for (int weight : List.of(400, 700)) {
            for (boolean italic : List.of(false, true)) {
                String variant =
                        weight == 700
                                ? (italic ? "BoldItalic" : "Bold")
                                : (italic ? "Italic" : "Regular");
                try (InputStream in =
                        PdfFontRegistry.class.getResourceAsStream(
                                "/dtm/pdfloom/fonts/Roboto-" + variant + ".ttf")) {
                    if (in == null) {
                        throw new IOException("Bundled font missing");
                    }
                    add("Roboto", weight, italic, in);
                } catch (IOException e) {
                    throw new PdfConversionException("Cannot load bundled font", e);
                }
            }
        }
        for (String family : List.of("Liberation Sans", "Liberation Mono", "Liberation Serif")) {
            for (int weight : List.of(400, 700)) {
                for (boolean italic : List.of(false, true)) {
                    String variant =
                            weight == 700
                                    ? (italic ? "BoldItalic" : "Bold")
                                    : (italic ? "Italic" : "Regular");
                    String file = family.replace(" ", "") + "-" + variant + ".ttf";
                    try (InputStream source =
                            PdfFontRegistry.class.getResourceAsStream(
                                    "/dtm/pdfloom/fonts/liberation/" + file)) {
                        if (source == null) {
                            throw new IOException("Bundled font missing: " + file);
                        }
                        add(family, weight, italic, source);
                    } catch (IOException exception) {
                        throw new PdfConversionException(
                                "Cannot load bundled font: " + file, exception);
                    }
                }
            }
        }
        for (PdfConversionConfig.FontSource f : config.fonts) {
            ResourceLoader.Data d = loader.load(f.reference(), origin);
            if (d != null) {
                addData(f.family(), f.weight(), f.italic(), d.bytes());
            }
        }
        for (StyleSheet.FontFace f : css.fonts) {
            ResourceLoader.Data d = loader.load(f.reference(), f.origin());
            if (d != null) {
                addData(f.family(), f.weight(), f.italic(), d.bytes());
            }
        }
        resolveFontSizes(css);
    }

    private void resolveFontSizes(StyleSheet styles) {
        for (dtm.pdfloom.html.HtmlNode node : styles.elements) {
            ComputedStyle current = styles.style(node);
            ComputedStyle parent = node.parent == null ? current : styles.style(node.parent);
            Font reference = font(parent == null ? current : parent);
            String expression =
                    styles.fontExpressions.getOrDefault(node, current.get("font-size", "12pt"));
            double root = node.tag.equals("html") ? 12 : styles.rootFontSize;
            Bounds x = bounds("x", reference);
            double size =
                    expression.equals("inherit")
                            ? reference.size()
                            : CssValues.length(
                                    expression,
                                    new CssNumericValue.Context(
                                            reference.size(),
                                            reference.size(),
                                            root,
                                            config.pageWidth,
                                            config.pageHeight,
                                            x.ascent() + x.descent(),
                                            width("0", reference)),
                                    reference.size());
            Map<String, String> values = new HashMap<>(current.values());
            values.put("font-size", size + "pt");
            styles.styles.put(node, new ComputedStyle(values));
            if (node.tag.equals("html")) {
                styles.rootFontSize = size;
            }
        }
        selected.clear();
    }

    private void addData(String family, int weight, boolean italic, byte[] bytes) {
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            add(family, weight, italic, in);
        } catch (IOException | UnsupportedOperationException e) {
            throw new PdfConversionException(
                    "Cannot embed font " + family + " (TrueType outlines required)", e);
        }
    }

    private void add(String family, int weight, boolean italic, InputStream in) throws IOException {
        String key =
                family.toLowerCase(Locale.ROOT) + ":" + weight + ":" + italic + ":" + faces.size();
        byte[] bytes =
                dtm.pdfloom.font.WoffDecoder.decode(in.readAllBytes(), config.maxResourceBytes);
        boolean cff = bytes.length >= 4 && java.nio.ByteBuffer.wrap(bytes).getInt() == 0x4f54544f;
        if (cff) {
            PDType0Font logical =
                    faces.stream()
                            .filter(face -> face.family.equals("roboto"))
                            .map(face -> fonts.get(face.key))
                            .findFirst()
                            .orElseThrow(() -> new IOException("Logical text font missing"));
            fonts.put(key, logical);
            outlinedFonts.add(key);
        } else {
            fonts.put(key, PDType0Font.load(document, new ByteArrayInputStream(bytes), true));
        }
        try {
            shapingFonts.put(
                    key,
                    java.awt.Font.createFont(
                            java.awt.Font.TRUETYPE_FONT, new ByteArrayInputStream(bytes)));
        } catch (java.awt.FontFormatException exception) {
            throw new IOException("Cannot load font for shaping", exception);
        }
        faces.add(new Face(family.toLowerCase(Locale.ROOT), weight, italic, key));
        fontData.add(new dtm.pdfloom.font.FontData(family, weight, italic, bytes));
    }

    @Override
    public Font font(ComputedStyle style) {
        Font cached = selected.get(style);
        if (cached != null) {
            return cached;
        }
        int weight = CascadeStyleResolver.weight(style.get("font-weight", "400"));
        boolean italic = style.is("font-style", "italic") || style.is("font-style", "oblique");
        Face best = null;
        for (String candidate : style.get("font-family", config.defaultFont).split(",")) {
            String family = CssValues.unquote(candidate.trim()).toLowerCase(Locale.ROOT);
            String requested = family;
            if (faces.stream().noneMatch(face -> face.family.equals(requested))) {
                family =
                        switch (family) {
                            case "arial", "helvetica", "sans-serif" -> "liberation sans";
                            case "times", "times new roman", "serif" -> "liberation serif";
                            case "courier", "courier new", "monospace" -> "liberation mono";
                            default -> family;
                        };
            }
            String finalFamily = family;
            best =
                    faces.stream()
                            .filter(f -> f.family.equals(finalFamily))
                            .min(
                                    Comparator.comparingInt(
                                            f ->
                                                    Math.abs(f.weight - weight)
                                                            + (f.italic == italic ? 0 : 1000)))
                            .orElse(null);
            if (best != null) {
                break;
            }
        }
        if (best == null) {
            best =
                    faces.stream()
                            .filter(f -> f.family.equals("roboto"))
                            .min(
                                    Comparator.comparingInt(
                                            f ->
                                                    Math.abs(f.weight - weight)
                                                            + (f.italic == italic ? 0 : 1000)))
                            .orElseThrow();
        }
        double size =
                CssValues.length(
                        style.get("font-size"),
                        12,
                        12,
                        12,
                        config.pageWidth,
                        config.pageHeight,
                        12);
        if (size <= 0 || size > 10000) {
            throw new PdfConversionException("Invalid font size: " + size);
        }
        String key = best.key;
        boolean kerning = style.is("font-kerning", "normal");
        boolean ligatures =
                style.get("font-variant-ligatures").contains("common-ligatures")
                        && !style.get("font-variant-ligatures").contains("no-common-ligatures");
        if (kerning || ligatures) {
            String styledKey = key + ":kern=" + kerning + ":liga=" + ligatures;
            if (!shapingFonts.containsKey(styledKey)) {
                Map<java.awt.font.TextAttribute, Object> attributes = new HashMap<>();
                if (kerning) {
                    attributes.put(
                            java.awt.font.TextAttribute.KERNING,
                            java.awt.font.TextAttribute.KERNING_ON);
                }
                if (ligatures) {
                    attributes.put(
                            java.awt.font.TextAttribute.LIGATURES,
                            java.awt.font.TextAttribute.LIGATURES_ON);
                }
                shapingFonts.put(styledKey, shapingFonts.get(key).deriveFont(attributes));
                fonts.put(styledKey, fonts.get(key));
                outlinedFonts.add(styledKey);
            }
            key = styledKey;
        }
        double a = ascent(key, size), d = descent(key, size);
        Font result = new Font(key, size, a, d);
        selected.put(style, result);
        return result;
    }

    @Override
    public String supportedText(String text, Font font) {
        StringBuilder b = new StringBuilder();
        PDFont f = fonts.get(font.key());
        text.codePoints()
                .forEach(
                        cp -> {
                            if (cp == 0xE000 || cp == 0xE001) {
                                b.appendCodePoint(cp);
                                return;
                            }
                            if (cp == '\t' || cp == '\n' || cp == '\r') {
                                b.appendCodePoint(cp);
                                return;
                            }
                            if (cp < 32) {
                                return;
                            }
                            String glyph = new String(Character.toChars(cp));
                            try {
                                if (outlinedFonts.contains(font.key())) {
                                    if (!shapingFonts.get(font.key()).canDisplay(cp)) {
                                        throw new IllegalArgumentException(
                                                "Missing outlined glyph");
                                    }
                                } else {
                                    f.encode(glyph);
                                }
                                b.append(glyph);
                            } catch (IOException | IllegalArgumentException e) {
                                if (missing.add(cp)) {
                                    config.report(
                                            new Diagnostic(
                                                    Diagnostic.Severity.WARNING,
                                                    "MISSING_GLYPH",
                                                    "U+"
                                                            + Integer.toHexString(cp)
                                                                    .toUpperCase(Locale.ROOT)
                                                            + " replaced by U+FFFD"));
                                }
                                b.append('\uFFFD');
                            }
                        });
        return b.toString();
    }

    @Override
    public Font font(ComputedStyle style, String text) {
        Font preferred = font(style);
        if (shapingFonts.get(preferred.key()).canDisplayUpTo(text) < 0) {
            return preferred;
        }
        int weight = CascadeStyleResolver.weight(style.get("font-weight", "400"));
        boolean italic = style.is("font-style", "italic") || style.is("font-style", "oblique");
        Face fallback =
                faces.stream()
                        .filter(face -> shapingFonts.get(face.key()).canDisplayUpTo(text) < 0)
                        .min(
                                Comparator.comparingInt(
                                        face ->
                                                Math.abs(face.weight() - weight)
                                                        + (face.italic() == italic ? 0 : 1000)))
                        .orElse(null);
        if (fallback == null) {
            return preferred;
        }
        return new Font(
                fallback.key(),
                preferred.size(),
                ascent(fallback.key(), preferred.size()),
                descent(fallback.key(), preferred.size()));
    }

    @Override
    public double width(String text, Font font) {
        if (outlinedFonts.contains(font.key())
                || dtm.pdfloom.font.ComplexText.requiresShaping(text)) {
            return dtm.pdfloom.font.ComplexText.width(shape(text, font.key(), font.size()));
        }
        try {
            return fonts.get(font.key()).getStringWidth(text) / 1000 * font.size();
        } catch (IOException e) {
            throw new PdfConversionException("Font measurement failed", e);
        }
    }

    @Override
    public List<FontRun> runs(ComputedStyle style, String text) {
        List<FontRun> runs = new ArrayList<>();
        java.util.regex.Matcher clusters = java.util.regex.Pattern.compile("\\X").matcher(text);
        StringBuilder pending = new StringBuilder();
        Font previous = null;
        while (clusters.find()) {
            String cluster = clusters.group();
            Font selected = font(style, cluster);
            if (previous != null && !previous.key().equals(selected.key())) {
                runs.add(new FontRun(supportedText(pending.toString(), previous), previous));
                pending.setLength(0);
            }
            previous = selected;
            pending.append(cluster);
        }
        if (previous != null) {
            runs.add(new FontRun(supportedText(pending.toString(), previous), previous));
        }
        return List.copyOf(runs);
    }

    @Override
    public Bounds bounds(String text, Font font) {
        if (outlinedFonts.contains(font.key())
                || dtm.pdfloom.font.ComplexText.requiresShaping(text)) {
            double ascent = 0;
            double descent = 0;
            for (dtm.pdfloom.font.ComplexText.Run run : shape(text, font.key(), font.size())) {
                java.awt.geom.Rectangle2D bounds = run.glyphs().getVisualBounds();
                ascent = Math.max(ascent, -bounds.getMinY());
                descent = Math.max(descent, bounds.getMaxY());
            }
            return new Bounds(ascent, descent);
        }
        try {
            org.apache.fontbox.ttf.TrueTypeFont ttf =
                    ((PDCIDFontType2) fonts.get(font.key()).getDescendantFont()).getTrueTypeFont();
            org.apache.fontbox.ttf.CmapLookup cmap = ttf.getUnicodeCmapLookup();
            double ascent = 0, descent = 0;
            for (int cp : text.codePoints().toArray()) {
                org.apache.fontbox.ttf.GlyphData glyph =
                        ttf.getGlyph().getGlyph(cmap.getGlyphId(cp));
                if (glyph != null) {
                    org.apache.fontbox.util.BoundingBox b = glyph.getBoundingBox();
                    ascent = Math.max(ascent, b.getUpperRightY());
                    descent = Math.max(descent, -b.getLowerLeftY());
                }
            }
            double scale = font.size() / ttf.getUnitsPerEm();
            return new Bounds(ascent * scale, descent * scale);
        } catch (IOException e) {
            throw new PdfConversionException("Cannot read glyph bounds", e);
        }
    }

    public List<dtm.pdfloom.font.ComplexText.Run> shape(String text, String key, double size) {
        return dtm.pdfloom.font.ComplexText.shape(
                text, shapingFonts.get(key).deriveFont((float) size));
    }

    public double ascent(String key, double size) {
        return verticalMetric(key, size, true);
    }

    public double descent(String key, double size) {
        return verticalMetric(key, size, false);
    }

    private double verticalMetric(String key, double size, boolean ascent) {
        if (outlinedFonts.contains(key)) {
            java.awt.font.LineMetrics metrics =
                    shapingFonts
                            .get(key)
                            .deriveFont((float) size)
                            .getLineMetrics(
                                    "Hg", new java.awt.font.FontRenderContext(null, true, true));
            return ascent ? metrics.getAscent() : metrics.getDescent();
        }
        try {
            org.apache.fontbox.ttf.TrueTypeFont ttf =
                    ((PDCIDFontType2) fonts.get(key).getDescendantFont()).getTrueTypeFont();
            org.apache.fontbox.ttf.OS2WindowsMetricsTable os2 = ttf.getOS2Windows();
            org.apache.fontbox.ttf.HorizontalHeaderTable header = ttf.getHorizontalHeader();
            double metric =
                    os2 != null && os2.getTypoAscender() > 0
                            ? (ascent ? os2.getTypoAscender() : -os2.getTypoDescender())
                            : (ascent ? header.getAscender() : -header.getDescender());
            return metric / ttf.getUnitsPerEm() * size;
        } catch (IOException e) {
            throw new PdfConversionException("Cannot read typographic metrics", e);
        }
    }

    public PDFont pdfFont(String key) {
        return fonts.get(key);
    }

    public boolean outlined(String key) {
        return outlinedFonts.contains(key);
    }

    public PDFont logicalFont(String key) throws IOException {
        LogicalUnicodeFont font = logicalFonts.get(key);
        if (font == null) {
            font = new LogicalUnicodeFont(document, fonts.get(key), shapingFonts.get(key));
            logicalFonts.put(key, font);
        }
        return font.font();
    }

    public String logicalEncoding(String key, String text) throws IOException {
        logicalFont(key);
        return logicalFonts.get(key).encode(text);
    }

    public void finishLogicalFonts() throws IOException {
        for (LogicalUnicodeFont font : logicalFonts.values()) {
            font.update();
        }
    }

    public List<dtm.pdfloom.font.FontData> fontData() {
        return List.copyOf(fontData);
    }
}
