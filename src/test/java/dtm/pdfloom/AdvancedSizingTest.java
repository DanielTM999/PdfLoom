package dtm.pdfloom;

import static org.junit.jupiter.api.Assertions.*;

import dtm.pdfloom.config.PdfConversionConfig;
import dtm.pdfloom.impl.HtmlPdfConverter;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;

import java.awt.Color;

class AdvancedSizingTest {
    @Test
    void zIndexOrdersPositionedElements() throws Exception {
        String html =
                "<style>@page{size:200pt"
                    + " 200pt;margin:20pt}body{margin:0}.a,.b{position:absolute;left:0;top:0;width:50pt;height:50pt}.a{background:red;z-index:10}.b{background:blue;z-index:1}</style><div"
                    + " class='a'></div><div class='b'></div>";
        try (org.apache.pdfbox.pdmodel.PDDocument document =
                Loader.loadPDF(
                        new HtmlPdfConverter(
                                        PdfConversionConfig.builder().diagnostics(d -> {
                                        }).build())
                                .convert(html))) {
            Color pixel =
                    new Color(new PDFRenderer(document).renderImageWithDPI(0, 72).getRGB(40, 40));
            assertTrue(
                    pixel.getRed() > 200 && pixel.getBlue() < 30, "Higher z-index must paint last");
        }
    }

    @Test
    void gridMinmaxHonorsMinimumTrackSize() throws Exception {
        java.util.List<dtm.pdfloom.render.Paint.Text> text =
                new ModernLayoutTest()
                        .texts(
                                "<style>body{margin:0}.g{display:grid;grid-template-columns:minmax(200pt,1fr)"
                                    + " 1fr}</style><div class='g'><div>A</div><div>B</div></div>");
        assertEquals(200, text.get(1).x() - text.get(0).x(), .01);
    }

    @Test
    void flexShrinkingFreezesAtMinimumAndRedistributes() throws Exception {
        java.util.List<dtm.pdfloom.render.Paint.Text> text =
                new ModernLayoutTest()
                        .texts(
                                "<style>body{margin:0}.f{display:flex}.f div{flex:0 1"
                                        + " 200pt}.a{min-width:180pt}</style><div class='f'><div"
                                        + " class='a'>A</div><div>B</div><div>C</div></div>");
        assertEquals(180, text.get(1).x() - text.get(0).x(), .01);
        assertEquals(240, text.get(2).x() - text.get(0).x(), .01);
    }
}
