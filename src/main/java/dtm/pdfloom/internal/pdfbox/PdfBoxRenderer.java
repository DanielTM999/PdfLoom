package dtm.pdfloom.internal.pdfbox;

import dtm.pdfloom.config.*;
import dtm.pdfloom.css.*;
import dtm.pdfloom.font.*;
import dtm.pdfloom.image.ImageStore;
import dtm.pdfloom.page.*;
import dtm.pdfloom.render.*;
import dtm.pdfloom.style.ComputedStyle;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.pdmodel.interactive.action.*;
import org.apache.pdfbox.pdmodel.interactive.annotation.*;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.*;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.*;
import org.apache.pdfbox.util.Matrix;

import java.awt.Color;
import java.awt.geom.AffineTransform;
import java.io.*;
import java.util.*;
import java.util.regex.*;

public final class PdfBoxRenderer implements PdfRenderer {
    private record Link(PDPage page, PDAnnotationLink annotation, String target) {
    }

    private final PDDocument doc;
    private final PdfFontRegistry fonts;
    private final ImageStore images;
    private final PdfConversionConfig config;
    private final Map<String, PDImageXObject> pdfImages = new HashMap<>();
    private final Map<String, PDPageXYZDestination> destinations = new LinkedHashMap<>();
    private final List<Link> internal = new ArrayList<>();
    private final PDDocumentOutline outline = new PDDocumentOutline();
    private final Map<Integer, PDOutlineItem> outlineLevels = new HashMap<>();
    private AffineTransform geometry = new AffineTransform();
    private java.awt.geom.Area visible;

    public PdfBoxRenderer(
            PDDocument doc, PdfFontRegistry fonts, ImageStore images, PdfConversionConfig config) {
        this.doc = doc;
        this.fonts = fonts;
        this.images = images;
        this.config = config;
    }

