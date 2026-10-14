package dtm.pdfloom.image;

import dtm.pdfloom.font.FontData;

import org.apache.batik.bridge.FontFace;
import org.apache.batik.bridge.FontFamilyResolver;
import org.apache.batik.gvt.font.AWTFontFamily;
import org.apache.batik.gvt.font.GVTFontFace;
import org.apache.batik.gvt.font.GVTFontFamily;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class SvgFontResolver implements FontFamilyResolver {
    private record Face(String family, int weight, boolean italic, java.awt.Font font) {
    }

    private final List<Face> faces = new ArrayList<>();

    SvgFontResolver(List<FontData> fonts) throws Exception {
        if (fonts.isEmpty()) {
            try (InputStream input =
                    SvgFontResolver.class.getResourceAsStream(
                            "/dtm/pdfloom/fonts/liberation/LiberationSans-Regular.ttf")) {
                if (input == null) {
                    throw new java.io.IOException("SVG default font missing");
                }
                faces.add(
                        new Face(
                                "liberation sans",
                                400,
                                false,
                                java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, input)));
            }
        } else {
            for (FontData data : fonts) {
                faces.add(
                        new Face(
                                data.family().toLowerCase(Locale.ROOT),
                                data.weight(),
                                data.italic(),
                                java.awt.Font.createFont(
                                        java.awt.Font.TRUETYPE_FONT,
                                        new ByteArrayInputStream(data.sfnt()))));
            }
        }
    }

    @Override
    public GVTFontFamily resolve(String name) {
        return resolve(name, null);
    }

    @Override
    public GVTFontFamily resolve(String name, FontFace fontFace) {
        String family =
                switch (name.toLowerCase(Locale.ROOT)) {
                    case "arial", "helvetica", "sans-serif" -> "liberation sans";
                    case "times", "times new roman", "serif" -> "liberation serif";
                    case "courier", "courier new", "monospace" -> "liberation mono";
                    default -> name.toLowerCase(Locale.ROOT);
                };
        int weight =
                fontFace == null
                        ? 400
                        : dtm.pdfloom.css.CascadeStyleResolver.weight(fontFace.getFontWeight());
        boolean italic = fontFace != null && !fontFace.getFontStyle().equals("normal");
        Face selected =
                faces.stream()
                        .filter(face -> face.family.equals(family))
                        .min(
                                Comparator.comparingInt(
                                        face ->
                                                Math.abs(face.weight - weight)
                                                        + (face.italic == italic ? 0 : 1000)))
                        .orElse(null);
        return selected == null
                ? null
                : new AWTFontFamily(new GVTFontFace(selected.family), selected.font);
    }

    @Override
    public GVTFontFamily loadFont(InputStream source, FontFace face) throws Exception {
        return new AWTFontFamily(
                face, java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, source));
    }

    @Override
    public GVTFontFamily getDefault() {
        GVTFontFamily font = resolve("liberation sans");
        Face fallback = faces.get(0);
        return font == null
                ? new AWTFontFamily(new GVTFontFace(fallback.family), fallback.font)
                : font;
    }

    @Override
    public GVTFontFamily getFamilyThatCanDisplay(char character) {
        return faces.stream()
                .filter(face -> face.font.canDisplay(character))
                .findFirst()
                .map(
                        face ->
                                (GVTFontFamily)
                                        new AWTFontFamily(new GVTFontFace(face.family), face.font))
                .orElse(null);
    }
}
