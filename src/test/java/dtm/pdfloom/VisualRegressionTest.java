package dtm.pdfloom;

import static org.junit.jupiter.api.Assertions.*;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.Objects;

import javax.imageio.ImageIO;

class VisualRegressionTest {
    @Test
    void reviewedFourPageReportRemainsWithinPixelTolerance() throws Exception {
        Path output = Path.of("target/visual-regression");
        Files.createDirectories(output);
        byte[] bytes = VisualFixtures.pdf();
        Files.write(output.resolve("actual.pdf"), bytes);
        try (org.apache.pdfbox.pdmodel.PDDocument pdf = Loader.loadPDF(bytes)) {
            assertEquals(4, pdf.getNumberOfPages());
            PDFRenderer renderer = new PDFRenderer(pdf);
            for (int page = 0; page < 4; page++) {
                BufferedImage expected;
                try (java.io.InputStream in =
                        Objects.requireNonNull(
                                getClass()
                                        .getResourceAsStream(
                                                "/visual/reference/page-" + (page + 1) + ".png"))) {
                    expected = ImageIO.read(in);
                }
                BufferedImage actual = renderer.renderImageWithDPI(page, 144);
                ImageIO.write(
                        actual, "png", output.resolve("page-" + (page + 1) + ".png").toFile());
                assertEquals(expected.getWidth(), actual.getWidth());
                assertEquals(expected.getHeight(), actual.getHeight());
                BufferedImage diff =
                        new BufferedImage(
                                actual.getWidth(), actual.getHeight(), BufferedImage.TYPE_INT_RGB);
                long changed = 0;
                for (int y = 0; y < actual.getHeight(); y++) {
                    for (int x = 0; x < actual.getWidth(); x++) {
                        int a = actual.getRGB(x, y), b = expected.getRGB(x, y);
                        boolean different = false;
                        for (int shift : new int[] {0, 8, 16}) {
                            if (Math.abs(((a >> shift) & 255) - ((b >> shift) & 255)) > 12) {
                                different = true;
                            }
                        }
                        diff.setRGB(x, y, different ? 0xff0000 : 0xffffff);
                        if (different) {
                            changed++;
                        }
                    }
                }
                double ratio = changed / (double) (actual.getWidth() * actual.getHeight());
                if (ratio > .001) {
                    ImageIO.write(
                            diff,
                            "png",
                            output.resolve("diff-page-" + (page + 1) + ".png").toFile());
                }
                assertTrue(
                        ratio <= .001,
                        "Page "
                                + (page + 1)
                                + ": "
                                + changed
                                + " changed pixels ("
                                + ratio
                                + "); inspect target/visual-regression");
            }
            String text = new PDFTextStripper().getText(pdf);
            for (int i = 1; i <= 40; i++) {
                assertTrue(text.contains("Registro com acentuação " + i), "Missing row " + i);
            }
            for (int page = 1; page <= 4; page++) {
                assertTrue(text.contains("Página " + page + " de 4"));
            }

            new PDFTextStripper() {
                @Override
                protected void processTextPosition(TextPosition p) {
                    assertTrue(
                            p.getXDirAdj() >= -.1 && p.getXDirAdj() + p.getWidthDirAdj() <= 360.1,
                            p.getUnicode());
                    assertTrue(p.getYDirAdj() >= -.1 && p.getYDirAdj() <= 480.1, p.getUnicode());
                    super.processTextPosition(p);
                }
            }.getText(pdf);
        }
    }
}
