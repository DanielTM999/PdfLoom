package dtm.pdfloom.internal.pdfbox;

import dtm.pdfloom.css.CssValues;
import dtm.pdfloom.exception.PdfConversionException;
import dtm.pdfloom.render.CssColor;
import dtm.pdfloom.render.Paint;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

final class ShadowPainter {
    record Raster(BufferedImage image, double x, double y, double width, double height) {
    }

    private record Shadow(
            double x, double y, double blur, double spread, Color color, boolean inset) {
            }

    private ShadowPainter() {
    }

    static List<Raster> box(Paint.Rectangle rectangle, boolean inset) {
        List<Raster> result = new ArrayList<>();
        List<Shadow> shadows = parse(rectangle.shadow(), false);
        for (int index = shadows.size() - 1; index >= 0; index--) {
            Shadow shadow = shadows.get(index);
            if (shadow.inset != inset) {
                continue;
            }
            Shape original =
                    new RoundRectangle2D.Double(
                            rectangle.x(),
                            rectangle.y(),
                            rectangle.width(),
                            rectangle.height(),
                            rectangle.radius() * 2,
                            rectangle.radius() * 2);
            double spread = inset ? -shadow.spread : shadow.spread;
            Shape expanded =
                    new RoundRectangle2D.Double(
                            rectangle.x() - spread + shadow.x,
                            rectangle.y() - spread + shadow.y,
                            Math.max(0, rectangle.width() + 2 * spread),
                            Math.max(0, rectangle.height() + 2 * spread),
                            Math.max(0, rectangle.radius() + spread) * 2,
                            Math.max(0, rectangle.radius() + spread) * 2);
            result.add(raster(original, expanded, shadow, true));
        }
        return result;
    }

    static List<Raster> text(Paint.Text text, String value, PdfFontRegistry fonts) {
        List<Raster> result = new ArrayList<>();
        List<Shadow> shadows = parse(text.shadow(), true);
        Area glyphs = new Area();
        for (dtm.pdfloom.font.ComplexText.Run run : fonts.shape(value, text.font(), text.size())) {
            AffineTransform position =
                    AffineTransform.getTranslateInstance(
                            text.x() + run.x(), text.y() + text.ascent());
            glyphs.add(new Area(position.createTransformedShape(run.glyphs().getOutline())));
        }
        for (int index = shadows.size() - 1; index >= 0; index--) {
            Shadow shadow = shadows.get(index);
            Shape shifted =
                    AffineTransform.getTranslateInstance(shadow.x, shadow.y)
                            .createTransformedShape(glyphs);
            result.add(raster(glyphs, shifted, shadow, false));
        }
        return result;
    }

    private static List<Shadow> parse(String value, boolean text) {
        List<Shadow> shadows = new ArrayList<>();
        if (value == null || value.isBlank() || value.equals("none")) {
            return shadows;
        }
        for (String expression : CssValues.commaSeparated(value)) {
            List<Double> lengths = new ArrayList<>();
            Color color = Color.BLACK;
            boolean inset = false;
            for (String token : CssValues.tokens(expression)) {
                if (token.equals("inset")) {
                    inset = true;
                } else if (token.endsWith("pt")) {
                    lengths.add(Double.parseDouble(token.substring(0, token.length() - 2)));
                } else {
                    color = CssColor.parse(token);
                }
            }
            if (lengths.size() < 2
                    || lengths.size() > (text ? 3 : 4)
                    || text && inset
                    || lengths.size() > 2 && lengths.get(2) < 0) {
                throw new PdfConversionException("Invalid CSS shadow: " + expression);
            }
            shadows.add(
                    new Shadow(
                            lengths.get(0),
                            lengths.get(1),
                            lengths.size() > 2 ? lengths.get(2) : 0,
                            lengths.size() > 3 ? lengths.get(3) : 0,
                            color,
                            inset));
        }
        return shadows;
    }

    private static Raster raster(Shape original, Shape shifted, Shadow shadow, boolean box) {
        double pad = Math.max(2, shadow.blur * 2);
        Rectangle2D bounds = shifted.getBounds2D().createUnion(original.getBounds2D());
        double x = bounds.getMinX() - pad;
        double y = bounds.getMinY() - pad;
        double width = Math.max(1, bounds.getWidth() + pad * 2);
        double height = Math.max(1, bounds.getHeight() + pad * 2);
        int w = (int) Math.ceil(width * 2);
        int h = (int) Math.ceil(height * 2);
        if (w < 1 || h < 1 || (long) w * h > 16_000_000) {
            throw new PdfConversionException("Shadow raster pixel limit exceeded");
        }
        BufferedImage mask = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = mask.createGraphics();
        graphics.scale(w / width, h / height);
        graphics.translate(-x, -y);
        graphics.setRenderingHint(
                java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setColor(Color.WHITE);
        if (shadow.inset) {
            graphics.fill(new Rectangle2D.Double(x, y, width, height));
            graphics.setComposite(java.awt.AlphaComposite.Clear);
        }
        graphics.fill(shifted);
        graphics.dispose();
        float[] alpha = new float[w * h];
        for (int row = 0; row < h; row++) {
            for (int column = 0; column < w; column++) {
                alpha[row * w + column] = (mask.getRGB(column, row) >>> 24) / 255f;
            }
        }
        if (shadow.blur > 0) {
            alpha = blur(alpha, w, h, shadow.blur);
        }
        for (int row = 0; row < h; row++) {
            for (int column = 0; column < w; column++) {
                double px = x + (column + .5) * width / w;
                double py = y + (row + .5) * height / h;
                boolean inside = original.contains(px, py);
                float opacity =
                        box && (shadow.inset ? !inside : inside) ? 0 : alpha[row * w + column];
                int a = Math.round(opacity * shadow.color.getAlpha());
                mask.setRGB(column, row, a << 24 | shadow.color.getRGB() & 0xffffff);
            }
        }
        return new Raster(mask, x, y, width, height);
    }

    private static float[] blur(float[] source, int width, int height, double sigma) {
        int radius = Math.max(1, (int) Math.ceil(sigma * 3));
        if (radius > 512) {
            throw new PdfConversionException("Shadow blur limit exceeded");
        }
        float[] kernel = new float[radius * 2 + 1];
        double sum = 0;
        for (int index = -radius; index <= radius; index++) {
            double weight = Math.exp(-index * index / (2 * sigma * sigma));
            kernel[index + radius] = (float) weight;
            sum += weight;
        }
        for (int index = 0; index < kernel.length; index++) {
            kernel[index] /= (float) sum;
        }
        float[] intermediate = new float[source.length];
        float[] result = new float[source.length];
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                float value = 0;
                for (int offset = -radius; offset <= radius; offset++) {
                    int sample = column + offset;
                    if (sample >= 0 && sample < width) {
                        value += source[row * width + sample] * kernel[offset + radius];
                    }
                }
                intermediate[row * width + column] = value;
            }
        }
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                float value = 0;
                for (int offset = -radius; offset <= radius; offset++) {
                    int sample = row + offset;
                    if (sample >= 0 && sample < height) {
                        value += intermediate[sample * width + column] * kernel[offset + radius];
                    }
                }
                result[row * width + column] = value;
            }
        }
        return result;
    }
}
