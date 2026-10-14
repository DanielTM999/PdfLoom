package dtm.pdfloom;

import dtm.pdfloom.config.*;
import dtm.pdfloom.impl.HtmlPdfConverter;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.nio.file.*;
import java.util.*;

import javax.imageio.ImageIO;

public final class VisualFixtures {
    public static byte[] pdf() throws Exception {
        String template;
        try (java.io.InputStream in =
                VisualFixtures.class.getResourceAsStream("/visual/regression.html")) {
            template =
                    new String(
                            Objects.requireNonNull(in).readAllBytes(),
                            java.nio.charset.StandardCharsets.UTF_8);
        }
        StringBuilder rows = new StringBuilder();
        for (int i = 1; i <= 40; i++) {
            rows.append("<tr><td>")
                    .append(i)
                    .append("</td><td>Registro com acentuação ")
                    .append(i)
                    .append("</td><td>R$ 100,00</td></tr>");
        }
        return new HtmlPdfConverter(PdfConversionConfig.builder().diagnostics(d -> {
        }).build())
                .convert(template.replace("<!--ROWS-->", rows));
    }

    public static void main(String[] args) throws Exception {
        Path output = Path.of("target/visual-review");
        Files.createDirectories(output);
        byte[] bytes = pdf();
        Files.write(output.resolve("regression.pdf"), bytes);
        try (org.apache.pdfbox.pdmodel.PDDocument pdf = Loader.loadPDF(bytes)) {
            PDFRenderer renderer = new PDFRenderer(pdf);
            for (int i = 0; i < pdf.getNumberOfPages(); i++) {
                ImageIO.write(
                        renderer.renderImageWithDPI(i, 144),
                        "png",
                        output.resolve("page-" + (i + 1) + ".png").toFile());
            }
            System.out.println("Review pages: " + pdf.getNumberOfPages());
        }
    }
}
