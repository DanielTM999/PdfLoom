package dtm.pdfloom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dtm.pdfloom.config.Diagnostic;
import dtm.pdfloom.config.PdfConversionConfig;
import dtm.pdfloom.impl.HtmlPdfConverter;
import dtm.pdfloom.render.Paint;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

class TableGeometryRegressionTest {
    @Test
    void sevenColumnAutomaticTableKeepsEveryNowrapCellApartAndReportsOverflow()
            throws Exception {
        StringBuilder html =
                new StringBuilder(
                        "<style>body{margin:0}table{width:100%;border-collapse:collapse}"
                                + "td{padding:2pt;border:1pt solid black;white-space:nowrap;"
                                + "font:10pt Arial}</style><table><tr>");
        for (int column = 0; column < 7; column++) {
            html.append("<td>COLUMN-").append(column).append("-NOWRAP-VALUE</td>");
        }
        html.append("</tr></table>");
        List<Diagnostic> diagnostics = new ArrayList<>();
        PdfConversionConfig config =
                PdfConversionConfig.builder().diagnostics(diagnostics::add).build();
        new HtmlPdfConverter(config).convert(html.toString());
        assertTrue(
                diagnostics.stream()
                        .anyMatch(
                                diagnostic ->
                                        diagnostic.code().equals("TABLE_MIN_CONTENT_OVERFLOW")
                                                && diagnostic.message().contains("requires")
                                                && diagnostic.message().contains("available")));
        List<Paint.Text> texts =
                new ModernLayoutTest()
                        .texts(html.toString()).stream()
                                .filter(text -> text.value().startsWith("COLUMN-"))
                                .sorted(Comparator.comparingDouble(Paint.Text::x))
                                .toList();
        assertEquals(7, texts.size());
        for (int index = 1; index < texts.size(); index++) {
            Paint.Text previous = texts.get(index - 1);
            assertTrue(
                    texts.get(index).x() >= previous.x() + previous.width() + 4 - .01,
                    "Cell " + index + " overlaps the previous nowrap cell");
        }
    }

    @Test
    void fixedTableResolvesPercentagesAgainstTheTableWithBorderBoxCells() throws Exception {
        List<Paint.Text> texts =
                new ModernLayoutTest()
                        .texts(
                                "<style>body{margin:0}table{width:200pt;table-layout:fixed;"
                                    + "border-spacing:0}td{box-sizing:border-box;padding:0 4pt;"
                                    + "border-left:2pt solid black;font:10pt Arial}"
                                    + ".a{width:25%}.b{width:40%}</style><table><tr>"
                                    + "<td class='a'>A</td><td class='b'>B</td><td>C</td>"
                                    + "</tr></table>");
        assertEquals(6, texts.get(0).x(), .01);
        assertEquals(56, texts.get(1).x(), .01);
        assertEquals(136, texts.get(2).x(), .01);
    }

    @Test
    void collapsedCellIsInsetByHalfOfTheWinningBorder() throws Exception {
        List<Paint.Text> texts =
                new ModernLayoutTest()
                        .texts(
                                "<style>body{margin:0}table{width:200pt;border-collapse:collapse;"
                                    + "border:4pt solid black}td{padding:3pt;border:1pt solid #999;"
                                    + "font:9pt Arial}</style><table><tr><td>A</td><td>B</td></tr>"
                                    + "</table>");
        assertEquals(7, texts.get(0).x(), .01);
        assertEquals(103.5, texts.get(1).x(), .01);
    }

    @Test
    void fixedTableWithContentBoxCellsStaysWithinTheTableWithoutOverlap() throws Exception {
        List<Paint.Text> texts =
                new ModernLayoutTest()
                        .texts(
                                "<style>body{margin:0}table{width:200pt;table-layout:fixed;"
                                    + "border-spacing:0}td{padding:0 4pt;border:1pt solid black;"
                                    + "font:10pt Arial;overflow-wrap:anywhere}"
                                    + ".a{width:25%}.b{width:40%}</style><table><tr>"
                                    + "<td class='a'>FIRST</td><td class='b'>SECOND</td>"
                                    + "<td>THIRD</td></tr></table>");
        assertEquals(3, texts.size());
        for (int index = 1; index < texts.size(); index++) {
            Paint.Text previous = texts.get(index - 1);
            assertTrue(texts.get(index).x() >= previous.x() + previous.width());
        }
        Paint.Text last = texts.get(texts.size() - 1);
        assertTrue(last.x() + last.width() <= 200 + .01);
    }

