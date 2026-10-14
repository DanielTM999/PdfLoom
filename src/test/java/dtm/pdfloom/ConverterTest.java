package dtm.pdfloom;

import static org.junit.jupiter.api.Assertions.*;

import dtm.pdfloom.config.*;
import dtm.pdfloom.exception.PdfConversionException;
import dtm.pdfloom.impl.HtmlPdfConverter;
import dtm.pdfloom.resource.impl.*;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import javax.imageio.ImageIO;

class ConverterTest {
    @TempDir
    Path temp;
    static final String CSS =
            "<style>@page{size:300pt 400pt;margin:20pt;@bottom-center{content:'Página '"
                + " counter(page) ' de ' counter(pages);font-size:9pt}}"
                + " body{margin:0;font-size:10pt} p{margin:0 0 8pt} td,th{padding:3pt;border:1pt"
                + " solid #333} table{width:100%;border-collapse:collapse}</style>";

    PdfConverter converter() {
        return new HtmlPdfConverter(PdfConversionConfig.builder().diagnostics(d -> {
        }).build());
    }

    String text(byte[] pdf) throws IOException {
        try (PDDocument d = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(d);
        }
    }

    @Test
    void unicodeFontAndMetadata() throws Exception {
        byte[] pdf =
                new HtmlPdfConverter(
                                PdfConversionConfig.builder()
                                        .metadata("Título", "Autor", "Assunto")
                                        .build())
                        .convert(CSS + "<h1>Ação — São João</h1><p>€ © Ω</p>");
        try (PDDocument d = Loader.loadPDF(pdf)) {
            assertEquals("Título", d.getDocumentInformation().getTitle());
            assertTrue(new PDFTextStripper().getText(d).contains("São João"));
            for (org.apache.pdfbox.cos.COSName key : d.getPage(0).getResources().getFontNames()) {
                assertTrue(d.getPage(0).getResources().getFont(key).isEmbedded());
            }
        }
    }

