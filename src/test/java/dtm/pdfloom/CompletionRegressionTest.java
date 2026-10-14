package dtm.pdfloom;

import static org.junit.jupiter.api.Assertions.*;

import dtm.pdfloom.config.PdfConversionConfig;
import dtm.pdfloom.font.ComplexText;
import dtm.pdfloom.impl.HtmlPdfConverter;
import dtm.pdfloom.render.Paint;
import dtm.pdfloom.resource.impl.ClasspathResourceResolver;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.List;

class CompletionRegressionTest {
    @Test
    void boundedGridTrackGrowsToItsMaximumBeforeFractionalTrack() throws Exception {
        List<Paint.Text> text =
                new ModernLayoutTest()
                        .texts(
                                "<style>body{margin:0}.g{display:grid;grid-template-columns:minmax(20pt,100pt)"
                                    + " 1fr}</style><div class='g'><div>A</div><div>B</div></div>");
        assertEquals(100, text.get(1).x(), .01);
    }

    @Test
    void flexColumnRedistributesAfterMinimumFreeze() throws Exception {
        List<Paint.Text> text =
                new ModernLayoutTest()
                        .texts(
                                "<style>body{margin:0}.f{display:flex;flex-direction:column;height:300pt}.f"
                                    + " div{flex:0 1 200pt}.a{min-height:180pt}</style><div"
                                    + " class='f'><div"
                                    + " class='a'>A</div><div>B</div><div>C</div></div>");
        assertEquals(180, baseline(text.get(1)) - baseline(text.get(0)), .01);
        assertEquals(240, baseline(text.get(2)) - baseline(text.get(0)), .01);
    }

    @Test
    void flexWrapDistributesCrossAxisSpace() throws Exception {
        List<Paint.Text> text =
                new ModernLayoutTest()
                        .texts(
                                "<style>body{margin:0}.f{display:flex;flex-wrap:wrap;height:200pt;align-content:space-between}.f"
                                    + " div{flex:0 0 200pt;height:20pt}</style><div"
                                    + " class='f'><div>A</div><div>B</div><div>C</div></div>");
        assertEquals(90, baseline(text.get(1)) - baseline(text.get(0)), .01);
        assertEquals(180, baseline(text.get(2)) - baseline(text.get(0)), .01);
    }

    private double baseline(Paint.Text text) {
        return text.y() + text.ascent();
    }

    @Test
    void widowsKeepThreeLinesTogetherOnContinuationPage() throws Exception {
        StringBuilder html =
                new StringBuilder(
                        "<style>@page{size:220pt"
                            + " 140pt;margin:20pt}body{margin:0}p{margin:0;line-height:20pt;orphans:3;widows:3}</style><p>");
        for (int index = 0; index < 7; index++) {
            if (index > 0) {
                html.append("<br>");
            }
            html.append("LINE").append(index);
        }
        html.append("</p>");
        try (PDDocument document =
                Loader.loadPDF(
                        new HtmlPdfConverter(
                                        PdfConversionConfig.builder()
                                                .diagnostics(diagnostic -> {
                                                })
                                                .build())
                                .convert(html.toString()))) {
            assertEquals(2, document.getNumberOfPages());
            PDFTextStripper extractor = new PDFTextStripper();
            extractor.setStartPage(1);
            extractor.setEndPage(1);
            String first = extractor.getText(document);
            assertTrue(first.contains("LINE3"));
            assertFalse(first.contains("LINE4"));
            extractor.setStartPage(2);
            extractor.setEndPage(2);
            String second = extractor.getText(document);
            assertTrue(
                    second.contains("LINE4")
                            && second.contains("LINE5")
                            && second.contains("LINE6"));
        }
    }

    @Test
    void floatingInlineImageReservesSpaceForAdjacentHeading() throws Exception {
        java.awt.image.BufferedImage image =
                new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", output);
        String uri =
                "data:image/png;base64,"
                        + java.util.Base64.getEncoder().encodeToString(output.toByteArray());
        List<Paint.Text> text =
                new ModernLayoutTest()
                        .texts(
                                "<style>body{margin:0}img{float:left;width:40pt;height:40pt;margin-right:10pt}h2{float:left;width:100pt;margin:0}</style><div><img"
                                    + " src='"
                                        + uri
                                        + "'><h2>HEADING</h2></div>");
        assertEquals(50, text.get(0).x(), .01);
        assertTrue(baseline(text.get(0)) < 40);
    }

