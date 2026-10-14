package dtm.pdfloom.internal.pdfbox;

import java.awt.geom.Area;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;

final class LinkGeometry {
    private LinkGeometry() {
    }

    static float[] quadrilaterals(Area area) {
        List<Float> result = new ArrayList<>();
        List<Point2D.Double> polygon = new ArrayList<>();
        PathIterator path = area.getPathIterator(null, .01);
        double[] coordinates = new double[6];
        while (!path.isDone()) {
            int kind = path.currentSegment(coordinates);
            if (kind == PathIterator.SEG_MOVETO) {
                append(result, polygon);
                polygon.clear();
                polygon.add(new Point2D.Double(coordinates[0], coordinates[1]));
            } else if (kind == PathIterator.SEG_LINETO) {
                polygon.add(new Point2D.Double(coordinates[0], coordinates[1]));
            } else if (kind == PathIterator.SEG_CLOSE) {
                append(result, polygon);
                polygon.clear();
            }
            path.next();
        }
        append(result, polygon);
        float[] values = new float[result.size()];
        for (int index = 0; index < values.length; index++) {
            values[index] = result.get(index);
        }
        return values;
    }

    private static void append(List<Float> values, List<Point2D.Double> polygon) {
        if (polygon.size() < 3) {
            return;
        }
        if (polygon.get(0).distance(polygon.get(polygon.size() - 1)) < .0001) {
            polygon = polygon.subList(0, polygon.size() - 1);
        }
        for (int index = 1; index < polygon.size() - 1; index += 2) {
            Point2D.Double first = polygon.get(0);
            Point2D.Double second = polygon.get(index);
            Point2D.Double third = polygon.get(index + 1);
            Point2D.Double fourth = polygon.get(Math.min(index + 2, polygon.size() - 1));
            for (Point2D.Double point : List.of(first, second, fourth, third)) {
                values.add((float) point.x);
                values.add((float) point.y);
            }
        }
    }
}