    @Override
    public void render(List<RenderPage> pages, OutputStream destination) throws IOException {
        for (ImageStore.Image im : images.all()) {
            pdfImages.put(im.key(), PDImageXObject.createFromByteArray(doc, im.bytes(), im.key()));
        }
        for (RenderPage rp : pages) {
            PDPage page =
                    new PDPage(
                            new PDRectangle((float) rp.spec().width(), (float) rp.spec().height()));
            doc.addPage(page);
            geometry = new AffineTransform();
            visible =
                    new java.awt.geom.Area(
                            new java.awt.geom.Rectangle2D.Double(
                                    0,
                                    rp.spec().bottom(),
                                    rp.spec().width(),
                                    rp.spec().contentHeight()));
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.saveGraphicsState();
                cs.addRect(
                        0,
                        (float) rp.spec().bottom(),
                        (float) rp.spec().width(),
                        (float) rp.spec().contentHeight());
                cs.clip();
                for (Paint p : StackingOrder.resolve(rp.commands())) {
                    paint(cs, page, rp, p, pages.size());
                }
                cs.restoreGraphicsState();
                visible =
                        new java.awt.geom.Area(
                                new java.awt.geom.Rectangle2D.Double(
                                        0, 0, rp.spec().width(), rp.spec().height()));
                marginBoxes(cs, page, rp, pages.size());
            }
        }
        fonts.finishLogicalFonts();
        for (Link link : internal) {
            PDPageXYZDestination dest = destinations.get(link.target);
            if (dest == null) {
                config.report(
                        new Diagnostic(Diagnostic.Severity.WARNING, "MISSING_ANCHOR", link.target));
            } else {
                link.annotation.setDestination(dest);
            }
        }
        if (!destinations.isEmpty()) {
            PDDocumentNameDictionary names = new PDDocumentNameDictionary(doc.getDocumentCatalog());
            PDDestinationNameTreeNode tree = new PDDestinationNameTreeNode();
            tree.setNames(new TreeMap<>(destinations));
            names.setDests(tree);
            doc.getDocumentCatalog().setNames(names);
        }
        if (outline.hasChildren()) {
            doc.getDocumentCatalog().setDocumentOutline(outline);
        }
        PDDocumentInformation info = doc.getDocumentInformation();
        info.setProducer("PdfLoom / Apache PDFBox");
        if (config.metadata.title() != null) {
            info.setTitle(config.metadata.title());
        }
        if (config.metadata.author() != null) {
            info.setAuthor(config.metadata.author());
        }
        if (config.metadata.subject() != null) {
            info.setSubject(config.metadata.subject());
        }
        doc.save(destination);
    }

    private void alpha(PDPageContentStream cs, double opacity, Color c) throws IOException {
        PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
        float a = (float) (opacity * c.getAlpha() / 255);
        gs.setNonStrokingAlphaConstant(a);
        gs.setStrokingAlphaConstant(a);
        cs.setGraphicsStateParameters(gs);
    }

    private void paint(PDPageContentStream cs, PDPage page, RenderPage rp, Paint p, int total)
            throws IOException {
        double ph = rp.spec().height();
        if (p instanceof Paint.Layer layer) {
            if (layer.opacity() < 1) {
                org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup group =
                        new org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup(doc);
                group.setBBox(new PDRectangle((float) rp.spec().width(), (float) ph));
                group.setResources(new PDResources());
                org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroupAttributes attributes =
                        new org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroupAttributes();
                attributes.getCOSObject().setBoolean(org.apache.pdfbox.cos.COSName.I, true);
                group.getCOSObject().setItem(org.apache.pdfbox.cos.COSName.GROUP, attributes);
                try (PDPageContentStream content =
                        new PDPageContentStream(
                                doc, new PDAppearanceStream(group.getCOSObject()))) {
                    for (Paint child : StackingOrder.resolve(layer.commands())) {
                        paint(content, page, rp, child, total);
                    }
                }
                cs.saveGraphicsState();
                alpha(cs, layer.opacity(), Color.WHITE);
                cs.drawForm(group);
                cs.restoreGraphicsState();
            } else {
                for (Paint child : StackingOrder.resolve(layer.commands())) {
                    paint(cs, page, rp, child, total);
                }
            }
            return;
        }
        if (p instanceof Paint.Group group) {
            cs.saveGraphicsState();
            AffineTransform previous = geometry;
            java.awt.geom.Area previousVisible = visible;
            geometry = new AffineTransform(previous);
            geometry.concatenate(applyTransform(cs, group, ph));
            visible = new java.awt.geom.Area(previousVisible);
            if (group.clip()) {
                cs.addRect(
                        (float) group.x(),
                        (float) (ph - group.y() - group.height()),
                        (float) group.width(),
                        (float) group.height());
                cs.clip();
                visible.intersect(
                        new java.awt.geom.Area(
                                geometry.createTransformedShape(
                                        new java.awt.geom.Rectangle2D.Double(
                                                group.x(),
                                                ph - group.y() - group.height(),
                                                group.width(),
                                                group.height()))));
            }
            for (Paint child : StackingOrder.resolve(group.commands())) {
                paint(cs, page, rp, child, total);
            }
            cs.restoreGraphicsState();
            geometry = previous;
            visible = previousVisible;
            return;
        }
        if (p instanceof Paint.Destination d) {
            PDPageXYZDestination dest = new PDPageXYZDestination();
            dest.setPage(page);
            java.awt.geom.Point2D point =
                    geometry.transform(new java.awt.geom.Point2D.Double(d.x(), ph - d.y()), null);
            dest.setTop((int) Math.round(point.getY()));
            dest.setLeft((int) Math.round(point.getX()));
            destinations.putIfAbsent(d.name(), dest);
            if (d.level() > 0) {
                PDOutlineItem item = new PDOutlineItem();
                item.setTitle(d.title());
                item.setDestination(dest);
                PDOutlineItem parent = null;
                for (int l = d.level() - 1; l >= 1; l--) {
                    if (outlineLevels.containsKey(l)) {
                        parent = outlineLevels.get(l);
                        break;
                    }
                }
                if (parent == null) {
                    outline.addLast(item);
                } else {
                    parent.addLast(item);
                }
                outlineLevels.put(d.level(), item);
                outlineLevels.keySet().removeIf(l -> l > d.level());
            }
            return;
        }
        cs.saveGraphicsState();
        if (p instanceof Paint.Text t) {
            String value =
                    t.value()
                            .replace(GeneratedContent.PAGE, Integer.toString(rp.number()))
                            .replace(GeneratedContent.PAGES, Integer.toString(total));
            if (!t.shadow().equals("none") && !value.isBlank()) {
                drawShadows(cs, ShadowPainter.text(t, value, fonts), ph, t.opacity());
            }
            Color c = CssColor.parse(t.color());
            alpha(cs, t.opacity(), c);
            cs.setNonStrokingColor(c);
            cs.beginText();
            cs.setFont(
                    fonts.outlined(t.font())
                            ? fonts.logicalFont(t.font())
                            : fonts.pdfFont(t.font()),
                    (float) t.size());
            cs.setCharacterSpacing((float) t.letterSpacing());
            cs.setWordSpacing((float) t.wordSpacing());
            cs.newLineAtOffset((float) t.x(), (float) (ph - t.y() - t.ascent()));
            boolean complex = fonts.outlined(t.font()) || ComplexText.requiresShaping(value);
            if (complex) {
                cs.setRenderingMode(org.apache.pdfbox.pdmodel.graphics.state.RenderingMode.NEITHER);
            }
            if (fonts.outlined(t.font())) {
                cs.appendRawCommands("<" + fonts.logicalEncoding(t.font(), value) + "> Tj\n");
            } else {
                cs.showText(value);
            }
            cs.endText();
            if (complex) {
                cs.setRenderingMode(org.apache.pdfbox.pdmodel.graphics.state.RenderingMode.FILL);
                for (ComplexText.Run run : fonts.shape(value, t.font(), t.size())) {
                    drawOutline(
                            cs,
                            run.glyphs().getOutline(),
                            t.x() + run.x(),
                            ph - t.y() - t.ascent());
                }
            }
            if (t.decoration().contains("underline") || t.decoration().contains("line-through")) {
                cs.setStrokingColor(c);
                cs.setLineWidth((float) Math.max(.4, t.size() / 18));
                double yy =
                        ph
                                - t.y()
                                - t.ascent()
                                + t.size() * (t.decoration().contains("line-through") ? .3 : -.1);
                cs.moveTo((float) t.x(), (float) yy);
                cs.lineTo((float) (t.x() + t.width()), (float) yy);
                cs.stroke();
            }
            if (!value.isBlank()) {
                link(page, rp, t.x(), t.y(), t.width(), t.height(), t.href());
            }
        } else if (p instanceof Paint.Image im) {
            alpha(cs, im.opacity(), Color.WHITE);
            PDImageXObject image = pdfImages.get(im.key());
            if (image == null) {
                throw new IOException("Image not loaded: " + im.key());
            }
            cs.drawImage(
                    image,
                    (float) im.x(),
                    (float) (ph - im.y() - im.height()),
                    (float) im.width(),
                    (float) im.height());
            link(page, rp, im.x(), im.y(), im.width(), im.height(), im.href());
        } else if (p instanceof Paint.BorderLine line) {
            Color color = CssColor.parse(line.border().color());
            alpha(cs, 1, color);
            BorderPainter.line(cs, line, ph, color, line.x1() == line.x2() ? 3 : 0);
        } else if (p instanceof Paint.Rectangle r) {
            if (!r.shadow().equals("none")) {
                drawShadows(cs, ShadowPainter.box(r, false), ph, r.opacity());
            }
            Color fill = CssColor.parse(r.fill());
            if (fill.getAlpha() > 0) {
                alpha(cs, r.opacity(), fill);
                cs.setNonStrokingColor(fill);
                rounded(cs, r.x(), ph - r.y() - r.height(), r.width(), r.height(), r.radius());
                cs.fill();
            }
            if (!r.shadow().equals("none")) {
                drawShadows(cs, ShadowPainter.box(r, true), ph, r.opacity());
            }
            for (int i = 0; i < 4; i++) {
                Paint.Border border = r.borders().get(i);
                if (border.width() <= 0
                        || border.style().equals("none")
                        || border.style().equals("hidden")) {
                    continue;
                }
                Color color = CssColor.parse(border.color());
                if (Set.of("double", "groove", "ridge", "inset", "outset")
                        .contains(border.style())) {
                    alpha(cs, r.opacity(), color);
                    double inset = border.width() / 2;
                    double x1 = r.x(), x2 = r.x() + r.width(), y1 = r.y(), y2 = r.y() + r.height();
                    Paint.BorderLine edge =
                            switch (i) {
                                case 0 ->
                                        new Paint.BorderLine(
                                                x1, y1 + inset, x2, y1 + inset, border);
                                case 1 ->
                                        new Paint.BorderLine(
                                                x2 - inset, y1, x2 - inset, y2, border);
                                case 2 ->
                                        new Paint.BorderLine(
                                                x1, y2 - inset, x2, y2 - inset, border);
                                default ->
                                        new Paint.BorderLine(
                                                x1 + inset, y1, x1 + inset, y2, border);
                            };
                    BorderPainter.line(cs, edge, ph, color, i);
                    continue;
                }
                if (r.radius() > 0 && border.style().equals("solid")) {
                    roundedBorder(cs, r, i, ph, color);
                    continue;
                }
                alpha(cs, r.opacity(), color);
                cs.setStrokingColor(color);
                cs.setLineWidth((float) border.width());
                cs.setLineDashPattern(
                        border.style().equals("dashed")
                                ? new float[] {
                                    (float) (border.width() * 3), (float) (border.width() * 2)
                                }
                                : border.style().equals("dotted")
                                        ? new float[] {
                                            (float) border.width(), (float) border.width()
                                        }
                                        : new float[] {
                                        },
                        0);
                double x1 = r.x(), x2 = r.x() + r.width(), y1 = ph - r.y(), y2 = y1 - r.height();
                switch (i) {
                    case 0 -> {
                        cs.moveTo((float) x1, (float) y1);
                        cs.lineTo((float) x2, (float) y1);
                    }
                    case 1 -> {
                        cs.moveTo((float) x2, (float) y1);
                        cs.lineTo((float) x2, (float) y2);
                    }
                    case 2 -> {
                        cs.moveTo((float) x1, (float) y2);
                        cs.lineTo((float) x2, (float) y2);
                    }
                    case 3 -> {
                        cs.moveTo((float) x1, (float) y1);
                        cs.lineTo((float) x1, (float) y2);
                    }
                }
                cs.stroke();
            }
        }
        cs.restoreGraphicsState();
    }

    private void drawOutline(PDPageContentStream stream, java.awt.Shape shape, double x, double y)
            throws IOException {
        java.awt.geom.PathIterator iterator = shape.getPathIterator(null);
        double[] points = new double[6];
        double previousX = 0;
        double previousY = 0;
        while (!iterator.isDone()) {
            int segment = iterator.currentSegment(points);
            switch (segment) {
                case java.awt.geom.PathIterator.SEG_MOVETO ->
                        stream.moveTo((float) (x + points[0]), (float) (y - points[1]));
                case java.awt.geom.PathIterator.SEG_LINETO ->
                        stream.lineTo((float) (x + points[0]), (float) (y - points[1]));
                case java.awt.geom.PathIterator.SEG_QUADTO -> {
                    double controlX1 = previousX + (points[0] - previousX) * 2 / 3;
                    double controlY1 = previousY + (points[1] - previousY) * 2 / 3;
                    double controlX2 = points[2] + (points[0] - points[2]) * 2 / 3;
                    double controlY2 = points[3] + (points[1] - points[3]) * 2 / 3;
                    stream.curveTo(
                            (float) (x + controlX1),
                            (float) (y - controlY1),
                            (float) (x + controlX2),
                            (float) (y - controlY2),
                            (float) (x + points[2]),
                            (float) (y - points[3]));
                }
                case java.awt.geom.PathIterator.SEG_CUBICTO ->
                        stream.curveTo(
                                (float) (x + points[0]),
                                (float) (y - points[1]),
                                (float) (x + points[2]),
                                (float) (y - points[3]),
                                (float) (x + points[4]),
                                (float) (y - points[5]));
                case java.awt.geom.PathIterator.SEG_CLOSE -> stream.closePath();
                default -> throw new IOException("Invalid glyph outline");
            }
            if (segment != java.awt.geom.PathIterator.SEG_CLOSE) {
                int last =
                        segment == java.awt.geom.PathIterator.SEG_CUBICTO
                                ? 4
                                : segment == java.awt.geom.PathIterator.SEG_QUADTO ? 2 : 0;
                previousX = points[last];
                previousY = points[last + 1];
            }
            iterator.next();
        }
        if (shape.getPathIterator(null).getWindingRule()
                == java.awt.geom.PathIterator.WIND_EVEN_ODD) {
            stream.fillEvenOdd();
        } else {
            stream.fill();
        }
    }

    private void roundedBorder(
            PDPageContentStream stream,
            Paint.Rectangle rectangle,
            int side,
            double pageHeight,
            Color color)
            throws IOException {
        double left = rectangle.borders().get(3).width();
        double top = rectangle.borders().get(0).width();
        double right = rectangle.borders().get(1).width();
        double bottom = rectangle.borders().get(2).width();
        double x = rectangle.x();
        double y = rectangle.y();
        double width = rectangle.width();
        double height = rectangle.height();
        double[][] polygon =
                switch (side) {
                    case 0 ->
                            new double[][] {
                                {x, y},
                                {x + width, y},
                                {x + width - right, y + top},
                                {x + width / 2, y + height / 2},
                                {x + left, y + top}
                            };
                    case 1 ->
                            new double[][] {
                                {x + width, y},
                                {x + width, y + height},
                                {x + width - right, y + height - bottom},
                                {x + width / 2, y + height / 2},
                                {x + width - right, y + top}
                            };
                    case 2 ->
                            new double[][] {
                                {x + width, y + height},
                                {x, y + height},
                                {x + left, y + height - bottom},
                                {x + width / 2, y + height / 2},
                                {x + width - right, y + height - bottom}
                            };
                    default ->
                            new double[][] {
                                {x, y + height},
                                {x, y},
                                {x + left, y + top},
                                {x + width / 2, y + height / 2},
                                {x + left, y + height - bottom}
                            };
                };
        stream.saveGraphicsState();
        alpha(stream, rectangle.opacity(), color);
        stream.setNonStrokingColor(color);
        stream.moveTo((float) polygon[0][0], (float) (pageHeight - polygon[0][1]));
        for (int index = 1; index < polygon.length; index++) {
            stream.lineTo((float) polygon[index][0], (float) (pageHeight - polygon[index][1]));
        }
        stream.closePath();
        stream.clip();
        java.awt.geom.Path2D.Double ring =
                new java.awt.geom.Path2D.Double(java.awt.geom.Path2D.WIND_EVEN_ODD);
        double radius = Math.min(rectangle.radius(), Math.min(width, height) / 2);
        ring.append(
                new java.awt.geom.RoundRectangle2D.Double(
                        x, y, width, height, radius * 2, radius * 2),
                false);
        if (width > left + right && height > top + bottom) {
            innerRounded(
                    ring,
                    x + left,
                    y + top,
                    width - left - right,
                    height - top - bottom,
                    Math.max(0, radius - left),
                    Math.max(0, radius - top),
                    Math.max(0, radius - right),
                    Math.max(0, radius - bottom));
        }
        drawOutline(stream, ring, 0, pageHeight);
        stream.restoreGraphicsState();
    }

    private void innerRounded(
            java.awt.geom.Path2D.Double path,
            double x,
            double y,
            double width,
            double height,
            double leftRadius,
            double topRadius,
            double rightRadius,
            double bottomRadius) {
        double k = .5522847498;
        double lr = Math.min(leftRadius, width / 2);
        double rr = Math.min(rightRadius, width / 2);
        double tr = Math.min(topRadius, height / 2);
        double br = Math.min(bottomRadius, height / 2);
        path.moveTo(x + lr, y);
        path.lineTo(x + width - rr, y);
        path.curveTo(x + width - rr + rr * k, y, x + width, y + tr - tr * k, x + width, y + tr);
        path.lineTo(x + width, y + height - br);
        path.curveTo(
                x + width,
                y + height - br + br * k,
                x + width - rr + rr * k,
                y + height,
                x + width - rr,
                y + height);
        path.lineTo(x + lr, y + height);
        path.curveTo(x + lr - lr * k, y + height, x, y + height - br + br * k, x, y + height - br);
        path.lineTo(x, y + tr);
        path.curveTo(x, y + tr - tr * k, x + lr - lr * k, y, x + lr, y);
        path.closePath();
    }

    private AffineTransform applyTransform(PDPageContentStream cs, Paint.Group group, double ph)
            throws IOException {
        if (group.transform().isBlank() || group.transform().equals("none")) {
            return new AffineTransform();
        }
        List<String> origin = CssValues.tokens(group.transformOrigin());
        double
                ox =
                        group.x()
                                + CssValues.length(
                                        origin.isEmpty() ? "50%" : origin.get(0),
                                        group.width(),
                                        12,
                                        12,
                                        group.width(),
                                        group.height(),
                                        group.width() / 2),
                oy =
                        group.y()
                                + CssValues.length(
                                        origin.size() > 1 ? origin.get(1) : "50%",
                                        group.height(),
                                        12,
                                        12,
                                        group.width(),
                                        group.height(),
                                        group.height() / 2);
        AffineTransform t = new AffineTransform();
        t.translate(ox, oy);
        Matcher fn = Pattern.compile("([a-zA-Z0-9]+)\\(([^)]*)\\)").matcher(group.transform());
        int matched = 0;
        while (fn.find()) {
            matched++;
            String[] args = fn.group(2).trim().split("[,\\s]+");
            try {
                switch (fn.group(1)) {
                    case "translate" ->
                            t.translate(
                                    translation(args[0], group.width()),
                                    args.length > 1 ? translation(args[1], group.height()) : 0);
                    case "translateX" -> t.translate(translation(args[0], group.width()), 0);
                    case "translateY" -> t.translate(0, translation(args[0], group.height()));
                    case "scale" ->
                            t.scale(
                                    Double.parseDouble(args[0]),
                                    args.length > 1
                                            ? Double.parseDouble(args[1])
                                            : Double.parseDouble(args[0]));
                    case "scaleX" -> t.scale(Double.parseDouble(args[0]), 1);
                    case "scaleY" -> t.scale(1, Double.parseDouble(args[0]));
                    case "rotate" -> t.rotate(angle(args[0]));
                    case "skewX" -> t.shear(Math.tan(angle(args[0])), 0);
                    case "skewY" -> t.shear(0, Math.tan(angle(args[0])));
                    case "matrix" -> {
                        if (args.length != 6) {
                            throw new IllegalArgumentException("matrix requires six values");
                        }
                        t.concatenate(
                                new AffineTransform(
                                        Double.parseDouble(args[0]),
                                        Double.parseDouble(args[1]),
                                        Double.parseDouble(args[2]),
                                        Double.parseDouble(args[3]),
                                        Double.parseDouble(args[4]),
                                        Double.parseDouble(args[5])));
                    }
                    default ->
                            config.report(
                                    new Diagnostic(
                                            Diagnostic.Severity.WARNING,
                                            "UNSUPPORTED_TRANSFORM",
                                            fn.group(1)));
                }
            } catch (IllegalArgumentException e) {
                throw new IOException("Invalid transform: " + group.transform(), e);
            }
        }
        if (matched == 0) {
            throw new IOException("Invalid transform: " + group.transform());
        }
        t.translate(-ox, -oy);
        double[] m = new double[6];
        t.getMatrix(m);
        for (double d : m) {
            if (!Double.isFinite(d)) {
                throw new IOException("Nonfinite transform");
            }
        }
        cs.transform(
                new Matrix(
                        (float) m[0],
                        (float) -m[1],
                        (float) -m[2],
                        (float) m[3],
                        (float) (m[4] + m[2] * ph),
                        (float) (ph * (1 - m[3]) - m[5])));
        return new AffineTransform(
                m[0], -m[1], -m[2], m[3], m[4] + m[2] * ph, ph * (1 - m[3]) - m[5]);
    }

    private double translation(String text, double base) {
        return CssValues.length(text, base, 12, 12, config.pageWidth, config.pageHeight, 0);
    }

    private double angle(String text) {
        if (text.endsWith("deg")) {
            return Math.toRadians(Double.parseDouble(text.substring(0, text.length() - 3)));
        }
        if (text.endsWith("rad")) {
            return Double.parseDouble(text.substring(0, text.length() - 3));
        }
        if (text.endsWith("turn")) {
            return Double.parseDouble(text.substring(0, text.length() - 4)) * Math.PI * 2;
        }
        return Double.parseDouble(text);
    }

    private void rounded(
            PDPageContentStream cs, double x, double y, double w, double h, double radius)
            throws IOException {
        float xx = (float) x,
                yy = (float) y,
                ww = (float) w,
                hh = (float) h,
                r = (float) Math.max(0, Math.min(radius, Math.min(w, h) / 2)),
                k = r * .55228475f;
        if (r == 0) {
            cs.addRect(xx, yy, ww, hh);
            return;
        }
        cs.moveTo(xx + r, yy);
        cs.lineTo(xx + ww - r, yy);
        cs.curveTo(xx + ww - r + k, yy, xx + ww, yy + r - k, xx + ww, yy + r);
        cs.lineTo(xx + ww, yy + hh - r);
        cs.curveTo(xx + ww, yy + hh - r + k, xx + ww - r + k, yy + hh, xx + ww - r, yy + hh);
        cs.lineTo(xx + r, yy + hh);
        cs.curveTo(xx + r - k, yy + hh, xx, yy + hh - r + k, xx, yy + hh - r);
        cs.lineTo(xx, yy + r);
        cs.curveTo(xx, yy + r - k, xx + r - k, yy, xx + r, yy);
        cs.closePath();
    }

    private void link(
            PDPage page, RenderPage rp, double x, double y, double w, double h, String href)
            throws IOException {
        if (href == null || href.isBlank()) {
            return;
        }
        PDAnnotationLink a = new PDAnnotationLink();
        java.awt.geom.Area area =
                new java.awt.geom.Area(
                        geometry.createTransformedShape(
                                new java.awt.geom.Rectangle2D.Double(
                                        x, rp.spec().height() - y - h, w, h)));
        area.intersect(visible);
        if (area.isEmpty()) {
            return;
        }
        java.awt.geom.Rectangle2D bounds = area.getBounds2D();
        a.setQuadPoints(LinkGeometry.quadrilaterals(area));
        a.setRectangle(
                new PDRectangle(
                        (float) bounds.getX(),
                        (float) bounds.getY(),
                        (float) bounds.getWidth(),
                        (float) bounds.getHeight()));
        PDBorderStyleDictionary bs = new PDBorderStyleDictionary();
        bs.setWidth(0);
        a.setBorderStyle(bs);
        if (href.startsWith("#")) {
            internal.add(new Link(page, a, href.substring(1)));
        } else {
            java.net.URI uri;
            try {
                uri = java.net.URI.create(href);
            } catch (IllegalArgumentException e) {
                return;
            }
            if (uri.getScheme() != null
                    && !Set.of("http", "https", "mailto")
                            .contains(uri.getScheme().toLowerCase(Locale.ROOT))) {
                return;
            }
            PDActionURI action = new PDActionURI();
            action.setURI(href);
            a.setAction(action);
        }
        page.getAnnotations().add(a);
    }

    private double marginPadding(ComputedStyle style, String side, RenderPage page) {
        return CssValues.length(
                style.get("padding-" + side),
                page.spec().contentWidth(),
                12,
                12,
                page.spec().width(),
                page.spec().height(),
                0);
    }

    private double marginY(RenderPage page, String position, ComputedStyle style, double height) {
        MarginBoxLayout.Region region = MarginBoxLayout.region(page.spec(), position);
        double available =
                region.height()
                        - MarginBoxLayout.padding(style, "top", region, fonts)
                        - MarginBoxLayout.padding(style, "bottom", region, fonts);
        if (height > available + .01) {
            config.report(
                    new Diagnostic(
                            Diagnostic.Severity.WARNING,
                            "MARGIN_BOX_OVERFLOW",
                            "Margin content exceeds its available height"));
        }
        return MarginBoxLayout.vertical(style, region, height, fonts);
    }

    private void drawShadows(
            PDPageContentStream stream,
            List<ShadowPainter.Raster> shadows,
            double pageHeight,
            double opacity)
            throws IOException {
        for (ShadowPainter.Raster shadow : shadows) {
            PDImageXObject image =
                    org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(
                            doc, shadow.image());
            if (image.getCOSObject().getInt(org.apache.pdfbox.cos.COSName.LENGTH)
                    > config.maxResourceBytes) {
                throw new IOException("Shadow resource limit exceeded");
            }
            long bytes = image.getCOSObject().getLong(org.apache.pdfbox.cos.COSName.LENGTH);
            org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject mask = image.getSoftMask();
            if (mask != null) {
                bytes += mask.getCOSObject().getLong(org.apache.pdfbox.cos.COSName.LENGTH);
            }
            images.accountGenerated(bytes);
            alpha(stream, opacity, Color.WHITE);
            stream.drawImage(
                    image,
                    (float) shadow.x(),
                    (float) (pageHeight - shadow.y() - shadow.height()),
                    (float) shadow.width(),
                    (float) shadow.height());
        }
    }

    private void marginBoxes(PDPageContentStream cs, PDPage page, RenderPage rp, int total)
            throws IOException {
        for (Map.Entry<String, ComputedStyle> entry : rp.spec().marginBoxes().entrySet()) {
            String position = entry.getKey();
            if (rp.marginContent().containsKey(position)) {
                for (Paint command : rp.marginContent().get(position)) {
                    paint(cs, page, rp, command, total);
                }
                continue;
            }
            ComputedStyle specified = entry.getValue();
            Map<String, String> values = new HashMap<>(specified.values());
            values.putIfAbsent("font-family", config.defaultFont);
            values.putIfAbsent("font-size", "9pt");
            values.putIfAbsent("color", "black");
            ComputedStyle style = new ComputedStyle(values);
            String content = style.get("content");
            Matcher element =
                    Pattern.compile("element\\(\\s*([\\w-]+)(?:\\s*,\\s*([\\w-]+))?\\s*\\)")
                            .matcher(content);
            if (element.find()) {
                dtm.pdfloom.layout.LayoutResult.Running run =
                        rp.running()
                                .get(
                                        element.group(1)
                                                + (element.group(2) == null
                                                        ? ""
                                                        : ":" + element.group(2)));
                if (run != null) {
                    MarginBoxLayout.Region region = MarginBoxLayout.region(rp.spec(), position);
                    double x = region.x() + MarginBoxLayout.padding(style, "left", region, fonts);
                    double y = marginY(rp, position, style, run.height());
                    for (Paint p : run.commands()) {
                        paint(cs, page, rp, p.move(x, y), total);
                    }
                }
                continue;
            }
            Matcher named =
                    Pattern.compile("string\\(\\s*([\\w-]+)(?:\\s*,\\s*([\\w-]+))?\\s*\\)")
                            .matcher(content);
            StringBuffer replaced = new StringBuffer();
            while (named.find()) {
                named.appendReplacement(
                        replaced,
                        Matcher.quoteReplacement(
                                "\""
                                        + rp.strings()
                                                .getOrDefault(
                                                        named.group(1)
                                                                + (named.group(2) == null
                                                                        ? ""
                                                                        : ":" + named.group(2)),
                                                        "")
                                                .replace("\"", "\\\"")
                                        + "\""));
            }
            named.appendTail(replaced);
            TextMetrics.Font f = fonts.font(style);
            String text =
                    GeneratedContent.evaluate(replaced.toString(), null, Map.of())
                            .replace(GeneratedContent.PAGE, Integer.toString(rp.number()))
                            .replace(GeneratedContent.PAGES, Integer.toString(total));
            MarginBoxLayout.Region region = MarginBoxLayout.region(rp.spec(), position);
            List<Paint> commands = MarginBoxLayout.text(text, style, region, position, fonts);
            double height =
                    commands.stream()
                                    .mapToDouble(command -> command.y() + command.height())
                                    .max()
                                    .orElse(region.y())
                            - commands.stream().mapToDouble(Paint::y).min().orElse(region.y());
            marginY(rp, position, style, height);
            for (Paint command : commands) {
                paint(cs, page, rp, command, total);
            }
        }
    }
}
