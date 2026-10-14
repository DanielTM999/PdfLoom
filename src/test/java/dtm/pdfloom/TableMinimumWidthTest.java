package dtm.pdfloom;

import static org.junit.jupiter.api.Assertions.*;

import dtm.pdfloom.impl.HtmlPdfConverter;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;

import java.util.List;

class TableMinimumWidthTest {
    @Test
    void automaticTableDoesNotShrinkNowrapCellsBelowTheirContentMinimum() throws Exception {
        List<dtm.pdfloom.render.Paint.Text> texts =
                new ModernLayoutTest()
                        .texts(
                                "<style>body{margin:0}table{width:100pt;border-spacing:0}td{padding:0;white-space:nowrap;font:12pt"
                                    + " Arial}</style>"
                                    + "<table><tr><td>WWWWWWWWWW</td><td>SECOND</td></tr></table>");
        dtm.pdfloom.render.Paint.Text first =
                texts.stream()
                        .filter(text -> text.value().contains("WWWW"))
                        .findFirst()
                        .orElseThrow();
        dtm.pdfloom.render.Paint.Text second =
                texts.stream()
                        .filter(text -> text.value().equals("SECOND"))
                        .findFirst()
                        .orElseThrow();
        assertTrue(
                second.x() >= first.x() + first.width() - .01,
                "The following cell must start after the complete nowrap text");
    }

    @Test
    void automaticTableRespectsCellMinimumEvenWhenPreferredWidthsAlreadyFillTheTable()
            throws Exception {
        String html =
                "<style>@page{size:300pt"
                    + " 200pt;margin:20pt}body{margin:0}table{width:200pt;border-spacing:0}td{padding:0}td:first-child{width:50%;min-width:120pt}td:last-child{width:50%}</style><table><tr><td>FIRST</td><td>SECOND</td></tr></table>";
        try (PDDocument document = Loader.loadPDF(new HtmlPdfConverter().convert(html))) {
            String text =
                    new PDFTextStripper() {
                        @Override
                        protected void writeString(String value, List<TextPosition> positions)
                                throws java.io.IOException {
                            if (value.contains("SECOND")) {
                                TextPosition second =
                                        positions.stream()
                                                .filter(
                                                        position ->
                                                                position.getUnicode().equals("S"))
                                                .max(
                                                        java.util.Comparator.comparingDouble(
                                                                TextPosition::getXDirAdj))
                                                .orElseThrow();
                                assertTrue(
                                        second.getXDirAdj() >= 139.9,
                                        "The second cell must start after the first cell's 120pt"
                                                + " minimum");
                            }
                            super.writeString(value, positions);
                        }
                    }.getText(document);
            assertTrue(text.contains("FIRST"));
            assertTrue(text.contains("SECOND"));
        }
    }
}
