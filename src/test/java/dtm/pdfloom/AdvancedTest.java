package dtm.pdfloom;

import static org.junit.jupiter.api.Assertions.*;

import dtm.pdfloom.config.*;
import dtm.pdfloom.exception.PdfConversionException;
import dtm.pdfloom.impl.HtmlPdfConverter;
import dtm.pdfloom.resource.impl.MemoryResourceResolver;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

class AdvancedTest {
    byte[] convert(String html) {
        return new HtmlPdfConverter(PdfConversionConfig.builder().diagnostics(d -> {
        }).build())
                .convert(html);
    }

    @Test
    void runningElementsNamedStringsAndCounters() throws Exception {
        String html =
                "<style>@page{size:300pt"
                    + " 300pt;margin:40pt;@top-center{content:element(reportHeader)}@bottom-center{content:string(chapter)"
                    + " ' ' counter(page) '/'"
                    + " counter(pages)}}body{margin:0}header{position:running(reportHeader)}h1{string-set:chapter"
                    + " content(text)}</style><header>HEADER REPETIDO</header><h1>Capítulo"
                    + " A</h1><p>Primeira</p><h1 style='break-before:page'>Capítulo"
                    + " B</h1><p>Segunda</p>";
        try (org.apache.pdfbox.pdmodel.PDDocument d = Loader.loadPDF(convert(html))) {
            assertEquals(2, d.getNumberOfPages());
            PDFTextStripper t = new PDFTextStripper();
            for (int i = 1; i <= 2; i++) {
                t.setStartPage(i);
                t.setEndPage(i);
                String text = t.getText(d);
                assertTrue(text.contains("HEADER REPETIDO"));
                assertTrue(text.contains("Capítulo " + (i == 1 ? "A" : "B") + " " + i + "/2"));
            }
        }
    }

    @Test
    void svgShapesAndExternalBlocking() throws Exception {
        String svg =
                "<svg xmlns='http://www.w3.org/2000/svg' width='100' height='50'><rect width='100'"
                        + " height='50' fill='#00ff00'/><circle cx='50' cy='25' r='10'"
                        + " fill='blue'/></svg>";
        String data =
                "data:image/svg+xml;base64,"
                        + Base64.getEncoder().encodeToString(svg.getBytes(StandardCharsets.UTF_8));
        try (org.apache.pdfbox.pdmodel.PDDocument d =
                Loader.loadPDF(
                        convert(
                                "<style>@page{margin:20pt}body{margin:0}</style><img src='"
                                        + data
                                        + "'>"))) {
            java.awt.image.BufferedImage raster = new PDFRenderer(d).renderImageWithDPI(0, 144);
            java.awt.Color c = new java.awt.Color(raster.getRGB(50, 50));
            assertTrue(c.getGreen() > 240);
        }
        String hostile =
                "<svg xmlns='http://www.w3.org/2000/svg'><image"
                        + " href='https://example.com/remote.png'/></svg>";
        String bad =
                "data:image/svg+xml;base64,"
                        + Base64.getEncoder()
                                .encodeToString(hostile.getBytes(StandardCharsets.UTF_8));
        assertThrows(PdfConversionException.class, () -> convert("<img src='" + bad + "'>"));
        String entity =
                "<!DOCTYPE svg [<!ENTITY x SYSTEM 'file:///secret'>]><svg"
                        + " xmlns='http://www.w3.org/2000/svg'><text>&x;</text></svg>";
        assertThrows(
                PdfConversionException.class,
                () ->
                        convert(
                                "<img src='data:image/svg+xml;base64,"
                                        + Base64.getEncoder()
                                                .encodeToString(
                                                        entity.getBytes(StandardCharsets.UTF_8))
                                        + "'>"));
    }

    @Test
    void transformAndClippingRaster() throws Exception {
        String html =
                "<style>@page{size:200pt 200pt;margin:10pt}body{margin:0}</style><div"
                    + " style='height:20pt;width:40pt;overflow:hidden;transform:translate(30pt,20pt);background:red'><div"
                    + " style='height:80pt;background:blue'>X</div></div>";
        try (org.apache.pdfbox.pdmodel.PDDocument d = Loader.loadPDF(convert(html))) {
            java.awt.image.BufferedImage raster = new PDFRenderer(d).renderImageWithDPI(0, 144);
            assertEquals(java.awt.Color.BLUE.getRGB(), raster.getRGB(120, 75));
            assertEquals(java.awt.Color.WHITE.getRGB(), raster.getRGB(90, 130));
        }
    }

    @Test
    void fontFaceRelativeToImportedCss() throws Exception {
        byte[] font;
        try (java.io.InputStream in =
                getClass().getResourceAsStream("/dtm/pdfloom/fonts/Roboto-Regular.ttf")) {
            font = in.readAllBytes();
        }
        dtm.pdfloom.resource.impl.MemoryResourceResolver memory =
                new MemoryResourceResolver(
                        Map.of(
                                "/css/main.css",
                                "@import 'nested/font.css';".getBytes(StandardCharsets.UTF_8),
                                "/css/nested/font.css",
                                "@font-face{font-family:Client;src:url('../../fonts/client.ttf')}body{font-family:Client}"
                                        .getBytes(StandardCharsets.UTF_8),
                                "/fonts/client.ttf",
                                font));
        dtm.pdfloom.impl.HtmlPdfConverter c =
                new HtmlPdfConverter(
                        PdfConversionConfig.builder()
                                .resourceResolver(memory)
                                .baseUri(URI.create("memory:/report.html"))
                                .diagnostics(d -> {
                                })
                                .build());
        try (org.apache.pdfbox.pdmodel.PDDocument d =
                Loader.loadPDF(
                        c.convert(
                                "<link rel='stylesheet' href='/css/main.css'><p>Fonte própria:"
                                        + " ação</p>"))) {
            assertTrue(new PDFTextStripper().getText(d).contains("Fonte própria"));
        }
    }
}
