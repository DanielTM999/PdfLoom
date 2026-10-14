package dtm.pdfloom.image;

import dtm.pdfloom.css.CssValues;
import dtm.pdfloom.exception.PdfConversionException;
import dtm.pdfloom.render.CssColor;
import dtm.pdfloom.style.ComputedStyle;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

public final class BackgroundPainter {
    public record Box(double x, double y, double width, double height) {
    }

    private record Stop(Color color, double position) {
    }

    private BackgroundPainter() {
    }

    public static ImageStore.Image paint(
            ComputedStyle style,
            double width,
            double height,
            double[] border,
            double[] padding,
            double fontSize,
            double rootFontSize,
            ImageStore images,
            URI origin) {
        if (width <= 0 || height <= 0) {
            return null;
        }
        String key =
                new java.util.TreeMap<>(style.values())
                        + ":"
                        + width
                        + ":"
                        + height
                        + ":"
                        + java.util.Arrays.toString(border)
                        + ":"
                        + java.util.Arrays.toString(padding)
                        + ":"
                        + fontSize
                        + ":"
                        + rootFontSize
                        + ":"
                        + origin;
        ImageStore.Image cached = images.generated(key);
        if (cached != null) {
            return cached;
        }
        int pixelsWide = Math.max(1, (int) Math.ceil(width * 2));
        int pixelsHigh = Math.max(1, (int) Math.ceil(height * 2));
        if ((long) pixelsWide * pixelsHigh > 16_000_000) {
            throw new PdfConversionException("Background raster pixel limit exceeded");
        }
        BufferedImage raster =
                new BufferedImage(pixelsWide, pixelsHigh, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = raster.createGraphics();
        graphics.scale(pixelsWide / width, pixelsHigh / height);
        graphics.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        List<String> layers = CssValues.commaSeparated(style.get("background-image", "none"));
        double radius =
                CssValues.length(
                        style.get("border-radius"),
                        width,
                        fontSize,
                        rootFontSize,
                        width,
                        height,
                        0);
        try {
            Box colorClip =
                    box(
                            layer(style, "background-clip", layers.size() - 1, "border-box"),
                            width,
                            height,
                            border,
                            padding);
            graphics.setClip(shape(colorClip, radius));
            graphics.setColor(CssColor.parse(style.get("background-color", "transparent")));
            graphics.fill(new Rectangle2D.Double(0, 0, width, height));
            for (int index = layers.size() - 1; index >= 0; index--) {
                String value = layers.get(index).strip();
                if (value.equals("none") || value.isBlank()) {
                    continue;
                }
                Box area =
                        box(
                                layer(style, "background-origin", index, "padding-box"),
                                width,
                                height,
                                border,
                                padding);
                Box clip =
                        box(
                                layer(style, "background-clip", index, "border-box"),
                                width,
                                height,
                                border,
                                padding);
                graphics.setClip(shape(clip, Math.max(0, radius - clip.x)));
                BufferedImage source;
                boolean gradient = value.contains("gradient(");
                if (gradient) {
                    source =
                            gradient(
                                    value,
                                    area.width,
                                    area.height,
                                    style.get("color", "black"),
                                    fontSize,
                                    rootFontSize);
                } else if (value.startsWith("url(") && value.endsWith(")")) {
                    String reference =
                            CssValues.unquote(value.substring(4, value.length() - 1).strip());
                    ImageStore.Image loaded = images.load(reference, origin);
                    if (loaded == null) {
                        continue;
                    }
                    source = ImageIO.read(new ByteArrayInputStream(loaded.bytes()));
                } else {
                    throw new PdfConversionException("Unsupported background image: " + value);
                }
                double naturalWidth = gradient ? area.width : source.getWidth() * .75;
                double naturalHeight = gradient ? area.height : source.getHeight() * .75;
                double[] size =
                        size(
                                layer(style, "background-size", index, "auto"),
                                naturalWidth,
                                naturalHeight,
                                area,
                                fontSize,
                                rootFontSize);
                ImagePlacement position =
                        ImagePlacement.resolve(
                                size[0],
                                size[1],
                                area.width,
                                area.height,
                                "none",
                                layer(style, "background-position", index, "0% 0%"),
                                fontSize,
                                rootFontSize,
                                width,
                                height);
                tile(
                        graphics,
                        source,
                        area,
                        position.x(),
                        position.y(),
                        size[0],
                        size[1],
                        layer(style, "background-repeat", index, "repeat"));
            }
        } catch (IOException exception) {
            throw new PdfConversionException("Background image rendering failed", exception);
        } finally {
            graphics.dispose();
        }
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(raster, "png", output);
            return images.generated(key, output.toByteArray(), pixelsWide, pixelsHigh);
        } catch (IOException exception) {
            throw new PdfConversionException("Background raster encoding failed", exception);
        }
    }