    @Test
    void externalFontFallbackSupportsMixedLatinAndArabicWords() throws Exception {
        PdfConversionConfig config =
                PdfConversionConfig.builder()
                        .resourceResolver(new ClasspathResourceResolver("/"))
                        .font("Arabic", 400, false, "/fonts/NotoSansArabic-Regular.ttf")
                        .diagnostics(
                                diagnostic -> assertNotEquals("MISSING_GLYPH", diagnostic.code()))
                        .build();
        try (PDDocument document =
                Loader.loadPDF(
                        new HtmlPdfConverter(config)
                                .convert("<p>LATIN \u0633\u0644\u0627\u0645</p>"))) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("LATIN"));
            assertFalse(text.contains("\uFFFD"));
            int count = 0;
            for (org.apache.pdfbox.cos.COSName name :
                    document.getPage(0).getResources().getFontNames()) {
                count++;
            }
            assertEquals(2, count);
        }
    }

    @Test
    void gridRowsUseMinimumAwareFractionSizing() throws Exception {
        List<Paint.Text> text =
                new ModernLayoutTest()
                        .texts(
                                "<style>body{margin:0}.g{display:grid;height:150pt;grid-template-columns:1fr;grid-template-rows:minmax(100pt,1fr)"
                                    + " 1fr}</style><div class='g'><div>A</div><div>B</div></div>");
        assertEquals(100, text.get(1).y() - text.get(0).y(), .01);
    }

    @Test
    void autoFillComputesRepetitionFromAvailableWidth() throws Exception {
        List<Paint.Text> text =
                new ModernLayoutTest()
                        .texts(
                                "<style>body{margin:0}.g{display:grid;grid-template-columns:repeat(auto-fill,minmax(100pt,1fr))}</style><div"
                                    + " class='g'><div>A</div><div>B</div><div>C</div><div>D</div></div>");
        assertEquals(100, text.get(1).x(), .01);
        assertEquals(200, text.get(2).x(), .01);
        assertEquals(0, text.get(3).x(), .01);
        assertTrue(text.get(3).y() > text.get(0).y());
    }

    @Test
    void transformedLinksAndDestinationsUsePaintedCoordinates() throws Exception {
        try (PDDocument document =
                Loader.loadPDF(
                        convert(
                                "<div id='anchor' style='transform:translateX(30pt)'><a"
                                        + " href='#anchor'>LINK</a></div>"))) {
            assertEquals(
                    50,
                    document.getPage(0).getAnnotations().get(0).getRectangle().getLowerLeftX(),
                    .01);
            org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination
                            .PDPageXYZDestination
                    destination =
                            (org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination
                                            .PDPageXYZDestination)
                                    document.getDocumentCatalog()
                                            .getNames()
                                            .getDests()
                                            .getValue("anchor");
            assertEquals(50, destination.getLeft());
        }
    }

    @Test
    void explicitClippedHeightDoesNotCreateHiddenContinuationPages() throws Exception {
        StringBuilder html = new StringBuilder("<div style='height:30pt;overflow:hidden'>");
        for (int index = 0; index < 60; index++) {
            html.append("<p>HIDDEN").append(index).append("</p>");
        }
        html.append("</div><p>AFTER</p>");
        try (PDDocument document = Loader.loadPDF(convert(html.toString()))) {
            assertEquals(1, document.getNumberOfPages());
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("AFTER"));
            assertFalse(text.contains("HIDDEN59"));
        }
    }

    private byte[] convert(String body) {
        return new HtmlPdfConverter(
                        PdfConversionConfig.builder().diagnostics(diagnostic -> {
                        }).build())
                .convert("<style>@page{size:220pt 220pt;margin:20pt}body{margin:0}</style>" + body);
    }

    @Test
    void stackingContextKeepsHighDescendantBelowHigherSibling() throws Exception {
        try (PDDocument document =
                Loader.loadPDF(
                        convert(
                                "<div style='position:relative;z-index:1'><div"
                                    + " style='position:absolute;z-index:100;left:0;top:0;width:60pt;height:60pt;background:red'></div></div><div"
                                    + " style='position:absolute;z-index:2;left:0;top:0;width:60pt;height:60pt;background:blue'></div>"))) {
            Color color =
                    new Color(new PDFRenderer(document).renderImageWithDPI(0, 72).getRGB(40, 40));
            assertTrue(color.getBlue() > 240 && color.getRed() < 10);
        }
    }

    @Test
    void negativeStackingLevelPaintsBehindNormalFlow() throws Exception {
        try (PDDocument document =
                Loader.loadPDF(
                        convert(
                                "<div style='background:blue;width:60pt;height:60pt'></div><div"
                                    + " style='position:absolute;z-index:-2;left:0;top:0;width:60pt;height:60pt;background:red'></div>"))) {
            Color color =
                    new Color(new PDFRenderer(document).renderImageWithDPI(0, 72).getRGB(40, 40));
            assertTrue(color.getBlue() > 240 && color.getRed() < 10);
        }
    }

    @Test
    void opacityIsAppliedOnceToOverlappingChildren() throws Exception {
        try (PDDocument document =
                Loader.loadPDF(
                        convert(
                                "<div style='opacity:.5;position:relative;width:60pt;height:60pt;background:red'><div"
                                    + " style='position:absolute;left:0;top:0;width:30pt;height:60pt;background:blue'></div></div>"))) {
            BufferedImage image = new PDFRenderer(document).renderImageWithDPI(0, 72);
            Color blue = new Color(image.getRGB(30, 40));
            Color red = new Color(image.getRGB(60, 40));
            assertEquals(128, blue.getRed(), 3);
            assertEquals(128, blue.getGreen(), 3);
            assertEquals(255, blue.getBlue(), 3);
            assertEquals(255, red.getRed(), 3);
            assertEquals(128, red.getBlue(), 3);
        }
    }

    @Test
    void groupedParagraphFragmentsHaveNoDuplicatedText() throws Exception {
        StringBuilder body =
                new StringBuilder(
                        "<section style='position:relative;opacity:.8;transform:translateX(1pt)'>");
        for (int index = 0; index < 70; index++) {
            body.append("<p style='margin:0'>TOKEN")
                    .append(String.format("%03d", index))
                    .append("</p>");
        }
        body.append("</section>");
        try (PDDocument document = Loader.loadPDF(convert(body.toString()))) {
            assertTrue(document.getNumberOfPages() > 2);
            String text = new PDFTextStripper().getText(document);
            for (int index = 0; index < 70; index++) {
                String token = "TOKEN" + String.format("%03d", index);
                assertEquals(1, text.split(token, -1).length - 1, token);
            }
        }
    }

    @Test
    void nestedRepeatAndMinmaxProduceMinimumAwareFractions() throws Exception {
        List<Paint.Text> text =
                new ModernLayoutTest()
                        .texts(
                                "<style>body{margin:0}.g{display:grid;grid-template-columns:repeat(2,minmax(120pt,1fr))"
                                    + " 1fr}</style><div"
                                    + " class='g'><div>A</div><div>B</div><div>C</div></div>");
        assertEquals(120, text.get(1).x(), .01);
        assertEquals(240, text.get(2).x(), .01);
    }

    @Test
    void flexMaximumFreezesAndRemainingGrowthIsRedistributed() throws Exception {
        List<Paint.Text> text =
                new ModernLayoutTest()
                        .texts(
                                "<style>body{margin:0}.f{display:flex}.f div{flex:1 1"
                                        + " 20pt}.a{max-width:50pt}</style><div class='f'><div"
                                        + " class='a'>A</div><div>B</div><div>C</div></div>");
        assertEquals(50, text.get(1).x(), .01);
        assertEquals(175, text.get(2).x(), .01);
    }

    @Test
    void combiningMarksStayWithTheirBaseDuringWrapping() throws Exception {
        List<Paint.Text> text =
                new ModernLayoutTest()
                        .texts(
                                "<style>body{margin:0}div{width:10pt;overflow-wrap:anywhere;font-size:12pt}</style><div>a\u0301a\u0301a\u0301</div>");
        assertEquals(
                List.of("a\u0301", "a\u0301", "a\u0301"),
                text.stream().map(Paint.Text::value).toList());
    }

    @Test
    void arabicShapingUsesLigaturesAndRendersWithEmbeddedFont() throws Exception {
        String arabic = "\u0633\u0644\u0627\u0645";
        try (InputStream source =
                getClass().getResourceAsStream("/fonts/NotoSansArabic-Regular.ttf")) {
            Font font = Font.createFont(Font.TRUETYPE_FONT, source).deriveFont(24f);
            List<ComplexText.Run> runs = ComplexText.shape(arabic, font);
            assertTrue(
                    runs.stream().mapToInt(run -> run.glyphs().getNumGlyphs()).sum()
                            < arabic.length());
            assertTrue(ComplexText.width(runs) > 20);
        }
        PdfConversionConfig config =
                PdfConversionConfig.builder()
                        .resourceResolver(new ClasspathResourceResolver("/"))
                        .font("Arabic", 400, false, "/fonts/NotoSansArabic-Regular.ttf")
                        .diagnostics(
                                diagnostic -> {
                                    assertNotEquals("MISSING_GLYPH", diagnostic.code());
                                })
                        .build();
        byte[] bytes =
                new HtmlPdfConverter(config)
                        .convert(
                                "<style>@page{size:220pt"
                                    + " 220pt;margin:20pt}body{margin:0}p{font-family:Arabic;direction:rtl;font-size:24pt}</style><p>"
                                        + arabic
                                        + " "
                                        + arabic
                                        + "</p>");
        try (PDDocument document = Loader.loadPDF(bytes)) {
            BufferedImage image = new PDFRenderer(document).renderImageWithDPI(0, 72);
            int dark = 0;
            for (int y = 20; y < 80; y++) {
                for (int x = 100; x < 200; x++) {
                    Color pixel = new Color(image.getRGB(x, y));
                    if (pixel.getRed() < 100) {
                        dark++;
                    }
                }
            }
            assertTrue(dark > 150);
            assertFalse(new PDFTextStripper().getText(document).isBlank());
        }
    }
}
