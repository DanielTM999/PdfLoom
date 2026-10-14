package dtm.pdfloom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dtm.pdfloom.impl.HtmlPdfConverter;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class ContentAttributeTest {
    @Test
    void contentAttributeIsEvaluatedAsGeneratedContentWhileTagTextStaysLiteral()
            throws Exception {
        String html =
                "<head><meta name='description' content='counter(page)'></head>"
                        + "<style>section{break-before:page}</style>"
                        + "<p content=\"'Total: ' counter(pages)\"></p>"
                        + "<section><p content=\"counter(page) '/' counter(pages)\">"
                        + " counter(page)</p></section>"
                        + "<section><span data-label='Fim' content=\"attr(data-label) ' '"
                        + " counter(page)\"></span></section>";
        try (PDDocument document = Loader.loadPDF(new HtmlPdfConverter().convert(html))) {
            assertEquals(3, document.getNumberOfPages());
            assertTrue(text(document, 1).contains("Total: 3"));
            String second = text(document, 2);
            assertTrue(second.contains("2/3"));
            assertTrue(second.contains("counter(page)"));
            assertTrue(text(document, 3).contains("Fim 3"));
            assertFalse(new PDFTextStripper().getText(document).contains("description"));
        }
    }

    private String text(PDDocument document, int page) throws Exception {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        return stripper.getText(document);
    }
}