    private static java.awt.Shape shape(Box box, double radius) {
        return new RoundRectangle2D.Double(
                box.x, box.y, box.width, box.height, radius * 2, radius * 2);
    }

    private static Box box(
            String value, double width, double height, double[] border, double[] padding) {
        if (value.equals("border-box")) {
            return new Box(0, 0, width, height);
        }
        double top = border[0];
        double right = border[1];
        double bottom = border[2];
        double left = border[3];
        if (value.equals("content-box")) {
            top += padding[0];
            right += padding[1];
            bottom += padding[2];
            left += padding[3];
        }
        return new Box(
                left, top, Math.max(0, width - left - right), Math.max(0, height - top - bottom));
    }

    private static String layer(ComputedStyle style, String property, int index, String fallback) {
        List<String> values = CssValues.commaSeparated(style.get(property, fallback));
        return values.get(Math.floorMod(index, values.size()));
    }

    private static double[] size(
            String value,
            double naturalWidth,
            double naturalHeight,
            Box area,
            double em,
            double rem) {
        if (value.equals("contain") || value.equals("cover")) {
            ImagePlacement size =
                    ImagePlacement.resolve(
                            naturalWidth, naturalHeight, area.width, area.height, value, "0% 0%");
            return new double[] {size.width(), size.height()};
        }
        List<String> tokens = CssValues.tokens(value);
        double width =
                CssValues.length(
                        tokens.get(0), area.width, em, rem, area.width, area.height, Double.NaN);
        double height =
                CssValues.length(
                        tokens.size() > 1 ? tokens.get(1) : "auto",
                        area.height,
                        em,
                        rem,
                        area.width,
                        area.height,
                        Double.NaN);
        if (Double.isNaN(width) && Double.isNaN(height)) {
            return new double[] {naturalWidth, naturalHeight};
        }
        if (Double.isNaN(width)) {
            width = height * naturalWidth / naturalHeight;
        }
        if (Double.isNaN(height)) {
            height = width * naturalHeight / naturalWidth;
        }
        return new double[] {Math.max(.001, width), Math.max(.001, height)};
    }

    private static void tile(
            Graphics2D graphics,
            BufferedImage source,
            Box area,
            double x,
            double y,
            double width,
            double height,
            String repeat) {
        List<String> modes = CssValues.tokens(repeat);
        String horizontal = modes.get(0);
        String vertical = modes.size() > 1 ? modes.get(1) : horizontal;
        if (horizontal.equals("repeat-x")) {
            horizontal = "repeat";
            vertical = "no-repeat";
        } else if (horizontal.equals("repeat-y")) {
            horizontal = "no-repeat";
            vertical = "repeat";
        }
        if (horizontal.equals("round")) {
            width = area.width / Math.max(1, Math.round(area.width / width));
            x = 0;
        }
        if (vertical.equals("round")) {
            height = area.height / Math.max(1, Math.round(area.height / height));
            y = 0;
        }
        int nx = horizontal.equals("no-repeat") ? 1 : (int) Math.ceil(area.width / width) + 2;
        int ny = vertical.equals("no-repeat") ? 1 : (int) Math.ceil(area.height / height) + 2;
        double dx = width;
        double dy = height;
        if (horizontal.equals("space") && area.width >= width * 2) {
            nx = (int) (area.width / width);
            dx += (area.width - nx * width) / (nx - 1);
            x = 0;
        } else if (!horizontal.equals("no-repeat")) {
            x -= Math.ceil(x / width) * width;
        }
        if (vertical.equals("space") && area.height >= height * 2) {
            ny = (int) (area.height / height);
            dy += (area.height - ny * height) / (ny - 1);
            y = 0;
        } else if (!vertical.equals("no-repeat")) {
            y -= Math.ceil(y / height) * height;
        }
        if ((long) nx * ny > 100000) {
            throw new PdfConversionException("Background tile limit exceeded");
        }
        for (int row = 0; row < ny; row++) {
            for (int column = 0; column < nx; column++) {
                java.awt.geom.AffineTransform transform = new java.awt.geom.AffineTransform();
                transform.translate(area.x + x + column * dx, area.y + y + row * dy);
                transform.scale(width / source.getWidth(), height / source.getHeight());
                graphics.drawImage(source, transform, null);
            }
        }
    }