    @Test
    void repeatedFooterAppearsOncePerPageAndRowsAreNotDuplicated() throws Exception {
        StringBuilder html =
                new StringBuilder(
                        "<style>@page{size:240pt 180pt;margin:20pt}body{margin:0}"
                                + "table{width:100%;border-collapse:collapse}"
                                + "td,th{padding:3pt;border:1pt solid black;font:9pt Arial}"
                                + "tr{break-inside:avoid}</style><table><thead><tr><th>HEAD"
                                + "</th></tr></thead><tbody>");
        for (int index = 0; index < 60; index++) {
            html.append("<tr><td>ROW-").append(index).append("</td></tr>");
        }
        html.append("</tbody><tfoot><tr><td>FOOT</td></tr></tfoot></table><p>AFTER-TABLE</p>");
        try (PDDocument document =
                Loader.loadPDF(new HtmlPdfConverter().convert(html.toString()))) {
            assertTrue(document.getNumberOfPages() >= 6);
            PDFTextStripper stripper = new PDFTextStripper();
            String all = stripper.getText(document);
            for (int index = 0; index < 60; index++) {
                assertEquals(1, all.split("ROW-" + index + "(?![0-9])", -1).length - 1);
            }
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(document);
                if (text.contains("ROW-")) {
                    assertEquals(1, text.split("FOOT", -1).length - 1, "Page " + page);
                    assertEquals(1, text.split("HEAD", -1).length - 1, "Page " + page);
                }
            }
            assertEquals(1, all.split("AFTER-TABLE", -1).length - 1);
        }
    }

    @Test
    void outerTableBordersSurviveOnEveryContinuationPage() throws Exception {
        StringBuilder html =
                new StringBuilder(
                        "<style>@page{size:240pt 180pt;margin:20pt}body{margin:0}"
                                + "table{width:100%;border-collapse:collapse;border:2pt solid black}"
                                + "td,th{padding:3pt;border:1pt solid #999;font:9pt Arial}"
                                + "tr{break-inside:avoid}</style><table><thead><tr><th>HEAD"
                                + "</th><th>SIDE</th></tr></thead><tbody>");
        for (int index = 0; index < 70; index++) {
            html.append("<tr><td>ROW-")
                    .append(index)
                    .append("</td><td>VALUE</td></tr>");
        }
        html.append("</tbody></table>");
        try (PDDocument document =
                Loader.loadPDF(new HtmlPdfConverter().convert(html.toString()))) {
            assertTrue(document.getNumberOfPages() >= 6);
            PDFRenderer renderer = new PDFRenderer(document);
            for (int page = 0; page < document.getNumberOfPages(); page++) {
                BufferedImage image = renderer.renderImageWithDPI(page, 144);
                int middle = 180;
                assertTrue(darkColumn(image, 36, 46, middle), "Left border on page " + (page + 1));
                assertTrue(
                        darkColumn(image, 434, 444, middle), "Right border on page " + (page + 1));
                int header = 56;
                assertEquals(
                        darkWidth(image, 30, 52, middle),
                        darkWidth(image, 30, 52, header),
                        "Left header border on page " + (page + 1));
                assertEquals(
                        darkWidth(image, 428, 450, middle),
                        darkWidth(image, 428, 450, header),
                        "Right header border on page " + (page + 1));
            }
        }
    }

    private int darkWidth(BufferedImage image, int from, int to, int y) {
        int dark = 0;
        for (int x = from; x <= to; x++) {
            int rgb = image.getRGB(x, y);
            if ((rgb >> 16 & 255) < 60 && (rgb >> 8 & 255) < 60 && (rgb & 255) < 60) {
                dark++;
            }
        }
        return dark;
    }

    private boolean darkColumn(BufferedImage image, int from, int to, int y) {
        for (int x = from; x <= to; x++) {
            int rgb = image.getRGB(x, y);
            if ((rgb >> 16 & 255) < 60 && (rgb >> 8 & 255) < 60 && (rgb & 255) < 60) {
                return true;
            }
        }
        return false;
    }
}
