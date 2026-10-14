package dtm.pdfloom.font;

import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.text.Bidi;
import java.util.ArrayList;
import java.util.List;

public final class ComplexText {
    private static final FontRenderContext CONTEXT =
            new FontRenderContext(new AffineTransform(), true, true);

    public record Run(GlyphVector glyphs, double x) {
    }

    private ComplexText() {
    }

    public static boolean requiresShaping(String text) {
        for (int codePoint : text.codePoints().toArray()) {
            int type = Character.getType(codePoint);
            Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
            if (type == Character.NON_SPACING_MARK
                    || type == Character.COMBINING_SPACING_MARK
                    || script == Character.UnicodeScript.ARABIC
                    || script == Character.UnicodeScript.HEBREW
                    || script == Character.UnicodeScript.DEVANAGARI
                    || script == Character.UnicodeScript.BENGALI
                    || script == Character.UnicodeScript.THAI
                    || script == Character.UnicodeScript.GUJARATI
                    || codePoint == 0x200D
                    || codePoint == 0x200C) {
                return true;
            }
        }
        return false;
    }

    public static List<Run> shape(String text, Font font) {
        Bidi bidi = new Bidi(text, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT);
        int count = bidi.getRunCount();
        byte[] levels = new byte[count];
        Integer[] order = new Integer[count];
        for (int index = 0; index < count; index++) {
            levels[index] = (byte) bidi.getRunLevel(index);
            order[index] = index;
        }
        Bidi.reorderVisually(levels, 0, order, 0, count);
        char[] characters = text.toCharArray();
        List<Run> result = new ArrayList<>();
        double x = 0;
        for (int index : order) {
            GlyphVector vector =
                    font.layoutGlyphVector(
                            CONTEXT,
                            characters,
                            bidi.getRunStart(index),
                            bidi.getRunLimit(index),
                            levels[index] % 2 == 0
                                    ? Font.LAYOUT_LEFT_TO_RIGHT
                                    : Font.LAYOUT_RIGHT_TO_LEFT);
            result.add(new Run(vector, x));
            x += vector.getGlyphPosition(vector.getNumGlyphs()).getX();
        }
        return List.copyOf(result);
    }

    public static double width(List<Run> runs) {
        if (runs.isEmpty()) {
            return 0;
        }
        Run last = runs.get(runs.size() - 1);
        return last.x() + last.glyphs().getGlyphPosition(last.glyphs().getNumGlyphs()).getX();
    }
}
