package dtm.pdfloom;

import static org.junit.jupiter.api.Assertions.*;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

class CompositionVisualTest {
    @Test
    void reviewedStackingFlexGridAndComplexTypographyRemainWithinTolerance() throws Exception {
        byte[] bytes = CompositionVisualFixtures.pdf();
        Path output = Path.of("target/composition-visual");
        Files.createDirectories(output);
        Files.write(output.resolve("actual.pdf"), bytes);
        try (PDDocument document = Loader.loadPDF(bytes);
                InputStream source =
                        getClass().getResourceAsStream("/visual/reference/composition-1.png")) {
            assertEquals(1, document.getNumberOfPages());
            BufferedImage expected = ImageIO.read(source);
            BufferedImage actual = new PDFRenderer(document).renderImageWithDPI(0, 144);
            assertEquals(expected.getWidth(), actual.getWidth());
            assertEquals(expected.getHeight(), actual.getHeight());
            BufferedImage difference =
                    new BufferedImage(
                            actual.getWidth(), actual.getHeight(), BufferedImage.TYPE_INT_RGB);
            int changed = 0;
            for (int y = 0; y < actual.getHeight(); y++) {
                for (int x = 0; x < actual.getWidth(); x++) {
                    int before = expected.getRGB(x, y);
                    int after = actual.getRGB(x, y);
                    boolean different = false;
                    for (int shift : new int[] {0, 8, 16}) {
                        if (Math.abs((before >> shift & 255) - (after >> shift & 255)) > 12) {
                            different = true;
                        }
                    }
                    difference.setRGB(x, y, different ? 0xFF0000 : 0xFFFFFF);
                    if (different) {
                        changed++;
                    }
                }
            }
            ImageIO.write(actual, "png", output.resolve("actual.png").toFile());
            ImageIO.write(difference, "png", output.resolve("difference.png").toFile());
            double fraction = (double) changed / actual.getWidth() / actual.getHeight();
            assertTrue(fraction <= .001, "Changed pixels: " + changed + "; fraction: " + fraction);
            String text = new PDFTextStripper().getText(document);
            assertTrue(
                    text.contains("180pt") && text.contains("95pt") && text.contains("Acentuação"));
            assertTrue(document.getPage(0).getResources().getFontNames().iterator().hasNext());
        }
    }
}
