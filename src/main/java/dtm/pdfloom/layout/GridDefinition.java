package dtm.pdfloom.layout;

import dtm.pdfloom.exception.PdfConversionException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class GridDefinition {
    record Axis(List<String> tracks, Map<String, List<Integer>> lines) {
        int line(String reference, boolean end) {
            String value = reference.strip();
            if (value.matches("-?[1-9][0-9]*")) {
                int number = Integer.parseInt(value);
                return number > 0 ? number - 1 : tracks.size() + number + 1;
            }
            List<String> parts = tokens(value);
            String name =
                    parts.stream()
                            .filter(part -> !part.matches("-?[0-9]+") && !part.equals("span"))
                            .findFirst()
                            .orElse("");
            List<Integer> positions = lines.get(name);
            if (positions == null) {
                positions = lines.get(name + (end ? "-end" : "-start"));
            }
            if (positions == null || positions.isEmpty()) {
                return -1;
            }
            int occurrence =
                    parts.stream()
                            .filter(part -> part.matches("-?[1-9][0-9]*"))
                            .mapToInt(Integer::parseInt)
                            .findFirst()
                            .orElse(1);
            int index = occurrence > 0 ? occurrence - 1 : positions.size() + occurrence;
            return index >= 0 && index < positions.size() ? positions.get(index) : -1;
        }
    }

    record Area(int row, int column, int rows, int columns) {
    }

    private GridDefinition() {
    }

    static Axis parse(
            String template, double available, double gap, ToDoubleFunction<String> length) {
        List<String> expanded = expand(template, available, gap, length, 0);
        List<String> tracks = new ArrayList<>();
        Map<String, List<Integer>> lines = new LinkedHashMap<>();
        for (String token : expanded) {
            if (token.startsWith("[") && token.endsWith("]")) {
                for (String name : token.substring(1, token.length() - 1).strip().split("\\s+")) {
                    if (!name.isBlank()) {
                        lines.computeIfAbsent(name, key -> new ArrayList<>()).add(tracks.size());
                    }
                }
            } else if (!token.equals("none") && !token.isBlank()) {
                tracks.add(token);
            }
        }
        if (tracks.size() > 256) {
            throw new PdfConversionException("Grid track limit exceeded");
        }
        return new Axis(tracks, lines);
    }

    private static List<String> expand(
            String template,
            double available,
            double gap,
            ToDoubleFunction<String> length,
            int depth) {
        if (depth > 32) {
            throw new PdfConversionException("Grid repetition depth exceeded");
        }
        List<String> output = new ArrayList<>();
        for (String token : tokens(template)) {
            if (token.startsWith("repeat(") && token.endsWith(")")) {
                int comma = token.indexOf(',');
                if (comma < 0) {
                    throw new PdfConversionException("Invalid grid repetition");
                }
                String countValue = token.substring(7, comma).strip();
                List<String> pattern =
                        expand(
                                token.substring(comma + 1, token.length() - 1),
                                available,
                                gap,
                                length,
                                depth + 1);
                int count;
                if (countValue.equals("auto-fill") || countValue.equals("auto-fit")) {
                    List<String> tracks =
                            pattern.stream().filter(value -> !value.startsWith("[")).toList();
                    double minimum =
                            tracks.stream()
                                            .mapToDouble(
                                                    value ->
                                                            GridTracks.parse(value, length)
                                                                    .minimum())
                                            .sum()
                                    + gap * Math.max(0, tracks.size() - 1);
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
                        throw new PdfConversionException("Invalid grid repetition", exception);
                    }
                }
                if (count < 1
                        || count > 256
                        || output.size() + (long) count * pattern.size() > 4096) {
                    throw new PdfConversionException("Grid repetition limit exceeded");
                }
                for (int index = 0; index < count; index++) {
                    output.addAll(pattern);
                }
            } else {
                output.add(token);
            }
        }
        return output;
    }

    static List<String> tokens(String source) {
        List<String> result = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int index = 0; index <= source.length(); index++) {
            char character = index == source.length() ? ' ' : source.charAt(index);
            if (character == '(' || character == '[') {
                depth++;
            } else if (character == ')' || character == ']') {
                depth--;
            }
            if (depth == 0 && Character.isWhitespace(character)) {
                if (index > start) {
                    result.add(source.substring(start, index));
                }
                start = index + 1;
            }
        }
        if (depth != 0) {
            throw new PdfConversionException("Unbalanced grid template");
        }
        return result;
    }

    static Map<String, Area> areas(String expression) {
        Map<String, Area> areas = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        Matcher rows = Pattern.compile("\"([^\"]*)\"|'([^']*)'").matcher(expression);
        int row = 0;
        int columns = -1;
        while (rows.find()) {
            String[] names =
                    (rows.group(1) == null ? rows.group(2) : rows.group(1)).strip().split("\\s+");
            if (columns >= 0 && names.length != columns) {
                throw new PdfConversionException("Grid area rows have different lengths");
            }
            columns = names.length;
            for (int column = 0; column < names.length; column++) {
                String name = names[column];
                if (name.matches("\\.+")) {
                    continue;
                }
                Area previous = areas.get(name);
                areas.put(
                        name,
                        previous == null
                                ? new Area(row, column, 1, 1)
                                : new Area(
                                        previous.row,
                                        Math.min(column, previous.column),
                                        row - previous.row + 1,
                                        Math.max(previous.column + previous.columns, column + 1)
                                                - Math.min(column, previous.column)));
                counts.merge(name, 1, Integer::sum);
            }
            row++;
        }
        for (Map.Entry<String, Area> entry : areas.entrySet()) {
            if (entry.getValue().rows * entry.getValue().columns != counts.get(entry.getKey())) {
                throw new PdfConversionException("Grid area is not rectangular: " + entry.getKey());
            }
        }
        return areas;
    }

    static void addAreas(Axis columns, Axis rows, Map<String, Area> areas) {
        areas.forEach(
                (name, area) -> {
                    columns.lines
                            .computeIfAbsent(name + "-start", key -> new ArrayList<>())
                            .add(area.column);
                    columns.lines
                            .computeIfAbsent(name + "-end", key -> new ArrayList<>())
                            .add(area.column + area.columns);
                    rows.lines
                            .computeIfAbsent(name + "-start", key -> new ArrayList<>())
                            .add(area.row);
                    rows.lines
                            .computeIfAbsent(name + "-end", key -> new ArrayList<>())
                            .add(area.row + area.rows);
                });
    }
}
