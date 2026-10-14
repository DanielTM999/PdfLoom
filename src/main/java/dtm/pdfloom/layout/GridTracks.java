package dtm.pdfloom.layout;

import dtm.pdfloom.css.CssValues;
import dtm.pdfloom.exception.PdfConversionException;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;

final class GridTracks {
    record Track(
            double minimum,
            double maximum,
            double fraction,
            boolean intrinsic,
            String minimumKind,
            String maximumKind) {
        Track(double minimum, double maximum, double fraction, boolean intrinsic) {
            this(
                    minimum,
                    maximum,
                    fraction,
                    intrinsic,
                    intrinsic ? "min-content" : "fixed",
                    fraction > 0 ? "fraction" : intrinsic ? "auto" : "fixed");
        }
    }

    private GridTracks() {
    }

    static List<String> expand(
            String template, double available, double gap, ToDoubleFunction<String> length) {
        List<String> result = new ArrayList<>();
        for (String token : CssValues.tokens(template)) {
            if (token.startsWith("repeat(") && token.endsWith(")")) {
                int separator = comma(token);
                if (separator < 0) {
                    throw new PdfConversionException("Invalid grid repetition: " + token);
                }
                String countValue = token.substring(7, separator).strip();
                String body = token.substring(separator + 1, token.length() - 1).strip();
                List<String> pattern = expand(body, available, gap, length);
                int count;
                if (countValue.equals("auto-fill") || countValue.equals("auto-fit")) {
                    double minimum =
                            pattern.stream()
                                    .mapToDouble(value -> parse(value, length).minimum())
                                    .sum();
                    minimum += gap * Math.max(0, pattern.size() - 1);
                    count =
                            (int)
                                    Math.max(
                                            1,
                                            Math.floor(
                                                    (available + gap)
                                                            / Math.max(.1, minimum + gap)));
                } else {
                    try {
                        count = Integer.parseInt(countValue);
                    } catch (NumberFormatException exception) {
                        throw new PdfConversionException(
                                "Invalid grid repetition: " + countValue, exception);
                    }
                }
                if (count < 1 || count > 256 || result.size() + count * pattern.size() > 256) {
                    throw new PdfConversionException("Grid track limit exceeded");
                }
                for (int index = 0; index < count; index++) {
                    result.addAll(pattern);
                }
            } else if (!token.startsWith("[")) {
                result.add(token);
            }
        }
        return result;
    }

    static Track parse(String value, ToDoubleFunction<String> length) {
        if (value.startsWith("minmax(") && value.endsWith(")")) {
            int separator = comma(value);
            if (separator < 0) {
                throw new PdfConversionException("Invalid minmax track: " + value);
            }
            Track lower = parse(value.substring(7, separator).strip(), length);
            Track upper = parse(value.substring(separator + 1, value.length() - 1).strip(), length);
            return new Track(
                    lower.minimum(),
                    Math.max(lower.minimum(), upper.maximum()),
                    upper.fraction(),
                    lower.intrinsic(),
                    lower.minimumKind(),
                    upper.maximumKind());
        }
        if (value.endsWith("fr")) {
            double fraction = Double.parseDouble(value.substring(0, value.length() - 2));
            if (fraction < 0 || !Double.isFinite(fraction)) {
                throw new PdfConversionException("Invalid grid fraction");
            }
            return new Track(0, Double.POSITIVE_INFINITY, fraction, true);
        }
        if (value.equals("auto") || value.equals("min-content") || value.equals("max-content")) {
            return new Track(
                    0,
                    Double.POSITIVE_INFINITY,
                    0,
                    true,
                    value.equals("auto") ? "min-content" : value,
                    value);
        }
        if (value.startsWith("fit-content(") && value.endsWith(")")) {
            return new Track(
                    0,
                    length.applyAsDouble(value.substring(12, value.length() - 1)),
                    0,
                    true,
                    "min-content",
                    "fit-content");
        }
        double fixed = Math.max(0, length.applyAsDouble(value));
        return new Track(fixed, fixed, 0, false);
    }

    static double[] size(List<Track> tracks, double[] intrinsic, double available) {
        return size(tracks, intrinsic, intrinsic, available);
    }

