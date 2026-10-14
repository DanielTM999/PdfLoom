package dtm.pdfloom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dtm.pdfloom.config.PdfConversionConfig;
import dtm.pdfloom.impl.HtmlPdfConverter;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

class PagedDocumentRegressionTest {
    @TempDir
    Path folder;

    @Test
    void namedPageStartsWithoutBlankPageAndEndsBeforeFollowingContent() throws Exception {
        String html =
                "<style>@page{size:300pt 300pt;margin:30pt;@bottom-right{content:'P'"
                        + " counter(page)}}@page cover{margin:0}.cover{page:cover}"
                        + "section{break-before:page}</style><div class='cover'>COVER</div>"
                        + "<section>FIRST</section><section>SECOND</section>";
        try (PDDocument document = Loader.loadPDF(new HtmlPdfConverter().convert(html))) {
            assertEquals(3, document.getNumberOfPages());
            String first = text(document, 1);
            assertTrue(first.contains("COVER"));
            assertTrue(text(document, 2).contains("FIRST"));
            assertTrue(text(document, 2).contains("P2"));
            assertTrue(text(document, 3).contains("P3"));
        }
    }

    @Test
    void tableOfContentsWithForcedBreaksAndDescendingGlyphsPaginates() throws Exception {
        String html =
                "<style>section{break-before:page}</style><nav data-pdfloom-toc></nav>"
                        + "<section><h2>Seção ção A</h2></section><section><h2>Seção B</h2>"
                        + "</section>";
        try (PDDocument document = Loader.loadPDF(new HtmlPdfConverter().convert(html))) {
            assertEquals(3, document.getNumberOfPages());
            String contents = text(document, 1);
            assertTrue(contents.contains("Seção ção A"));
            assertTrue(contents.contains("Seção B"));
            assertEquals(1, new PDFTextStripper().getText(document).split("Seção B", -1).length - 2);
        }
    }

    @Test
    void baseDirectoryOutsideTheWorkingDirectoryResolvesRelativeImages() throws Exception {
        Path images = Files.createDirectories(folder.resolve("templates/img"));
        ImageIO.write(
                new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB),
                "png",
                images.resolve("logo.png").toFile());
        PdfConversionConfig config =
                PdfConversionConfig.builder().baseDirectory(folder.resolve("templates")).build();
        byte[] pdf = new HtmlPdfConverter(config).convert("<img src='img/logo.png'>");
        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertEquals(1, document.getNumberOfPages());
        }
    }

    private String text(PDDocument document, int page) throws Exception {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        return stripper.getText(document);
    }
}
