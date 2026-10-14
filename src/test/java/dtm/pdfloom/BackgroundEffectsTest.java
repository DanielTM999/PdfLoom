package dtm.pdfloom;

import static org.junit.jupiter.api.Assertions.*;

import dtm.pdfloom.impl.HtmlPdfConverter;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

import javax.imageio.ImageIO;

class BackgroundEffectsTest {
    @Test
    void tableCellHeadingsPreserveTheirTableBackgroundWithoutRepaintingTheAncestor()
            throws Exception {
        BufferedImage image =
                raster(
                        "<style>.section{background:white;border-radius:7pt;height:120pt}.header{display:table;width:160pt;background:#f8f9fa;padding:15pt"
                            + " 0}h2{display:table-cell;margin:0;padding:0"
                            + " 10pt;font-size:12pt}</style><div class='section'><div"
                            + " class='header'><h2>Heading</h2></div></div>");
        assertEquals(new Color(248, 249, 250).getRGB(), color(image, 100, 20).getRGB());
        assertEquals(Color.WHITE.getRGB(), color(image, 100, 70).getRGB());
    }

    @Test
    void genuineTableRowGroupsStillPaintTheirOwnBackgrounds() throws Exception {
        BufferedImage image =
                raster(
                        "<style>table{width:160pt;border-spacing:0}td{height:30pt;padding:0}thead{background:red}tbody{background:blue}</style><table><thead><tr><td>A</td></tr></thead><tbody><tr><td>B</td></tr></tbody></table>");
        assertEquals(Color.RED.getRGB(), color(image, 100, 15).getRGB());
        assertEquals(Color.BLUE.getRGB(), color(image, 100, 45).getRGB());
    }

    @Test
    void doubleAndReliefBordersHaveDistinctPaintedBands() throws Exception {
        BufferedImage doubleBorder =
                raster("<div style='width:100pt;height:60pt;border:9pt double red'></div>");
        assertEquals(Color.RED.getRGB(), color(doubleBorder, 50, 1).getRGB());
        assertEquals(Color.WHITE.getRGB(), color(doubleBorder, 50, 4).getRGB());
        assertEquals(Color.RED.getRGB(), color(doubleBorder, 50, 7).getRGB());
        BufferedImage groove =
                raster(
                        "<div style='width:100pt;height:60pt;border:8pt groove"
                                + " rgb(120,120,120)'></div>");
        Color outer = color(groove, 50, 1);
        Color inner = color(groove, 50, 6);
        assertTrue(outer.getRed() < 120);
        assertTrue(inner.getRed() > 120);
        assertTrue(color(groove, 115, 30).getRed() > 120);
        assertTrue(color(groove, 110, 30).getRed() < 120);
        assertTrue(color(groove, 50, 75).getRed() > 120);
        assertTrue(color(groove, 50, 70).getRed() < 120);
        BufferedImage ridge =
                raster(
                        "<div style='width:100pt;height:60pt;border:8pt ridge"
                                + " rgb(120,120,120)'></div>");
        assertTrue(color(ridge, 50, 1).getRed() > 120);
        assertTrue(color(ridge, 50, 6).getRed() < 120);
    }

    private BufferedImage raster(String html) throws Exception {
        try (PDDocument document =
                Loader.loadPDF(
                        new HtmlPdfConverter()
                                .convert(
                                        "<style>@page{size:240pt"
                                                + " 240pt;margin:20pt}body{margin:0}</style>"
                                                + html))) {
            return new PDFRenderer(document).renderImageWithDPI(0, 144);
        }
    }

    private Color color(BufferedImage image, double x, double y) {
        return new Color(image.getRGB((int) ((20 + x) * 2), (int) ((20 + y) * 2)));
    }

    @Test
    void linearRadialConicAndRepeatingGradientsProduceTheirColorFields() throws Exception {
        BufferedImage linear =
                raster(
                        "<div style='width:100pt;height:100pt;background:linear-gradient(to"
                                + " right,red,blue)'></div>");
        assertTrue(color(linear, 5, 50).getRed() > 220);
        assertTrue(color(linear, 95, 50).getBlue() > 220);
        BufferedImage radial =
                raster(
                        "<div style='width:100pt;height:100pt;background:radial-gradient(circle,red,blue)'></div>");
        assertTrue(color(radial, 50, 50).getRed() > 240);
        assertTrue(color(radial, 2, 2).getBlue() > 220);
        BufferedImage conic =
                raster(
                        "<div style='width:100pt;height:100pt;background:conic-gradient(red 0deg"
                                + " 180deg,blue 180deg 360deg)'></div>");
        assertTrue(color(conic, 80, 50).getRed() > 240);
        assertTrue(color(conic, 20, 50).getBlue() > 240);
        BufferedImage repeating =
                raster(
                        "<div style='width:100pt;height:30pt;background:repeating-linear-gradient(to"
                            + " right,red 0pt 10pt,blue 10pt 20pt)'></div>");
        assertTrue(color(repeating, 5, 15).getRed() > 240);
        assertTrue(color(repeating, 15, 15).getBlue() > 240);
        assertTrue(color(repeating, 25, 15).getRed() > 240);
    }

    @Test
    void imageBackgroundRespectsSizePositionRepeatAndClipping() throws Exception {
        BufferedImage tile = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D graphics = tile.createGraphics();
        graphics.setColor(Color.RED);
        graphics.fillRect(0, 0, 4, 4);
        graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(tile, "png", output);
        String url =
                "url(data:image/png;base64,"
                        + Base64.getEncoder().encodeToString(output.toByteArray())
                        + ")";
        BufferedImage once =
                raster(
                        "<div style='width:100pt;height:100pt;background:"
                                + url
                                + " right 10pt bottom 5pt / 20pt 10pt no-repeat blue'></div>");
        assertTrue(color(once, 75, 90).getRed() > 240);
        assertTrue(color(once, 50, 50).getBlue() > 240);
        BufferedImage spaced =
                raster(
                        "<div style='width:100pt;height:20pt;background-image:"
                                + url
                                + ";background-size:20pt 20pt;background-repeat:space"
                                + " no-repeat'></div>");
        assertTrue(color(spaced, 10, 10).getRed() > 240);
        assertTrue(color(spaced, 90, 10).getRed() > 240);
    }

    @Test
    void boxAndTextShadowsPaintEffectsWithoutDuplicatingText() throws Exception {
        BufferedImage outer =
                raster(
                        "<div style='margin:20pt;width:40pt;height:40pt;background:white;box-shadow:10pt"
                            + " 10pt 0pt red'></div>");
        assertTrue(color(outer, 65, 45).getRed() > 240 && color(outer, 65, 45).getGreen() < 20);
        assertEquals(Color.WHITE.getRGB(), color(outer, 40, 40).getRGB());
        BufferedImage inset =
                raster(
                        "<div style='width:80pt;height:80pt;background:white;box-shadow:inset 8pt"
                                + " 0pt 0pt red'></div>");
        assertTrue(color(inset, 3, 40).getRed() > 240 && color(inset, 3, 40).getGreen() < 20);
        assertEquals(Color.WHITE.getRGB(), color(inset, 40, 40).getRGB());
        try (PDDocument document =
                Loader.loadPDF(
                        new HtmlPdfConverter()
                                .convert(
                                        "<p style='font-size:30pt;text-shadow:3pt 2pt 2pt"
                                                + " red'>SEARCHABLE</p>"))) {
            String text = new PDFTextStripper().getText(document);
            assertEquals(1, text.split("SEARCHABLE", -1).length - 1);
            assertNotNull(new PDFRenderer(document).renderImageWithDPI(0, 144));
        }
    }
}
