import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

import java.nio.file.Path;

public class SmokeCheck {
    public static void main(String[] paths) throws Exception {
        for (String path : paths) {
            try (org.apache.pdfbox.pdmodel.PDDocument document =
                    Loader.loadPDF(Path.of(path).toFile())) {
                if (document.getNumberOfPages() != 2) {
                    throw new AssertionError("Expected two pages: " + path);
                }
                if (!"Relatório PdfLoom".equals(document.getDocumentInformation().getTitle())) {
                    throw new AssertionError("Metadata lost");
                }
                String text = new PDFTextStripper().getText(document);
                if (!text.contains("página 2")
                        || !text.contains("Página 2 de 2")
                        || !text.contains("€")) {
                    throw new AssertionError("Text/counters lost");
                }
                if (document.getDocumentCatalog().getDocumentOutline() == null) {
                    throw new AssertionError("Bookmarks lost");
                }
                if (document.getPage(0).getAnnotations().isEmpty()) {
                    throw new AssertionError("Links lost");
                }
                for (org.apache.pdfbox.pdmodel.PDPage page : document.getPages()) {
                    for (org.apache.pdfbox.cos.COSName key : page.getResources().getFontNames()) {
                        if (!page.getResources().getFont(key).isEmbedded()) {
                            throw new AssertionError("Font not embedded");
                        }
                    }
                }
                new PDFRenderer(document).renderImageWithDPI(0, 72);
                System.out.println(
                        "Validated distribution PDF: "
                                + path
                                + " (2 pages, fonts, links, counters, raster)");
            }
        }
    }
}
