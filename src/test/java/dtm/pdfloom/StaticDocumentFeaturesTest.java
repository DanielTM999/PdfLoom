package dtm.pdfloom;

import static org.junit.jupiter.api.Assertions.*;

import dtm.pdfloom.image.ImagePlacement;
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

class StaticDocumentFeaturesTest {
    @Test
    void preparingTheTocPreservesCssPreformattedLineBreaks() throws Exception {
        String html =
                "<p style='white-space:pre-wrap'>LINEONE\nLINETWO</p><nav data-pdfloom-toc></nav>";
        try (PDDocument pdf = Loader.loadPDF(new HtmlPdfConverter().convert(html))) {
            String text = new PDFTextStripper().getText(pdf).replace("\r", "");
            assertTrue(text.contains("LINEONE\nLINETWO"), text);
        }
    }

    @Test
    void oversizedImageContinuesWithoutScalingOrFooterOverlap() throws Exception {
        BufferedImage source = new BufferedImage(20, 600, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D graphics = source.createGraphics();
        graphics.setColor(Color.RED);
        graphics.fillRect(0, 0, 20, 200);
        graphics.setColor(Color.GREEN);
        graphics.fillRect(0, 200, 20, 200);
        graphics.setColor(Color.BLUE);
        graphics.fillRect(0, 400, 20, 200);
        graphics.dispose();
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(source, "png", png);
        String html =
                "<style>@page{size:200pt"
                    + " 200pt;margin:25pt;@bottom-center{content:'FOOTER'}}body{margin:0}img{display:block;width:15pt;height:450pt}</style><img"
                    + " src='data:image/png;base64,"
                        + Base64.getEncoder().encodeToString(png.toByteArray())
                        + "'>";
        try (PDDocument pdf = Loader.loadPDF(new HtmlPdfConverter().convert(html))) {
            assertEquals(3, pdf.getNumberOfPages());
            PDFRenderer renderer = new PDFRenderer(pdf);
            for (int page = 0; page < 3; page++) {
                BufferedImage image = renderer.renderImageWithDPI(page, 72);
                Color actual = new Color(image.getRGB(30, 100));
                assertEquals(new Color[] {Color.RED, Color.GREEN, Color.BLUE}[page], actual);
                assertEquals(Color.WHITE.getRGB(), image.getRGB(30, 180));
            }
            assertEquals(3, new PDFTextStripper().getText(pdf).split("FOOTER", -1).length - 1);
        }
    }

    @Test
    void threeAndFourComponentImagePositionsUseEdgeOffsets() {
        ImagePlacement four =
                ImagePlacement.resolve(20, 10, 100, 80, "none", "right 10pt bottom 5pt");
        assertEquals(70, four.x(), .001);
        assertEquals(65, four.y(), .001);
        ImagePlacement three = ImagePlacement.resolve(20, 10, 100, 80, "none", "bottom 5pt left");
        assertEquals(0, three.x(), .001);
        assertEquals(65, three.y(), .001);
        assertThrows(
                IllegalArgumentException.class,
                () -> ImagePlacement.resolve(20, 10, 100, 80, "none", "left right 5pt"));
    }

    @Test
    void reversedListsRespectExplicitStartsAndItemValues() throws Exception {
        try (PDDocument pdf =
                Loader.loadPDF(
                        new HtmlPdfConverter()
                                .convert(
                                        "<ol reversed start='8'><li>A</li><li"
                                                + " value='4'>B</li><li>C</li></ol>"))) {
            String text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains("8. A"));
            assertTrue(text.contains("4. B"));
            assertTrue(text.contains("3. C"));
        }
    }

    @Test
    void inlineSvgUsesTheSecureImagePathAndIntrinsicGeometry() throws Exception {
        String html =
                "<style>body{margin:0}</style><svg xmlns='http://www.w3.org/2000/svg' width='80'"
                        + " height='40' viewBox='0 0 80 40'><rect width='80' height='40'"
                        + " fill='red'/></svg><p>AFTER SVG</p>";
        try (PDDocument pdf = Loader.loadPDF(new HtmlPdfConverter().convert(html))) {
            BufferedImage image = new PDFRenderer(pdf).renderImageWithDPI(0, 72);
            assertEquals(Color.RED.getRGB(), image.getRGB(65, 65));
            assertTrue(new PDFTextStripper().getText(pdf).contains("AFTER SVG"));
        }
    }

    @Test
    void automaticTocConvergesAndInlineAnchorCreatesDestination() throws Exception {
        String html =
                "<style>@page{size:300pt"
                        + " 200pt;margin:20pt}body{margin:0}h1{break-before:page}</style><nav"
                        + " data-pdfloom-toc></nav><h1>Chapter one</h1><p><span"
                        + " id='inline-target'>Destination</span></p><h1>Chapter two</h1><a"
                        + " href='#inline-target'>BACK</a>";
        try (PDDocument pdf = Loader.loadPDF(new HtmlPdfConverter().convert(html))) {
            assertEquals(3, pdf.getNumberOfPages());
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(1);
            String text = stripper.getText(pdf);
            assertTrue(text.contains("Chapter one"));
            assertTrue(text.contains("Chapter two"));
            assertTrue(text.contains("2"));
            assertTrue(text.contains("3"));
            assertTrue(
                    pdf.getDocumentCatalog()
                            .getNames()
                            .getDests()
                            .getNames()
                            .containsKey("inline-target"));
            assertFalse(pdf.getPage(0).getAnnotations().isEmpty());
        }
    }
}