    static double[] size(
            List<Track> tracks, double[] intrinsic, double[] preferred, double available) {
        double[] sizes = new double[tracks.size()];
        boolean[] frozen = new boolean[tracks.size()];
        double remaining = available;
        for (int index = 0; index < sizes.length; index++) {
            Track track = tracks.get(index);
            sizes[index] =
                    Math.max(
                            track.minimum(),
                            track.intrinsic()
                                    ? track.minimumKind().equals("max-content")
                                            ? preferred[index]
                                            : intrinsic[index]
                                    : 0);
            sizes[index] = Math.min(sizes[index], track.maximum());
            if (track.fraction() == 0) {
                frozen[index] = true;
                remaining -= sizes[index];
            }
        }
        double boundedFree = available - java.util.Arrays.stream(sizes).sum();
        List<Integer> bounded = new ArrayList<>();
        double[] growthLimits = new double[sizes.length];
        for (int index = 0; index < sizes.length; index++) {
            Track track = tracks.get(index);
            growthLimits[index] =
                    switch (track.maximumKind()) {
                        case "min-content" -> sizes[index];
                        case "max-content", "auto", "fit-content" ->
                                Math.max(sizes[index], Math.min(track.maximum(), preferred[index]));
                        default -> track.maximum();
                    };
            if (track.fraction() == 0
                    && Double.isFinite(growthLimits[index])
                    && growthLimits[index] > sizes[index]) {
                bounded.add(index);
            }
        }
        while (!bounded.isEmpty() && boundedFree > .001) {
            double share = boundedFree / bounded.size();
            for (int index : List.copyOf(bounded)) {
                double added = Math.min(share, growthLimits[index] - sizes[index]);
                sizes[index] += added;
                boundedFree -= added;
                remaining -= added;
                if (sizes[index] >= growthLimits[index] - .001) {
                    bounded.remove(Integer.valueOf(index));
                }
            }
        }
        for (int pass = 0; pass <= sizes.length; pass++) {
            double fractions = 0;
            for (int index = 0; index < sizes.length; index++) {
                if (!frozen[index]) {
                    fractions += tracks.get(index).fraction();
                }
            }
            boolean violation = false;
            for (int index = 0; index < sizes.length; index++) {
                if (frozen[index]) {
                    continue;
                }
                double proposed =
                        Math.max(0, remaining)
                                * tracks.get(index).fraction()
                                / Math.max(1, fractions);
                if (proposed < sizes[index]) {
                    frozen[index] = true;
                    remaining -= sizes[index];
                    violation = true;
                }
            }
            if (!violation) {
                for (int index = 0; index < sizes.length; index++) {
                    if (!frozen[index]) {
                        sizes[index] =
                                Math.max(0, remaining)
                                        * tracks.get(index).fraction()
                                        / Math.max(1, fractions);
                    }
                }
                break;
            }
        }
        double free = available - java.util.Arrays.stream(sizes).sum();
        if (free > 0) {
            List<Integer> automatic = new ArrayList<>();
            for (int index = 0; index < sizes.length; index++) {
                if (tracks.get(index).fraction() == 0
                        && tracks.get(index).intrinsic()
                        && tracks.get(index).maximumKind().equals("auto")
                        && sizes[index] < tracks.get(index).maximum()) {
                    automatic.add(index);
                }
            }
            while (!automatic.isEmpty() && free > .001) {
                double share = free / automatic.size();
                for (int index : List.copyOf(automatic)) {
                    double added = Math.min(share, tracks.get(index).maximum() - sizes[index]);
                    sizes[index] += added;
                    free -= added;
                    if (sizes[index] >= tracks.get(index).maximum() - .001) {
                        automatic.remove(Integer.valueOf(index));
                    }
                }
            }
        }
        return sizes;
    }

    private static int comma(String function) {
        int depth = 0;
        for (int index = 0; index < function.length(); index++) {
            char character = function.charAt(index);
            if (character == '(') {
                depth++;
            } else if (character == ')') {
                depth--;
            } else if (character == ',' && depth == 1) {
                return index;
            }
        }
        return -1;
    }
}
