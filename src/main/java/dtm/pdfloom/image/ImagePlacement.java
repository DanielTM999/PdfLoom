package dtm.pdfloom.image;

import dtm.pdfloom.css.CssValues;

import java.util.List;

public record ImagePlacement(double x, double y, double width, double height) {
    public static ImagePlacement resolve(
            double intrinsicWidth,
            double intrinsicHeight,
            double boxWidth,
            double boxHeight,
            String fit,
            String position) {
        return resolve(
                intrinsicWidth,
                intrinsicHeight,
                boxWidth,
                boxHeight,
                fit,
                position,
                12,
                boxWidth,
                boxHeight);
    }

    public static ImagePlacement resolve(
            double intrinsicWidth,
            double intrinsicHeight,
            double boxWidth,
            double boxHeight,
            String fit,
            String position,
            double fontSize,
            double viewportWidth,
            double viewportHeight) {
        return resolve(
                intrinsicWidth,
                intrinsicHeight,
                boxWidth,
                boxHeight,
                fit,
                position,
                fontSize,
                12,
                viewportWidth,
                viewportHeight);
    }

    public static ImagePlacement resolve(
            double intrinsicWidth,
            double intrinsicHeight,
            double boxWidth,
            double boxHeight,
            String fit,
            String position,
            double fontSize,
            double rootFontSize,
            double viewportWidth,
            double viewportHeight) {
        double width = boxWidth;
        double height = boxHeight;
        if (!fit.isBlank() && !fit.equals("fill")) {
            double scale =
                    switch (fit) {
                        case "contain" ->
                                Math.min(boxWidth / intrinsicWidth, boxHeight / intrinsicHeight);
                        case "cover" ->
                                Math.max(boxWidth / intrinsicWidth, boxHeight / intrinsicHeight);
                        case "none" -> 1;
                        case "scale-down" ->
                                Math.min(
                                        1,
                                        Math.min(
                                                boxWidth / intrinsicWidth,
                                                boxHeight / intrinsicHeight));
                        default ->
                                throw new IllegalArgumentException(
                                        "Unsupported object-fit: " + fit);
                    };
            width = intrinsicWidth * scale;
            height = intrinsicHeight * scale;
        }
        List<String> tokens = CssValues.tokens(position.isBlank() ? "50% 50%" : position);
        if (tokens.size() > 4) {
            throw new IllegalArgumentException("Invalid object-position: " + position);
        }
        if (tokens.size() >= 3) {
            double x = (boxWidth - width) / 2;
            double y = (boxHeight - height) / 2;
            boolean horizontalSet = false;
            boolean verticalSet = false;
            for (int index = 0; index < tokens.size(); index++) {
                String edge = tokens.get(index);
                if (edge.equals("center")) {
                    if (!horizontalSet) {
                        horizontalSet = true;
                    } else if (!verticalSet) {
                        verticalSet = true;
                    } else {
                        throw new IllegalArgumentException("Duplicate object-position axis");
                    }
                    continue;
                }
                boolean horizontal = edge.equals("left") || edge.equals("right");
                if (!horizontal && !edge.equals("top") && !edge.equals("bottom")
                        || horizontal && horizontalSet
                        || !horizontal && verticalSet) {
                    throw new IllegalArgumentException("Invalid object-position edge: " + edge);
                }
                double remaining = horizontal ? boxWidth - width : boxHeight - height;
                double distance = 0;
                if (index + 1 < tokens.size()
                        && !List.of("left", "right", "top", "bottom", "center")
                                .contains(tokens.get(index + 1))) {
                    distance =
                            CssValues.length(
                                    tokens.get(++index),
                                    remaining,
                                    fontSize,
                                    rootFontSize,
                                    viewportWidth,
                                    viewportHeight,
                                    Double.NaN);
                    if (!Double.isFinite(distance)) {
                        throw new IllegalArgumentException("Invalid object-position offset");
                    }
                }
                double value =
                        edge.equals("right") || edge.equals("bottom")
                                ? remaining - distance
                                : distance;
                if (horizontal) {
                    x = value;
                    horizontalSet = true;
                } else {
                    y = value;
                    verticalSet = true;
                }
            }
            return new ImagePlacement(x, y, width, height);
        }
        String horizontal = tokens.get(0);
        String vertical = tokens.size() > 1 ? tokens.get(1) : "50%";
        if (horizontal.equals("top") || horizontal.equals("bottom")) {
            String swapped = vertical;
            vertical = horizontal;
            horizontal = swapped;
        } else if (vertical.equals("left") || vertical.equals("right")) {
            String swapped = horizontal;
            horizontal = vertical;
            vertical = swapped;
        }
        return new ImagePlacement(
                offset(
                        horizontal,
                        boxWidth - width,
                        fontSize,
                        rootFontSize,
                        viewportWidth,
                        viewportHeight),
                offset(
                        vertical,
                        boxHeight - height,
                        fontSize,
                        rootFontSize,
                        viewportWidth,
                        viewportHeight),
                width,
                height);
    }

    private static double offset(
            String value,
            double remaining,
            double fontSize,
            double rootFontSize,
            double viewportWidth,
            double viewportHeight) {
        return switch (value) {
            case "left", "top" -> 0;
            case "center" -> remaining / 2;
            case "right", "bottom" -> remaining;
            default ->
                    CssValues.length(
                            value,
                            remaining,
                            fontSize,
                            rootFontSize,
                            viewportWidth,
                            viewportHeight,
                            0);
        };
    }
}
