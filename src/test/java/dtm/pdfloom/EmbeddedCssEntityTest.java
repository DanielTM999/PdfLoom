package dtm.pdfloom;

import static org.junit.jupiter.api.Assertions.*;

import dtm.pdfloom.config.Diagnostic;
import dtm.pdfloom.config.PdfConversionConfig;
import dtm.pdfloom.css.CascadeStyleResolver;
import dtm.pdfloom.html.HtmlNode;
import dtm.pdfloom.html.HtmlParser;
import dtm.pdfloom.impl.HtmlPdfConverter;
import dtm.pdfloom.resource.ResourceLoader;
import dtm.pdfloom.resource.impl.MemoryResourceResolver;
import dtm.pdfloom.style.StyleSheet;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.imageio.ImageIO;

class EmbeddedCssEntityTest {
    @TempDir
    Path directory;

    @Test
    void namedAndNumericEntitiesRestoreQuotedContentAndSelectors() throws Exception {
        for (String quote : List.of("&quot;", "&#34;", "&#x22;", "&apos;", "&#39;", "&#x27;")) {
            List<Diagnostic> diagnostics = new ArrayList<>();
            PdfConversionConfig config = PdfConversionConfig.builder().diagnostics(diagnostics::add).build();
            String html = "<style>div &gt; p::before{content:"
                    + quote + "Texto restaurado " + quote + ";}</style><div><p>Registro</p></div>";
            try (PDDocument document = Loader.loadPDF(new HtmlPdfConverter(config).convert(html))) {
                assertTrue(new PDFTextStripper().getText(document).contains("Texto restaurado Registro"));
            }
            assertTrue(diagnostics.isEmpty(), diagnostics.toString());
        }
    }

    @Test
    void validCssKeepsLiteralEntitiesInsideStrings() throws Exception {
        String html = "<style>p::before{content:'&quot; &amp; &#34; ';}</style><p>Texto</p>";
        try (PDDocument document = Loader.loadPDF(new HtmlPdfConverter().convert(html))) {
            assertTrue(new PDFTextStripper().getText(document).contains("&quot; &amp; &#34; Texto"));
        }
    }

    @Test
    void decodedCssDoesNotDecodeEntitiesTwice() throws Exception {
        List<Diagnostic> diagnostics = new ArrayList<>();
        String html = "<style>p::before{content:&quot;&amp;quot;literal&amp;quot; &amp;amp;&quot;;}</style><p>Texto</p>";
        PdfConversionConfig config = PdfConversionConfig.builder().diagnostics(diagnostics::add).build();
        try (PDDocument document = Loader.loadPDF(new HtmlPdfConverter(config).convert(html))) {
            assertTrue(new PDFTextStripper().getText(document).contains("&quot;literal&quot; &amp;Texto"));
        }
        assertTrue(diagnostics.isEmpty(), diagnostics.toString());
    }

    @Test
    void unrecoverableCssKeepsOriginalDiagnostics() throws Exception {
        List<Diagnostic> diagnostics = new ArrayList<>();
        PdfConversionConfig config = PdfConversionConfig.builder().diagnostics(diagnostics::add).build();
        String html = "<style>p::before{content:&quot;Texto&quot;;}p{color:;}</style><p>Registro</p>";
        try (PDDocument document = Loader.loadPDF(new HtmlPdfConverter(config).convert(html))) {
            assertTrue(new PDFTextStripper().getText(document).contains("Registro"));
        }
        assertTrue(diagnostics.stream().anyMatch(diagnostic -> diagnostic.code().equals("CSS_PARSE_RECOVERY")));
    }

    @Test
    void externalStyleSheetsRemainUnchanged() throws Exception {
        List<Diagnostic> diagnostics = new ArrayList<>();
        PdfConversionConfig config = PdfConversionConfig.builder()
                .resourceResolver(new MemoryResourceResolver(Map.of(
                        "report.css", "p::before{content:&quot;Externo&quot;;}".getBytes(StandardCharsets.UTF_8))))
                .diagnostics(diagnostics::add)
                .build();
        try (PDDocument document = Loader.loadPDF(new HtmlPdfConverter(config)
                .convert("<link rel='stylesheet' href='report.css'><p>Registro</p>"))) {
            assertFalse(new PDFTextStripper().getText(document).contains("Externo"));
        }
        assertTrue(diagnostics.stream().anyMatch(diagnostic -> diagnostic.code().equals("CSS_PARSE_RECOVERY")));
    }

