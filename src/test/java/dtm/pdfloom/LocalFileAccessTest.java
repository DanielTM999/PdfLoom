package dtm.pdfloom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dtm.pdfloom.config.Diagnostic;
import dtm.pdfloom.config.PdfConversionConfig;
import dtm.pdfloom.exception.PdfConversionException;
import dtm.pdfloom.impl.HtmlPdfConverter;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

class LocalFileAccessTest {
    @TempDir
    Path folder;

    @Test
    void defaultConfigurationKeepsAbsoluteFilesOutsideTheRootBlocked() throws Exception {
        Path image = image("blocked.png");
        assertThrows(
                PdfConversionException.class,
                () ->
                        new HtmlPdfConverter()
                                .convert("<img src='" + image.toUri() + "'>"));
    }

    @Test
    void allowLocalFilesReadsEveryCommonAbsoluteSpelling() throws Exception {
        Path directory = Files.createDirectories(folder.resolve("upload dir"));
        Path image = image(directory, "photo.png");
        String raw = image.toAbsolutePath().toString();
        String slashed = raw.replace('\\', '/');
        List<String> references =
                List.of(
                        image.toUri().toString(),
                        "file:///" + slashed,
                        "file:///" + raw,
                        "file://" + slashed,
                        raw);
        PdfConversionConfig config = PdfConversionConfig.builder().allowLocalFiles().build();
        for (String reference : references) {
            byte[] pdf = new HtmlPdfConverter(config).convert("<img src=\"" + reference + "\">");
            assertEquals(1, images(pdf), reference);
        }
    }

    @Test
    void allowLocalFilesResolvesRelativeReferencesAgainstAFileBase() throws Exception {
        Path directory = Files.createDirectories(folder.resolve("base"));
        image(Files.createDirectories(directory.resolve("img")), "logo.png");
        PdfConversionConfig config =
                PdfConversionConfig.builder()
                        .allowLocalFiles()
                        .baseDirectory(directory)
                        .build();
        byte[] pdf = new HtmlPdfConverter(config).convert("<img src='img\\logo.png'>");
        assertEquals(1, images(pdf));
    }

    @Test
    void missingAndUndecodableImagesAreSkippedWithWarnings() throws Exception {
        Path broken = Files.writeString(folder.resolve("broken.png"), "not an image");
        List<Diagnostic> diagnostics = new ArrayList<>();
        PdfConversionConfig config =
                PdfConversionConfig.builder()
                        .allowLocalFiles()
                        .missingResourcePolicy(
                                PdfConversionConfig.MissingResourcePolicy.WARN_AND_SKIP)
                        .diagnostics(diagnostics::add)
                        .build();
        byte[] pdf =
                new HtmlPdfConverter(config)
                        .convert(
                                "<p>TEXT</p><img src='file:///"
                                        + folder.resolve("absent.png").toString().replace('\\', '/')
                                        + "'><img src='"
                                        + broken.toUri()
                                        + "'><img src='data:image/png;base64,bnVsbA=='>");
        assertEquals(0, images(pdf));
        assertTrue(
                diagnostics.stream()
                        .anyMatch(diagnostic -> diagnostic.code().equals("MISSING_RESOURCE")));
        assertEquals(
                2,
                diagnostics.stream()
                        .filter(diagnostic -> diagnostic.code().equals("INVALID_RESOURCE"))
                        .count());
    }

    private Path image(String name) throws Exception {
        return image(folder, name);
    }

    private Path image(Path directory, String name) throws Exception {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        Path path = directory.resolve(name);
        ImageIO.write(image, "png", path.toFile());
        return path;
    }

    private int images(byte[] pdf) throws Exception {
        int count = 0;
        try (PDDocument document = Loader.loadPDF(pdf)) {
            for (COSName name :
                    document.getPage(0).getResources().getXObjectNames()) {
                if (document.getPage(0).getResources().getXObject(name)
                        instanceof PDImageXObject) {
                    count++;
                }
            }
        }
        return count;
    }
}