    private static BufferedImage gradient(
            String expression,
            double width,
            double height,
            String currentColor,
            double em,
            double rem) {
        List<String> arguments =
                new ArrayList<>(
                        CssValues.commaSeparated(
                                expression.substring(
                                        expression.indexOf('(') + 1, expression.length() - 1)));
        boolean radial = expression.contains("radial-gradient");
        boolean conic = expression.contains("conic-gradient");
        boolean repeating = expression.startsWith("repeating-");
        String geometry = "";
        if (!isColor(arguments.get(0))) {
            geometry = arguments.remove(0);
        }
        double angle = conic ? 0 : Math.PI;
        if (!radial && !geometry.isBlank()) {
            String direction =
                    geometry.contains(" at ")
                            ? geometry.substring(0, geometry.indexOf(" at "))
                            : geometry;
            direction = direction.replace("from ", "").strip();
            if (direction.startsWith("to ")) {
                double x =
                        direction.contains("right")
                                ? width
                                : direction.contains("left") ? -width : 0;
                double y =
                        direction.contains("bottom")
                                ? height
                                : direction.contains("top") ? -height : 0;
                angle = Math.atan2(x, -y);
            } else if (!direction.isBlank()) {
                angle = angle(direction);
            }
        }
        double centerX = width / 2;
        double centerY = height / 2;
        int at = geometry.indexOf("at ");
        if (at >= 0) {
            ImagePlacement center =
                    ImagePlacement.resolve(
                            0,
                            0,
                            width,
                            height,
                            "none",
                            geometry.substring(at + 3),
                            em,
                            rem,
                            width,
                            height);
            centerX = center.x();
            centerY = center.y();
        }
        double radiusX = Math.max(centerX, width - centerX);
        double radiusY = Math.max(centerY, height - centerY);
        boolean closest = geometry.contains("closest");
        if (closest) {
            radiusX = Math.min(centerX, width - centerX);
            radiusY = Math.min(centerY, height - centerY);
        }
        if (geometry.contains("circle")) {
            radiusX =
                    radiusY =
                            geometry.contains("side")
                                    ? closest
                                            ? Math.min(radiusX, radiusY)
                                            : Math.max(radiusX, radiusY)
                                    : Math.hypot(radiusX, radiusY);
        } else if (!geometry.contains("side")) {
            radiusX *= Math.sqrt(2);
            radiusY *= Math.sqrt(2);
        }
        double basis =
                radial
                        ? radiusX
                        : conic
                                ? 1
                                : Math.abs(width * Math.sin(angle))
                                        + Math.abs(height * Math.cos(angle));
        List<Stop> stops = stops(arguments, currentColor, basis, em, rem, conic);
        int pixelWidth = Math.max(1, (int) Math.ceil(width * 2));
        int pixelHeight = Math.max(1, (int) Math.ceil(height * 2));
        if ((long) pixelWidth * pixelHeight > 16_000_000) {
            throw new PdfConversionException("Gradient raster pixel limit exceeded");
        }
        BufferedImage image =
                new BufferedImage(pixelWidth, pixelHeight, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < pixelHeight; y++) {
            for (int x = 0; x < pixelWidth; x++) {
                double px = (x + .5) * width / pixelWidth;
                double py = (y + .5) * height / pixelHeight;
                double position;
                if (radial) {
                    position =
                            Math.hypot(
                                    (px - centerX) / Math.max(.001, radiusX),
                                    (py - centerY) / Math.max(.001, radiusY));
                } else if (conic) {
                    position = (Math.atan2(px - centerX, centerY - py) - angle) / (Math.PI * 2);
                    position -= Math.floor(position);
                } else {
                    position =
                            .5
                                    + ((px - width / 2) * Math.sin(angle)
                                                    - (py - height / 2) * Math.cos(angle))
                                            / Math.max(.001, basis);
                }
                image.setRGB(x, y, interpolate(stops, position, repeating));
            }
        }
        return image;
    }

