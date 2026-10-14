package dtm.pdfloom.render;

import java.awt.Color;
import java.util.*;

public final class CssColor {
    private static final Map<String, String> NAMES =
            Map.ofEntries(
                    Map.entry("black", "#000000"),
                    Map.entry("white", "#ffffff"),
                    Map.entry("red", "#ff0000"),
                    Map.entry("green", "#008000"),
                    Map.entry("blue", "#0000ff"),
                    Map.entry("gray", "#808080"),
                    Map.entry("grey", "#808080"),
                    Map.entry("silver", "#c0c0c0"),
                    Map.entry("yellow", "#ffff00"),
                    Map.entry("orange", "#ffa500"),
                    Map.entry("navy", "#000080"),
                    Map.entry("teal", "#008080"),
                    Map.entry("purple", "#800080"),
                    Map.entry("maroon", "#800000"),
                    Map.entry("lime", "#00ff00"),
                    Map.entry("aqua", "#00ffff"),
                    Map.entry("fuchsia", "#ff00ff"));

    private CssColor() {
    }

    public static Color parse(String value) {
        String v = value.trim().toLowerCase(Locale.ROOT);
        if (v.equals("transparent") || v.isEmpty() || v.equals("none")) {
            return new Color(0, 0, 0, 0);
        }
        v = NAMES.getOrDefault(v, v);
        com.helger.css.utils.ECSSColor named =
                com.helger.css.utils.ECSSColor.getFromNameCaseInsensitiveOrNull(v);
        if (named != null) {
            return new Color(named.getRed(), named.getGreen(), named.getBlue());
        }
        try {
            if (v.startsWith("#")) {
                String h = v.substring(1);
                if (h.length() == 3 || h.length() == 4) {
                    StringBuilder b = new StringBuilder();
                    for (char c : h.toCharArray()) {
                        b.append(c).append(c);
                    }
                    h = b.toString();
                }
                if (h.length() == 8) {
                    return new Color(
                            Integer.parseInt(h.substring(0, 2), 16),
                            Integer.parseInt(h.substring(2, 4), 16),
                            Integer.parseInt(h.substring(4, 6), 16),
                            Integer.parseInt(h.substring(6, 8), 16));
                }
                return Color.decode("#" + h);
            }
            if (v.startsWith("rgb")) {
                String[] p = v.substring(v.indexOf('(') + 1, v.lastIndexOf(')')).split("[, /]+");
                int[] c = new int[3];
                for (int i = 0; i < 3; i++) {
                    c[i] =
                            Math.max(
                                    0,
                                    Math.min(
                                            255,
                                            (int)
                                                    Math.round(
                                                            p[i].endsWith("%")
                                                                    ? Double.parseDouble(
                                                                                    p[i].replace(
                                                                                            "%",
                                                                                            ""))
                                                                            * 2.55
                                                                    : Double.parseDouble(p[i]))));
                }
                float alpha =
                        p.length > 3
                                ? p[3].endsWith("%")
                                        ? Float.parseFloat(p[3].substring(0, p[3].length() - 1))
                                                / 100
                                        : Float.parseFloat(p[3])
                                : 1;
                return new Color(
                        c[0], c[1], c[2], Math.max(0, Math.min(255, Math.round(alpha * 255))));
            }
            if (v.startsWith("hsl")) {
                String[] parts =
                        v.substring(v.indexOf('(') + 1, v.lastIndexOf(')')).split("[, /]+");
                String angle = parts[0];
                double hue =
                        angle.endsWith("turn")
                                ? Double.parseDouble(angle.substring(0, angle.length() - 4)) * 360
                                : angle.endsWith("rad")
                                        ? Math.toDegrees(
                                                Double.parseDouble(
                                                        angle.substring(0, angle.length() - 3)))
                                        : angle.endsWith("grad")
                                                ? Double.parseDouble(
                                                                angle.substring(
                                                                        0, angle.length() - 4))
                                                        * .9
                                                : Double.parseDouble(angle.replace("deg", ""));
                double saturation =
                        Math.max(
                                0,
                                Math.min(1, Double.parseDouble(parts[1].replace("%", "")) / 100));
                double lightness =
                        Math.max(
                                0,
                                Math.min(1, Double.parseDouble(parts[2].replace("%", "")) / 100));
                float alpha =
                        parts.length > 3
                                ? parts[3].endsWith("%")
                                        ? Float.parseFloat(parts[3].replace("%", "")) / 100
                                        : Float.parseFloat(parts[3])
                                : 1;
                hue = (hue % 360 + 360) % 360 / 60;
                double chroma = (1 - Math.abs(2 * lightness - 1)) * saturation;
                double secondary = chroma * (1 - Math.abs(hue % 2 - 1));
                double offset = lightness - chroma / 2;
                double[] rgb =
                        hue < 1
                                ? new double[] {chroma, secondary, 0}
                                : hue < 2
                                        ? new double[] {secondary, chroma, 0}
                                        : hue < 3
                                                ? new double[] {0, chroma, secondary}
                                                : hue < 4
                                                        ? new double[] {0, secondary, chroma}
                                                        : hue < 5
                                                                ? new double[] {
                                                                    secondary, 0, chroma
                                                                }
                                                                : new double[] {
                                                                    chroma, 0, secondary
                                                                };
                return new Color(
                        (int) Math.round((rgb[0] + offset) * 255),
                        (int) Math.round((rgb[1] + offset) * 255),
                        (int) Math.round((rgb[2] + offset) * 255),
                        Math.max(0, Math.min(255, Math.round(alpha * 255))));
            }
        } catch (Exception ignored) {
        }
        return Color.BLACK;
    }

    public static boolean recognized(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).strip();
        return normalized.equals("transparent")
                || normalized.equals("currentcolor")
                || normalized.matches("#[0-9a-f]{3}(?:[0-9a-f]|[0-9a-f]{3}|[0-9a-f]{5})?")
                || com.helger.css.utils.ECSSColor.getFromNameCaseInsensitiveOrNull(normalized)
                        != null
                || normalized.matches("(?:rgb|rgba|hsl|hsla)\\([^()]+\\)");
    }
}