    @Test
    void normalHtmlAttributesAndBodyAreNotDecodedAgain() throws Exception {
        PdfConversionConfig config = PdfConversionConfig.builder().build();
        HtmlParser.Document document = new HtmlParser().parse(
                "<style>p{color:red;}</style><p id='item' title='&amp;quot;'>A &amp;amp; B</p>", config);
        HtmlNode paragraph = document.elements().stream()
                .filter(element -> element.attr("id").equals("item")).findFirst().orElseThrow();
        try (ResourceLoader loader = new ResourceLoader(config)) {
            StyleSheet sheet = new CascadeStyleResolver(config, loader).resolve(document, null);
            assertEquals("red", sheet.style(paragraph).get("color"));
            assertEquals("&quot;", paragraph.attr("title"));
            assertEquals("A &amp; B", paragraph.textContent());
        }
    }

    @Test
    void monthlyTableReportKeepsImagesHeadersTotalsAndEveryFooter() throws Exception {
        Files.createDirectories(directory.resolve("assets"));
        BufferedImage logo = new BufferedImage(48, 24, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = logo.createGraphics();
        graphics.setColor(new Color(36, 100, 109));
        graphics.fillRect(0, 0, 48, 24);
        graphics.dispose();
        ImageIO.write(logo, "png", directory.resolve("assets/logo.png").toFile());
        String template;
        try (InputStream input = Objects.requireNonNull(getClass().getResourceAsStream("/examples/monthly-escaped-css.html"))) {
            template = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        StringBuilder rows = new StringBuilder();
        for (int index = 1; index <= 100; index++) {
            rows.append("<tr><td>DOC-").append(String.format("%04d", index))
                    .append("</td><td>Inspeção</td><td>João</td><td>ABC1234</td>")
                    .append("<td>9XY12345678901234</td><td>Veículo Exemplo</td>")
                    .append("<td>13/10/2026</td><td>R$ 100,00</td></tr>");
        }
        List<Diagnostic> diagnostics = new ArrayList<>();
        PdfConversionConfig config = PdfConversionConfig.builder()
                .baseDirectory(directory).diagnostics(diagnostics::add).build();
        byte[] bytes = new HtmlPdfConverter(config).convert(template.replace("{{rows}}", rows));
        Path output = Files.createDirectories(Path.of("target/isolated/monthly-review"));
        Files.write(output.resolve("monthly-report.pdf"), bytes);
        try (PDDocument document = Loader.loadPDF(bytes)) {
            assertTrue(document.getNumberOfPages() > 1);
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            for (int index = 1; index <= 100; index++) {
                assertTrue(text.contains(String.format("DOC-%04d", index)));
            }
            assertTrue(text.contains("Total: R$ 10.000,00"));
            assertTrue(text.contains("Descontos: R$ 500,00"));
            assertTrue(text.contains("Líquido: R$ 9.500,00"));
            boolean foundImage = false;
            for (PDPage page : document.getPages()) {
                for (org.apache.pdfbox.cos.COSName name : page.getResources().getXObjectNames()) {
                    foundImage |= page.getResources().getXObject(name) instanceof PDImageXObject;
                }
            }
            assertTrue(foundImage);
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = stripper.getText(document);
                assertTrue(pageText.contains("Página " + page + " de " + document.getNumberOfPages()));
                if (pageText.contains("DOC-")) {
                    assertTrue(pageText.contains("Documento"));
                    assertTrue(pageText.contains("Responsável"));
                }
            }
            PDFRenderer renderer = new PDFRenderer(document);
            for (int page : new int[] {0, document.getNumberOfPages() - 1}) {
                ImageIO.write(renderer.renderImageWithDPI(page, 100), "png",
                        output.resolve("page-" + (page + 1) + ".png").toFile());
            }
        }
        assertFalse(diagnostics.stream().anyMatch(diagnostic -> diagnostic.code().equals("CSS_PARSE_RECOVERY")),
                diagnostics.toString());
    }
}