    private static boolean isColor(String value) {
        String first = CssValues.tokens(value).get(0).toLowerCase(java.util.Locale.ROOT);
        return first.startsWith("#")
                || first.startsWith("rgb")
                || first.startsWith("hsl")
                || !first.contains("(")
                        && !first.matches(".*[0-9].*")
                        && !List.of(
                                        "to",
                                        "from",
                                        "at",
                                        "circle",
                                        "ellipse",
                                        "closest-side",
                                        "farthest-side",
                                        "closest-corner",
                                        "farthest-corner")
                                .contains(first);
    }

    private static double angle(String value) {
        String text = value.strip();
        if (text.endsWith("deg")) {
            return Math.toRadians(Double.parseDouble(text.substring(0, text.length() - 3)));
        }
        if (text.endsWith("turn")) {
            return Double.parseDouble(text.substring(0, text.length() - 4)) * Math.PI * 2;
        }
        if (text.endsWith("rad")) {
            return Double.parseDouble(text.substring(0, text.length() - 3));
        }
        if (text.equals("0")) {
            return 0;
        }
        throw new PdfConversionException("Invalid gradient angle: " + value);
    }

    private static List<Stop> stops(
            List<String> arguments,
            String currentColor,
            double basis,
            double em,
            double rem,
            boolean conic) {
        List<Stop> result = new ArrayList<>();
        for (String argument : arguments) {
            List<String> tokens = CssValues.tokens(argument);
            Color color =
                    CssColor.parse(
                            tokens.get(0).equals("currentcolor") ? currentColor : tokens.get(0));
            int count = Math.max(1, tokens.size() - 1);
            for (int index = 0; index < count; index++) {
                double position = Double.NaN;
                if (tokens.size() > 1) {
                    String value = tokens.get(index + 1);
                    position =
                            conic && !value.endsWith("%")
                                    ? angle(value) / (Math.PI * 2)
                                    : CssValues.length(
                                                    value, basis, em, rem, basis, basis, Double.NaN)
                                            / basis;
                }
                result.add(new Stop(color, position));
            }
        }
        if (result.size() < 2) {
            throw new PdfConversionException("Gradient requires two color stops");
        }
        if (Double.isNaN(result.get(0).position)) {
            result.set(0, new Stop(result.get(0).color, 0));
        }
        int last = result.size() - 1;
        if (Double.isNaN(result.get(last).position)) {
            result.set(last, new Stop(result.get(last).color, 1));
        }
        int previous = 0;
        for (int index = 1; index < result.size(); index++) {
            if (!Double.isNaN(result.get(index).position)) {
                double start = result.get(previous).position;
                double end = Math.max(start, result.get(index).position);
                result.set(index, new Stop(result.get(index).color, end));
                for (int middle = previous + 1; middle < index; middle++) {
                    result.set(
                            middle,
                            new Stop(
                                    result.get(middle).color,
                                    start
                                            + (end - start)
                                                    * (middle - previous)
                                                    / (index - previous)));
                }
                previous = index;
            }
        }
        return result;
    }

    private static int interpolate(List<Stop> stops, double position, boolean repeating) {
        double first = stops.get(0).position;
        double last = stops.get(stops.size() - 1).position;
        if (repeating && last > first) {
            position =
                    first
                            + (position - first)
                            - Math.floor((position - first) / (last - first)) * (last - first);
        }
        Stop left = stops.get(0);
        for (Stop right : stops) {
            if (position <= right.position) {
                double ratio =
                        right.position > left.position
                                ? Math.max(
                                        0,
                                        Math.min(
                                                1,
                                                (position - left.position)
                                                        / (right.position - left.position)))
                                : 1;
                double alpha = left.color.getAlpha() * (1 - ratio) + right.color.getAlpha() * ratio;
                int rgb = (int) Math.round(alpha) << 24;
                for (int shift : new int[] {16, 8, 0}) {
                    double a = ((left.color.getRGB() >> shift) & 255) * left.color.getAlpha();
                    double b = ((right.color.getRGB() >> shift) & 255) * right.color.getAlpha();
                    int channel =
                            alpha > 0 ? (int) Math.round((a * (1 - ratio) + b * ratio) / alpha) : 0;
                    rgb |= Math.max(0, Math.min(255, channel)) << shift;
                }
                return rgb;
            }
            left = right;
        }
        return left.color.getRGB();
    }
}
