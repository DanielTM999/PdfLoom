package dtm.pdfloom;

import static org.junit.jupiter.api.Assertions.*;

import dtm.pdfloom.config.PdfConversionConfig;
import dtm.pdfloom.impl.HtmlPdfConverter;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.List;

class MarginBoxLayoutTest {
    @Test
    void runningFooterAlignmentAndPaddingKeepItOutsideTheFlow() throws Exception {
        for (String align : List.of("top", "middle", "bottom")) {
            String html =
                    "<style>@page{size:300pt 240pt;margin:20pt 20pt"
                            + " 60pt;@bottom-center{content:element(footer);vertical-align:"
                            + align
                            + ";padding-top:5pt;padding-bottom:10pt}}body{margin:0}footer{position:running(footer);"
                            + "height:20pt;background:blue}td{height:30pt;padding:0}</style><footer>FOOTER</footer><table>";
            StringBuilder rows = new StringBuilder(html);
            for (int row = 0; row < 18; row++) {
                rows.append("<tr><td>ROW").append(row).append("</td></tr>");
            }
            rows.append("</table>");
            PdfConversionConfig config =
                    PdfConversionConfig.builder().diagnostics(diagnostic -> {
                    }).build();
            try (PDDocument document =
                    Loader.loadPDF(new HtmlPdfConverter(config).convert(rows.toString()))) {
                assertTrue(document.getNumberOfPages() > 1);
                PDFRenderer renderer = new PDFRenderer(document);
                int expected = align.equals("top") ? 185 : align.equals("middle") ? 197 : 210;
                for (int page = 0; page < document.getNumberOfPages(); page++) {
                    Color color =
                            new Color(
                                    renderer.renderImageWithDPI(page, 72)
                                            .getRGB(200, expected + 5));
                    assertTrue(color.getBlue() > 240 && color.getRed() < 10, align);
                }
                PDFTextStripper reader =
                        new PDFTextStripper() {
                            @Override
                            protected void writeString(String value, List<TextPosition> positions)
                                    throws java.io.IOException {
                                if (value.startsWith("ROW")) {
                                    for (TextPosition position : positions) {
                                        assertTrue(position.getYDirAdj() < 180);
                                    }
                                }
                                super.writeString(value, positions);
                            }
                        };
                String text = reader.getText(document);
                for (int row = 0; row < 18; row++) {
                    assertTrue(text.contains("ROW" + row));
                }
            }
        }
    }

    @Test
    void textMarginBoxHonorsBottomAlignmentAndPadding() throws Exception {
        PdfConversionConfig config =
                PdfConversionConfig.builder().diagnostics(diagnostic -> {
                }).build();
        String html =
                "<style>@page{size:300pt 240pt;margin:20pt 20pt"
                    + " 60pt;@bottom-center{content:'BOTTOM';"
                    + "vertical-align:bottom;padding-bottom:10pt;font-size:10pt}}body{margin:0}</style><p>BODY</p>";
        try (PDDocument document = Loader.loadPDF(new HtmlPdfConverter(config).convert(html))) {
            new PDFTextStripper() {
                @Override
                protected void processTextPosition(TextPosition position) {
                    if (position.getUnicode().equals("B") && position.getYDirAdj() > 180) {
                        assertTrue(position.getYDirAdj() > 225 && position.getYDirAdj() <= 230);
                    }
                    super.processTextPosition(position);
                }
            }.getText(document);
        }
    }
}
