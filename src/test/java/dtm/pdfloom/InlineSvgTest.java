package dtm.pdfloom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dtm.pdfloom.impl.HtmlPdfConverter;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

class InlineSvgTest {
    @Test
    void htmlInlineSvgWithoutNamespaceDeclarationsIsRendered() throws Exception {
        String html =
                "<style>@page{size:200pt 200pt;margin:0}body{margin:0}</style>"
                        + "<svg width='100' height='40'><defs><rect id='box' width='100'"
                        + " height='40' fill='#ff0000'/></defs><use xlink:href='#box'/></svg>"
                        + "<p>AFTER SVG</p>";
        try (PDDocument document = Loader.loadPDF(new HtmlPdfConverter().convert(html))) {
            assertEquals(1, document.getNumberOfPages());
            assertTrue(new PDFTextStripper().getText(document).contains("AFTER SVG"));
            BufferedImage image = new PDFRenderer(document).renderImageWithDPI(0, 72);
            int rgb = image.getRGB(20, 10);
            assertTrue((rgb >> 16 & 255) > 200 && (rgb >> 8 & 255) < 60 && (rgb & 255) < 60);
        }
    }
}
