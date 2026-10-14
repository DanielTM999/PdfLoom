package dtm.pdfloom.layout;

import dtm.pdfloom.render.Paint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class CollapsedBorders {
    private record Edge(boolean vertical, int line, int segment) {
    }

    private record Candidate(
            Paint.Border border, int origin, int row, int column, int order, boolean rtl)
            implements Comparable<Candidate> {
        @Override
        public int compareTo(Candidate other) {
            int result =
                    Boolean.compare(
                            border.style().equals("hidden"), other.border.style().equals("hidden"));
            if (result == 0) {
                result =
                        Boolean.compare(
                                !border.style().equals("none"),
                                !other.border.style().equals("none"));
            }
            if (result == 0) {
                result = Double.compare(border.width(), other.border.width());
            }
            if (result == 0) {
                result = Integer.compare(rank(border.style()), rank(other.border.style()));
            }
            if (result == 0) {
                result = Integer.compare(origin, other.origin);
            }
            if (result == 0) {
                result = Integer.compare(other.row, row);
            }
            if (result == 0) {
                result =
                        rtl
                                ? Integer.compare(column, other.column)
                                : Integer.compare(other.column, column);
            }
            return result == 0 ? Integer.compare(other.order, order) : result;
        }

        private static int rank(String style) {
            return switch (style) {
                case "double" -> 8;
                case "solid" -> 7;
                case "dashed" -> 6;
                case "dotted" -> 5;
                case "ridge" -> 4;
                case "outset" -> 3;
                case "groove" -> 2;
                case "inset" -> 1;
                default -> 0;
            };
        }
    }

    private final Map<Edge, Candidate> edges = new LinkedHashMap<>();
    private final boolean rtl;
    private int sequence;

    CollapsedBorders() {
        this(false);
    }

    CollapsedBorders(boolean rtl) {
        this.rtl = rtl;
    }

    void add(int row, int column, int rows, int columns, List<Paint.Border> borders, int origin) {
        int order = sequence++;
        Candidate top = new Candidate(borders.get(0), origin, row, column, order, rtl);
        Candidate right = new Candidate(borders.get(1), origin, row, column, order, rtl);
        Candidate bottom = new Candidate(borders.get(2), origin, row, column, order, rtl);
        Candidate left = new Candidate(borders.get(3), origin, row, column, order, rtl);
        for (int segment = column; segment < column + columns; segment++) {
            compete(new Edge(false, row, segment), top);
            compete(new Edge(false, row + rows, segment), bottom);
        }
        for (int segment = row; segment < row + rows; segment++) {
            compete(new Edge(true, column, segment), left);
            compete(new Edge(true, column + columns, segment), right);
        }
    }

    private void compete(Edge edge, Candidate candidate) {
        Candidate previous = edges.get(edge);
        if (previous == null || candidate.compareTo(previous) > 0) {
            edges.put(edge, candidate);
        }
    }

    double[] halves(int row, int column, int rows, int columns) {
        double top = 0, right = 0, bottom = 0, left = 0;
        for (int segment = column; segment < column + columns; segment++) {
            top = Math.max(top, width(new Edge(false, row, segment)) / 2);
            bottom = Math.max(bottom, width(new Edge(false, row + rows, segment)) / 2);
        }
        for (int segment = row; segment < row + rows; segment++) {
            left = Math.max(left, width(new Edge(true, column, segment)) / 2);
            right = Math.max(right, width(new Edge(true, column + columns, segment)) / 2);
        }
        return new double[] {top, right, bottom, left};
    }

    private double width(Edge edge) {
        Candidate candidate = edges.get(edge);
        if (candidate == null
                || candidate.border.style().equals("hidden")
                || candidate.border.style().equals("none")) {
            return 0;
        }
        return Math.max(0, candidate.border.width());
    }

    List<Paint> paint(double[] columns, double[] rows) {
        List<Paint> commands = new ArrayList<>();
        for (Map.Entry<Edge, Candidate> entry : edges.entrySet()) {
            Paint.Border border = entry.getValue().border;
            if (border.style().equals("hidden")
                    || border.style().equals("none")
                    || border.width() <= 0) {
                continue;
            }
            if (border.style().equals("inset") || border.style().equals("outset")) {
                border =
                        new Paint.Border(
                                border.width(),
                                border.color(),
                                border.style().equals("inset") ? "ridge" : "groove");
            }
            Edge edge = entry.getKey();
            double x = edge.vertical ? columns[edge.line] : columns[edge.segment];
            double y = edge.vertical ? rows[edge.segment] : rows[edge.line];
            double length =
                    edge.vertical ? rows[edge.segment + 1] - y : columns[edge.segment + 1] - x;
            commands.add(
                    new Paint.BorderLine(
                            x,
                            y,
                            edge.vertical ? x : x + length,
                            edge.vertical ? y + length : y,
                            border));
        }
        return commands;
    }
}
