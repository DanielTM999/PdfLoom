package dtm.pdfloom;

import static org.junit.jupiter.api.Assertions.*;

import dtm.pdfloom.config.PdfConversionConfig;
import dtm.pdfloom.css.CascadeStyleResolver;
import dtm.pdfloom.html.HtmlParser;
import dtm.pdfloom.image.ImagePlacement;
import dtm.pdfloom.image.ImageStore;
import dtm.pdfloom.internal.pdfbox.PdfFontRegistry;
import dtm.pdfloom.layout.CssLayoutEngine;
import dtm.pdfloom.render.Paint;
import dtm.pdfloom.resource.ResourceLoader;
import dtm.pdfloom.style.StyleSheet;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import javax.imageio.ImageIO;

class ImageLayoutTest {
    @Test
    void fitModesPreserveRatiosAndPositionInsideTheContentBox() {
        ImagePlacement contain = ImagePlacement.resolve(75, 37.5, 100, 100, "contain", "center");
        assertEquals(new ImagePlacement(0, 25, 100, 50), contain);
        assertEquals(
                new ImagePlacement(-50, 0, 200, 100),
                ImagePlacement.resolve(75, 37.5, 100, 100, "cover", "50% 50%"));
        assertEquals(
                new ImagePlacement(0, 0, 100, 100),
                ImagePlacement.resolve(75, 37.5, 100, 100, "fill", "center"));
        assertEquals(
                new ImagePlacement(25, 62.5, 75, 37.5),
                ImagePlacement.resolve(75, 37.5, 100, 100, "none", "right bottom"));
        assertEquals(
                new ImagePlacement(0, 0, 75, 37.5),
                ImagePlacement.resolve(75, 37.5, 100, 100, "scale-down", "top left"));
        assertEquals(
                new ImagePlacement(5, 11, 100, 50),
                ImagePlacement.resolve(75, 37.5, 100, 100, "contain", "5pt 11pt"));
        assertEquals(
                new ImagePlacement(20, 40, 100, 50),
                ImagePlacement.resolve(75, 37.5, 100, 100, "contain", "1em 2em", 20, 300, 300));
    }

    @Test
    void borderBoxDoesNotApplyDeclaredDimensionsTwice() throws Exception {
        List<Paint> paints =
                layout(
                        "box-sizing:border-box;width:100pt;height:80pt;padding:10pt;border:2pt"
                                + " solid black;object-fit:fill");
        Paint.Image image =
                paints.stream()
                        .filter(Paint.Image.class::isInstance)
                        .map(Paint.Image.class::cast)
                        .findFirst()
                        .orElseThrow();
        assertEquals(12, image.x(), .01);
        assertEquals(12, image.y(), .01);
        assertEquals(76, image.width(), .01);
        assertEquals(56, image.height(), .01);
        Paint.Text next =
                paints.stream()
                        .filter(Paint.Text.class::isInstance)
                        .map(Paint.Text.class::cast)
                        .filter(text -> text.value().equals("NEXT"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(100, next.x(), .01);
    }

    @Test
    void absoluteReplacedImageKeepsItsContentInsideTheSpecifiedBorderBox() throws Exception {
        List<Paint> paints =
                layout(
                        "position:absolute;left:200pt;top:5pt;box-sizing:border-box;width:80pt;height:60pt;padding:5pt");
        Paint.Image image =
                paints.stream()
                        .filter(Paint.Image.class::isInstance)
                        .map(Paint.Image.class::cast)
                        .findFirst()
                        .orElseThrow();
        assertEquals(205, image.x(), .01);
        assertEquals(10, image.y(), .01);
        assertEquals(70, image.width(), .01);
        assertEquals(50, image.height(), .01);
        Paint.Text next =
                paints.stream()
                        .filter(Paint.Text.class::isInstance)
                        .map(Paint.Text.class::cast)
                        .filter(text -> text.value().equals("NEXT"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(0, next.x(), .01);
    }

    @Test
    void automaticAndConstrainedWidthsUseIntrinsicDimensions() throws Exception {
        for (String css :
                List.of(
                        "padding:5pt;border:1pt solid black",
                        "height:40pt",
                        "min-width:100pt;max-width:110pt")) {
            List<Paint> paints = layout(css);
            Paint.Image image =
                    paints.stream()
                            .filter(Paint.Image.class::isInstance)
                            .map(Paint.Image.class::cast)
                            .findFirst()
                            .orElseThrow();
            assertEquals(2, image.width() / image.height(), .001);
            double expected = css.startsWith("padding") ? 75 : css.startsWith("height") ? 80 : 100;
            assertEquals(expected, image.width(), .01);
        }
    }

    private List<Paint> layout(String css) throws Exception {
        BufferedImage image = new BufferedImage(100, 50, BufferedImage.TYPE_INT_RGB);
        String data;
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            data =
                    "data:image/png;base64,"
                            + Base64.getEncoder().encodeToString(output.toByteArray());
        }
        PdfConversionConfig config =
                PdfConversionConfig.builder().diagnostics(diagnostic -> {
                }).build();
        HtmlParser.Document dom =
                new HtmlParser()
                        .parse(
                                "<style>body{margin:0}img{"
                                        + css
                                        + "}</style><img src='"
                                        + data
                                        + "'><span>NEXT</span>",
                                config);
        try (ResourceLoader loader = new ResourceLoader(config);
                PDDocument document = new PDDocument()) {
            StyleSheet styles = new CascadeStyleResolver(config, loader).resolve(dom, null);
            PdfFontRegistry fonts = new PdfFontRegistry(document, config, loader, styles, null);
            List<Paint> paints = new ArrayList<>();
            collect(
                    new CssLayoutEngine(config, styles, fonts, new ImageStore(loader), null, 1)
                            .layout(dom, 300)
                            .commands,
                    paints);
            return paints;
        }
    }

    private void collect(List<Paint> source, List<Paint> result) {
        for (Paint paint : source) {
            result.add(paint);
            if (paint instanceof Paint.Group group) {
                collect(group.commands(), result);
            } else if (paint instanceof Paint.Layer layer) {
                collect(layer.commands(), result);
            }
        }
    }
}
