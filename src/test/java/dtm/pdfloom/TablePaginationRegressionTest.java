package dtm.pdfloom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dtm.pdfloom.exception.PdfConversionException;
import dtm.pdfloom.impl.HtmlPdfConverter;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

class TablePaginationRegressionTest {
    @Test
    void oversizedCellFragmentsAtTextLinesWithoutLossOrDuplication() throws Exception {
        StringBuilder lines = new StringBuilder();
        for (int index = 0; index < 70; index++) {
            lines.append("CELL-LINE-").append(index).append("<br>");
        }
        String html =
                "<style>@page{size:240pt 180pt;margin:20pt}body{margin:0}"
                        + "table{width:100%;border-collapse:collapse}"
                        + "td,th{font:10pt/1.4 Arial;padding:4pt;border:1pt solid black}"
                        + "tr{break-inside:avoid}</style><table><thead><tr><th>HEADING</th>"
                        + "</tr></thead><tbody><tr><td>"
                        + lines
                        + "</td></tr></tbody></table>";
        try (PDDocument document = Loader.loadPDF(new HtmlPdfConverter().convert(html))) {
            assertTrue(document.getNumberOfPages() >= 6);
            String text = new PDFTextStripper().getText(document);
            for (int index = 0; index < 70; index++) {
                assertEquals(1, text.split("CELL-LINE-" + index + "(?![0-9])", -1).length - 1);
            }
            assertEquals(document.getNumberOfPages(), text.split("HEADING", -1).length - 1);
        }
    }

    @Test
    void successiveTablesRepeatOnlyTheirOwnHeaders() throws Exception {
        StringBuilder html =
                new StringBuilder(
                        "<style>@page{size:240pt 180pt;margin:20pt}"
                                + "body{margin:0}table{width:100%}td,th{padding:5pt}"
                                + "tr{break-inside:avoid}</style>");
        for (String name : new String[] {"ALPHA", "BETA"}) {
            html.append("<table><thead><tr><th>")
                    .append(name)
                    .append("-HEADER</th></tr></thead><tbody>");
            for (int index = 0; index < 22; index++) {
                html.append("<tr><td>")
                        .append(name)
                        .append("-ROW-")
                        .append(index)
                        .append("</td></tr>");
            }
            html.append("</tbody></table>");
        }
        try (PDDocument document =
                Loader.loadPDF(new HtmlPdfConverter().convert(html.toString()))) {
            PDFTextStripper stripper = new PDFTextStripper();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(document);
                if (text.contains("BETA-ROW") && !text.contains("ALPHA-ROW")) {
                    assertTrue(text.contains("BETA-HEADER"));
                    assertTrue(!text.contains("ALPHA-HEADER"));
                }
            }
        }
    }

    @Test
    void oversizedRepeatedHeaderFailsClearlyInsteadOfLooping() {
        PdfConversionException error =
                assertThrows(
                        PdfConversionException.class,
                        () ->
                                new HtmlPdfConverter()
                                        .convert(
                                                "<style>@page{size:240pt 180pt;margin:20pt}"
                                                    + "body{margin:0}th{height:180pt}td{height:100pt}</style>"
                                                    + "<table><thead><tr><th>HEADER</th></tr></thead>"
                                                    + "<tbody><tr><td>FIRST</td></tr><tr><td>SECOND</td></tr></tbody></table>"));
        assertTrue(error.getMessage().contains("header"));
    }

    @Test
    void continuingBodyCannotPaintOverRepeatedHeader() throws Exception {
        StringBuilder rows = new StringBuilder();
        for (int index = 0; index < 45; index++) {
            rows.append("<tr><td>RECORD-")
                    .append(index)
                    .append("</td><td><div style='height:")
                    .append(index % 2 == 0 ? 35 : 47)
                    .append("pt'>VALUE-")
                    .append(index)
                    .append("</div></td></tr>");
        }
        String html =
                "<style>@page{size:300pt 240pt;margin:20pt}body{margin:0}"
                        + "table{width:100%;border-collapse:collapse;background:#ffeedd}"
                        + "th{background:#2266aa;color:white;padding:8pt;font:12pt Arial}"
                        + "td{padding:3pt;border:1pt solid black}"
                        + "tr{break-inside:avoid}</style><table><thead><tr>"
                        + "<th>HEADER<br>SECOND LINE</th><th>RIGHT<br>SECOND LINE</th>"
                        + "</tr></thead><tbody>"
                        + rows
                        + "</tbody></table>";
        Path output = Path.of("target/table-pagination-regression");
        Files.createDirectories(output);
        byte[] pdf = new HtmlPdfConverter().convert(html);
        Files.write(output.resolve("headers.pdf"), pdf);
        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertTrue(document.getNumberOfPages() >= 6);
            PDFRenderer renderer = new PDFRenderer(document);
            BufferedImage first = renderer.renderImageWithDPI(0, 144);
            for (int page = 0; page < document.getNumberOfPages(); page++) {
                BufferedImage actual = renderer.renderImageWithDPI(page, 144);
                ImageIO.write(
                        actual, "png", output.resolve("page-" + (page + 1) + ".png").toFile());
                int different = 0;
                int pixels = 0;
                for (int y = 43; y < 123; y++) {
                    for (int x = 43; x < 555; x++) {
                        int expected = first.getRGB(x, y);
                        int observed = actual.getRGB(x, y);
                        for (int channel : new int[] {0, 8, 16}) {
                            if (Math.abs((expected >> channel & 255) - (observed >> channel & 255))
                                    > 12) {
                                different++;
                                break;
                            }
                        }
                        pixels++;
                    }
                }
                assertTrue(
                        (double) different / pixels <= .001,
                        "Header changed on page " + (page + 1) + ": " + different);
            }
            String text = new PDFTextStripper().getText(document);
            for (int index = 0; index < 45; index++) {
                assertEquals(1, text.split("RECORD-" + index + "(?![0-9])", -1).length - 1);
            }
        }
    }
}
