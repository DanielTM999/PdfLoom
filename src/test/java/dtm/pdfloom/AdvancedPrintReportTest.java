package dtm.pdfloom;

import static org.junit.jupiter.api.Assertions.*;

import dtm.pdfloom.impl.HtmlPdfConverter;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

class AdvancedPrintReportTest {
    @Test
    void integratedPrintExampleKeepsInlineReferencesBeforeForcedChapterBreaks() throws Exception {
        String html = Files.readString(Path.of("src/test/resources/examples/advanced-print.html"));
        try (PDDocument document = Loader.loadPDF(new HtmlPdfConverter().convert(html))) {
            assertEquals(3, document.getNumberOfPages());
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("Consultar os detalhes"), text);
            assertTrue(text.contains("Voltar ao resumo"), text);
            assertEquals(3, text.split("DADOS DEMONSTRATIVOS", -1).length - 1);
            assertTrue(text.contains("capítulo 2, página 3"), text);
            assertTrue(text.contains("capítulo 1, página 2"), text);
            PDFTextStripper firstPage =
                    new PDFTextStripper() {
                        @Override
                        protected void processTextPosition(
                                org.apache.pdfbox.text.TextPosition position) {
                            if (position.getYDirAdj() > 90 && position.getYDirAdj() < 170) {
                                assertTrue(position.getXDirAdj() >= 39.9);
                                assertTrue(
                                        position.getXDirAdj() + position.getWidthDirAdj() <= 555.4);
                            }
                            super.processTextPosition(position);
                        }
                    };
            firstPage.setStartPage(1);
            firstPage.setEndPage(1);
            String toc = firstPage.getText(document);
            assertTrue(toc.matches("(?s).*Resumo executivo\\s+2.*"), toc);
            assertTrue(toc.matches("(?s).*Detalhes da análise\\s+3.*"), toc);
        }
    }
}
