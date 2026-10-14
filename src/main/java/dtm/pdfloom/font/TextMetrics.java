package dtm.pdfloom.font;

import dtm.pdfloom.style.ComputedStyle;

public interface TextMetrics {
    record FontRun(String text, Font font) {
    }

    default java.util.List<FontRun> runs(ComputedStyle style, String text) {
        Font font = font(style, text);
        return java.util.List.of(new FontRun(supportedText(text, font), font));
    }

    record Font(String key, double size, double ascent, double descent) {
    }

    record Bounds(double ascent, double descent) {
    }

    Font font(ComputedStyle style);

    default Font font(ComputedStyle style, String text) {
        return font(style);
    }

    String supportedText(String text, Font font);

    double width(String text, Font font);

    default Bounds bounds(String text, Font font) {
        return new Bounds(font.ascent(), font.descent());
    }
}