    @Test
    void consumerStreamRemainsOpenAndAllOverloadsWork() throws Exception {
        class Stream extends ByteArrayInputStream {
            boolean closed;

            Stream() {
                super((CSS + "<p>Input</p>").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }

            public void close() {
                closed = true;
            }
        }
        Stream stream = new Stream();
        assertTrue(text(converter().convert(stream)).contains("Input"));
        assertFalse(stream.closed);
        Path source = temp.resolve("source.html");
        Files.writeString(source, CSS + "<p>Arquivo</p>");
        assertTrue(text(converter().convert(source.toFile())).contains("Arquivo"));
        Path a = temp.resolve("a.pdf"), b = temp.resolve("b.pdf"), c = temp.resolve("c.pdf");
        converter().convert(CSS + "string", a);
        converter().convert(source.toFile(), b);
        Stream second = new Stream();
        converter().convert(second, c);
        assertFalse(second.closed);
        assertTrue(Files.size(a) > 1000);
        assertTrue(Files.size(b) > 1000);
        assertTrue(Files.size(c) > 1000);
    }

    @Test
    void atomicDestinationPreservedOnFailure() throws Exception {
        Path target = temp.resolve("exists.pdf");
        Files.writeString(target, "original");
        assertThrows(
                PdfConversionException.class,
                () -> converter().convert("<img src='missing.png'>", target));
        assertEquals("original", Files.readString(target));
        try (java.util.stream.Stream<Path> paths = Files.list(temp)) {
            assertEquals(1, paths.count());
        }
        assertThrows(
                PdfConversionException.class,
                () -> converter().convert("x", temp.resolve("no/f.pdf")));
    }

    @Test
    void automaticPaginationAndTotals() throws Exception {
        StringBuilder html = new StringBuilder(CSS);
        for (int i = 0; i < 120; i++) {
            html.append("<p>Linha ").append(i).append(" com conteúdo e acentos.</p>");
        }
        byte[] result = converter().convert(html.toString());
        try (PDDocument d = Loader.loadPDF(result)) {
            assertTrue(d.getNumberOfPages() > 3);
            String t = new PDFTextStripper().getText(d);
            for (int i = 0; i < 120; i++) {
                assertTrue(t.contains("Linha " + i + " "), "Missing " + i);
            }
            assertTrue(t.contains("Página 1 de " + d.getNumberOfPages()));
            assertTrue(
                    t.contains("Página " + d.getNumberOfPages() + " de " + d.getNumberOfPages()));
        }
    }

    @Test
    void explicitBreakAndAvoid() throws Exception {
        String html =
                CSS
                        + "<p>Primeira</p><section"
                        + " style='break-before:page;break-inside:avoid'><h2>Segunda</h2><p>juntos</p></section>";
        try (PDDocument d = Loader.loadPDF(converter().convert(html))) {
            assertEquals(2, d.getNumberOfPages());
            PDFTextStripper t = new PDFTextStripper();
            t.setStartPage(2);
            t.setEndPage(2);
            assertTrue(t.getText(d).contains("Segunda"));
        }
    }

    @Test
    void longTableHeadersSpansAndRows() throws Exception {
        StringBuilder html =
                new StringBuilder(
                        CSS
                                + "<table><thead><tr><th"
                                + " colspan='2'>CABEÇALHO</th></tr></thead><tbody><tr><td"
                                + " rowspan='2'>SPAN</td><td>A</td></tr><tr><td>B</td></tr>");
        for (int i = 0; i < 200; i++) {
            html.append("<tr><td>REGISTRO-")
                    .append(i)
                    .append("</td><td>valor ")
                    .append(i)
                    .append("</td></tr>");
        }
        html.append("</tbody></table>");
        byte[] pdf = converter().convert(html.toString());
        try (PDDocument d = Loader.loadPDF(pdf)) {
            assertTrue(d.getNumberOfPages() > 3);
            PDFTextStripper t = new PDFTextStripper();
            String all = t.getText(d);
            assertTrue(all.contains("REGISTRO-199"));
            assertTrue(all.contains("SPAN"));
            for (int i = 1; i <= d.getNumberOfPages(); i++) {
                t.setStartPage(i);
                t.setEndPage(i);
                assertTrue(t.getText(d).contains("CABEÇALHO"), "Header absent page " + i);
            }
        }
    }

    @Test
    void oversizeCellFragmentsWithoutLoss() throws Exception {
        StringBuilder cell = new StringBuilder();
        for (int i = 0; i < 160; i++) {
            cell.append("<p>Fragmento ").append(i).append("</p>");
        }
        String html =
                CSS
                        + "<table><tr style='break-inside:avoid'><td>"
                        + cell
                        + "</td><td>Outro conteúdo</td></tr></table>";
        byte[] pdf = converter().convert(html);
        assertTrue(text(pdf).contains("Fragmento 159"));
    }

    @Test
    void sectionCountersAndPseudoElements() throws Exception {
        String html =
                CSS
                        + "<style>body{counter-reset:chapter}h2{counter-increment:chapter}h2::before{content:'Capítulo"
                        + " ' counter(chapter) ' — '}</style><h2>Introdução</h2><h2>Final</h2>";
        String t = text(converter().convert(html));
        assertTrue(t.contains("Capítulo 1"));
        assertTrue(t.contains("Capítulo 2"));
    }

    @Test
    void fixedContentAndPageVariants() throws Exception {
        String html =
                CSS
                        + "<style>@page:first{@bottom-center{content:'Primeira'}}@page:left{@bottom-center{content:'Par'}}@page:right{@bottom-center{content:'Ímpar'}}</style><div"
                        + " style='position:fixed;bottom:0;font-size:8pt'>FIXO</div><p>A</p><p"
                        + " style='break-before:page'>B</p><p style='break-before:page'>C</p>";
        try (PDDocument d = Loader.loadPDF(converter().convert(html))) {
            assertEquals(3, d.getNumberOfPages());
            PDFTextStripper t = new PDFTextStripper();
            for (int i = 1; i <= 3; i++) {
                t.setStartPage(i);
                t.setEndPage(i);
                String page = t.getText(d);
                assertTrue(page.contains("FIXO"));
                assertTrue(page.contains(i == 1 ? "Primeira" : i == 2 ? "Par" : "Ímpar"));
            }
        }
    }

    @Test
    void internalExternalLinksAndBookmarks() throws Exception {
        try (PDDocument d =
                Loader.loadPDF(
                        converter()
                                .convert(
                                        CSS
                                                + "<h1 id='start'>Título</h1><a"
                                                + " href='#start'>Voltar</a> <a"
                                                + " href='https://example.com'>Site</a>"))) {
            assertNotNull(d.getDocumentCatalog().getDocumentOutline());
            assertNotNull(d.getDocumentCatalog().getNames().getDests());
            assertEquals(2, d.getPage(0).getAnnotations().size());
        }
    }

    @Test
    void deepAndUnknownElements() throws Exception {
        String h =
                CSS
                        + "<custom-node>"
                        + "<div>".repeat(180)
                        + "Profundo"
                        + "</div>".repeat(180)
                        + "</custom-node>";
        assertTrue(text(converter().convert(h)).contains("Profundo"));
        assertThrows(
                PdfConversionException.class,
                () ->
                        new HtmlPdfConverter(PdfConversionConfig.builder().maxDepth(20).build())
                                .convert(h));
    }

    @Test
    void concurrentUseIsIsolated() throws Exception {
        PdfConverter c = converter();
        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            List<Future<String>> tasks = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                int n = i;
                tasks.add(pool.submit(() -> text(c.convert(CSS + "<p>ISOLADO-" + n + "</p>"))));
            }
            for (int i = 0; i < tasks.size(); i++) {
                assertTrue(tasks.get(i).get().contains("ISOLADO-" + i));
            }
        }
    }

    @Test
    void rasterizedOutputHasExpectedBackground() throws Exception {
        byte[] pdf =
                converter()
                        .convert(
                                CSS
                                        + "<div style='width:100pt;height:50pt;background:#24646d;padding:0;margin:0'>Visual</div>");
        try (PDDocument d = Loader.loadPDF(pdf)) {
            java.awt.image.BufferedImage image = new PDFRenderer(d).renderImageWithDPI(0, 144);
            java.awt.Color color = new java.awt.Color(image.getRGB(80, 80));
            assertEquals(0x24, color.getRed(), 2);
            assertEquals(0x64, color.getGreen(), 2);
            assertEquals(0x6d, color.getBlue(), 2);
            Path out = Path.of("target/test-artifacts");
            Files.createDirectories(out);
            ImageIO.write(image, "png", out.resolve("box.png").toFile());
        }
    }
}
