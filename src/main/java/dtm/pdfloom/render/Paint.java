package dtm.pdfloom.render;

import java.util.*;

public sealed interface Paint
        permits Paint.Text,
                Paint.Rectangle,
                Paint.Image,
                Paint.Destination,
                Paint.Group,
                Paint.Layer,
                Paint.BorderLine {
    double x();

    double y();

    double width();

    double height();

    Paint move(double dx, double dy);

    record Border(double width, String color, String style) {
    }

    record BorderLine(double x1, double y1, double x2, double y2, Border border) implements Paint {
        @Override
        public double x() {
            return Math.min(x1, x2) - border.width() / 2;
        }

        @Override
        public double y() {
            return Math.min(y1, y2) - border.width() / 2;
        }

        @Override
        public double width() {
            return Math.abs(x2 - x1) + border.width();
        }

        @Override
        public double height() {
            return Math.abs(y2 - y1) + border.width();
        }

        @Override
        public Paint move(double dx, double dy) {
            return new BorderLine(x1 + dx, y1 + dy, x2 + dx, y2 + dy, border);
        }
    }

    record Text(
            double x,
            double y,
            double width,
            double height,
            String value,
            String font,
            double size,
            String color,
            double letterSpacing,
            double wordSpacing,
            String decoration,
            String href,
            double opacity,
            double ascent,
            double lineHeight,
            String shadow)
            implements Paint {
        public Text(
                double x,
                double y,
                double width,
                double height,
                String value,
                String font,
                double size,
                String color,
                double letterSpacing,
                double wordSpacing,
                String decoration,
                String href,
                double opacity,
                double ascent,
                double lineHeight) {
            this(
                    x,
                    y,
                    width,
                    height,
                    value,
                    font,
                    size,
                    color,
                    letterSpacing,
                    wordSpacing,
                    decoration,
                    href,
                    opacity,
                    ascent,
                    lineHeight,
                    "none");
        }

        public Paint move(double dx, double dy) {
            return new Text(
                    x + dx,
                    y + dy,
                    width,
                    height,
                    value,
                    font,
                    size,
                    color,
                    letterSpacing,
                    wordSpacing,
                    decoration,
                    href,
                    opacity,
                    ascent,
                    lineHeight,
                    shadow);
        }

        public Text content(String s, double measured) {
            return new Text(
                    x,
                    y,
                    measured,
                    height,
                    s,
                    font,
                    size,
                    color,
                    letterSpacing,
                    wordSpacing,
                    decoration,
                    href,
                    opacity,
                    ascent,
                    lineHeight,
                    shadow);
        }
    }

    record Rectangle(
            double x,
            double y,
            double width,
            double height,
            String fill,
            List<Border> borders,
            double radius,
            double opacity,
            String shadow)
            implements Paint {
        public Rectangle(
                double x,
                double y,
                double width,
                double height,
                String fill,
                List<Border> borders,
                double radius,
                double opacity) {
            this(x, y, width, height, fill, borders, radius, opacity, "none");
        }

        public Rectangle {
            borders = List.copyOf(borders);
        }

        public Paint move(double dx, double dy) {
            return new Rectangle(
                    x + dx, y + dy, width, height, fill, borders, radius, opacity, shadow);
        }
    }

    record Image(
            double x,
            double y,
            double width,
            double height,
            String key,
            double opacity,
            String href)
            implements Paint {
        public Paint move(double dx, double dy) {
            return new Image(x + dx, y + dy, width, height, key, opacity, href);
        }
    }

    record Destination(double x, double y, String name, int level, String title) implements Paint {
        public double width() {
            return 0;
        }

        public double height() {
            return 0;
        }

        public Paint move(double dx, double dy) {
            return new Destination(x + dx, y + dy, name, level, title);
        }
    }

    record Group(
            double x,
            double y,
            double width,
            double height,
            List<Paint> commands,
            boolean clip,
            String transform,
            String transformOrigin)
            implements Paint {
        public Group {
            commands = List.copyOf(commands);
        }

        public Paint move(double dx, double dy) {
            return new Group(
                    x + dx,
                    y + dy,
                    width,
                    height,
                    commands.stream().map(p -> p.move(dx, dy)).toList(),
                    clip,
                    transform,
                    transformOrigin);
        }
    }

    record Layer(
            double x,
            double y,
            double width,
            double height,
            List<Paint> commands,
            int order,
            boolean context,
            double opacity)
            implements Paint {
        public Layer {
            commands = List.copyOf(commands);
        }

        public Paint move(double dx, double dy) {
            return new Layer(
                    x + dx,
                    y + dy,
                    width,
                    height,
                    commands.stream().map(command -> command.move(dx, dy)).toList(),
                    order,
                    context,
                    opacity);
        }
    }
}
