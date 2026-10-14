package dtm.pdfloom.internal.pdfbox;

import dtm.pdfloom.render.Paint;

import org.apache.pdfbox.pdmodel.PDPageContentStream;

import java.awt.Color;
import java.io.IOException;

final class BorderPainter {
    private BorderPainter() {
    }

    static void line(
            PDPageContentStream stream,
            Paint.BorderLine line,
            double pageHeight,
            Color color,
            int side)
            throws IOException {
        String style = line.border().style();
        double width = line.border().width();
        if (width <= 0 || style.equals("none") || style.equals("hidden")) {
            return;
        }
        boolean relief = style.equals("groove") || style.equals("ridge");
        int count = style.equals("double") || relief ? 2 : 1;
        double stroke = style.equals("double") ? width / 3 : relief ? width / 2 : width;
        stream.setLineWidth((float) stroke);
        stream.setLineDashPattern(
                style.equals("dashed")
                        ? new float[] {(float) (stroke * 3), (float) (stroke * 2)}
                        : style.equals("dotted")
                                ? new float[] {(float) stroke, (float) stroke}
                                : new float[0],
                0);
        for (int index = 0; index < count; index++) {
            double offset = count == 1 ? 0 : (index == 0 ? -1 : 1) * (relief ? width / 4 : stroke);
            boolean near = side == 0 || side == 3;
            Color selected = color;
            if (relief) {
                boolean dark = style.equals("groove") == near;
                if ((index == 1) == near) {
                    dark = !dark;
                }
                selected = shade(color, dark);
            } else if (style.equals("inset") || style.equals("outset")) {
                selected = shade(color, style.equals("inset") == near);
            }
            stream.setStrokingColor(selected);
            double dx = line.x1() == line.x2() ? offset : 0;
            double dy = line.y1() == line.y2() ? offset : 0;
            stream.moveTo((float) (line.x1() + dx), (float) (pageHeight - line.y1() - dy));
            stream.lineTo((float) (line.x2() + dx), (float) (pageHeight - line.y2() - dy));
            stream.stroke();
        }
    }

    private static Color shade(Color color, boolean dark) {
        double fraction = dark ? .6 : .4;
        int red =
                dark
                        ? (int) (color.getRed() * fraction)
                        : (int) (color.getRed() + (255 - color.getRed()) * fraction);
        int green =
                dark
                        ? (int) (color.getGreen() * fraction)
                        : (int) (color.getGreen() + (255 - color.getGreen()) * fraction);
        int blue =
                dark
                        ? (int) (color.getBlue() * fraction)
                        : (int) (color.getBlue() + (255 - color.getBlue()) * fraction);
        return new Color(red, green, blue);
    }
}
