package dtm.pdfloom;

import static org.junit.jupiter.api.Assertions.*;

import dtm.pdfloom.config.PdfConversionConfig;
import dtm.pdfloom.css.CascadeStyleResolver;
import dtm.pdfloom.html.HtmlNode;
import dtm.pdfloom.html.HtmlParser;
import dtm.pdfloom.impl.HtmlPdfConverter;
import dtm.pdfloom.render.Paint;
import dtm.pdfloom.resource.ResourceLoader;
import dtm.pdfloom.style.StyleSheet;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.List;

class LayoutRegressionTest {
    @Test
    void wrappingIncludesTheSpaceBetweenWords() throws Exception {
        List<Paint.Text> text =
                new ModernLayoutTest()
                        .texts(
                                "<style>body{margin:0}p{font:12pt"
                                        + " Arial;width:65pt;margin:0}</style><p>AAAA BBBB</p>");
        Paint.Text first =
                text.stream()
                        .filter(value -> value.value().equals("AAAA"))
                        .findFirst()
                        .orElseThrow();
        Paint.Text second =
                text.stream()
                        .filter(value -> value.value().equals("BBBB"))
                        .findFirst()
                        .orElseThrow();
        assertTrue(second.y() > first.y());
        assertEquals(first.x(), second.x(), .01);
    }

    @Test
    void flexAndGridPreserveDirectTextInAnonymousItems() throws Exception {
        for (String display : List.of("flex", "grid")) {
            List<Paint.Text> text =
                    new ModernLayoutTest()
                            .texts(
                                    "<style>body{margin:0}.container{display:"
                                            + display
                                            + ";grid-template-columns:repeat(3,1fr)}</style><div"
                                            + " class='container'>FIRST <b>MIDDLE</b> LAST</div>");
            List<String> words =
                    text.stream().map(Paint.Text::value).filter(value -> !value.isBlank()).toList();
            assertEquals(List.of("FIRST", "MIDDLE", "LAST"), words);
            assertTrue(
                    text.get(0).x()
                            < text.stream()
                                    .filter(value -> value.value().equals("LAST"))
                                    .findFirst()
                                    .orElseThrow()
                                    .x());
        }
    }

    @Test
    void minimumCellHeightVerticallyCentersNaturalContent() throws Exception {
        List<Paint.Text> top =
                new ModernLayoutTest()
                        .texts(
                                "<style>body{margin:0}td{height:40pt;padding:0;vertical-align:top}</style><table><tr><td>TEXT</td></tr></table>");
        List<Paint.Text> middle =
                new ModernLayoutTest()
                        .texts(
                                "<style>body{margin:0}td{height:40pt;padding:0;vertical-align:middle}</style><table><tr><td>TEXT</td></tr></table>");
        assertEquals(12.8, middle.get(0).y() - top.get(0).y(), .01);
    }

    @Test
    void percentageHeightInAnAutoSizedParentDoesNotCreateExtraPages() throws Exception {
        PdfConversionConfig config =
                PdfConversionConfig.builder().diagnostics(diagnostic -> {
                }).build();
        try (PDDocument document =
                Loader.loadPDF(
                        new HtmlPdfConverter(config)
                                .convert(
                                        """
                                        <style>@page{size:300pt 240pt;margin:20pt}body{margin:0}
                                        .parent{display:flex;height:100%}.child{height:20pt}</style>
                                        <section><div class='parent'><div class='child'>FIRST</div></div></section><p>LAST</p>
                                        """))) {
            assertEquals(1, document.getNumberOfPages());
            assertTrue(
                    new org.apache.pdfbox.text.PDFTextStripper()
                            .getText(document)
                            .contains("LAST"));
        }
    }

    @Test
    void absoluteBottomUsesTheHeightOfThePositionedParent() throws Exception {
        PdfConversionConfig config =
                PdfConversionConfig.builder().diagnostics(diagnostic -> {
                }).build();
        try (PDDocument document =
                Loader.loadPDF(
                        new HtmlPdfConverter(config)
                                .convert(
                                        """
                                        <style>@page{size:300pt 240pt;margin:20pt}body{margin:0}
                                        .parent{position:relative;height:100pt}
                                        .child{position:absolute;bottom:10pt;height:20pt;width:80pt;background:red}</style>
                                        <div class='parent'><div class='child'></div></div><p>LAST</p>
                                        """))) {
            assertEquals(1, document.getNumberOfPages());
            Color pixel =
                    new Color(new PDFRenderer(document).renderImageWithDPI(0, 72).getRGB(50, 95));
            assertTrue(pixel.getRed() > 240 && pixel.getGreen() < 10);
        }
    }

    @Test
    void boxShorthandsUseTopRightBottomLeftForOneToFourValues() throws Exception {
        List<String> declarations = List.of("1pt", "1pt 2pt", "1pt 2pt 3pt", "1pt 2pt 3pt 4pt");
        List<List<String>> expected =
                List.of(
                        List.of("1pt", "1pt", "1pt", "1pt"),
                        List.of("1pt", "2pt", "1pt", "2pt"),
                        List.of("1pt", "2pt", "3pt", "2pt"),
                        List.of("1pt", "2pt", "3pt", "4pt"));
        PdfConversionConfig config =
                PdfConversionConfig.builder().diagnostics(diagnostic -> {
                }).build();
        for (int index = 0; index < declarations.size(); index++) {
            HtmlParser.Document dom =
                    new HtmlParser()
                            .parse(
                                    "<p id='box' style='margin:"
                                            + declarations.get(index)
                                            + ";padding:"
                                            + declarations.get(index)
                                            + ";border-width:"
                                            + declarations.get(index)
                                            + "'>TEXT</p>",
                                    config);
            try (ResourceLoader loader = new ResourceLoader(config)) {
                StyleSheet css = new CascadeStyleResolver(config, loader).resolve(dom, null);
                HtmlNode node =
                        dom.elements().stream()
                                .filter(value -> value.attr("id").equals("box"))
                                .findFirst()
                                .orElseThrow();
                List<String> sides = List.of("top", "right", "bottom", "left");
                for (int side = 0; side < sides.size(); side++) {
                    for (String property :
                            List.of(
                                    "margin-" + sides.get(side),
                                    "padding-" + sides.get(side),
                                    "border-" + sides.get(side) + "-width")) {
                        assertEquals(
                                expected.get(index).get(side),
                                css.style(node).get(property),
                                property);
                    }
                }
            }
        }
    }

    @Test
    void fixedTableUsesFirstRowWidthsAndDoesNotResizeForLaterContent() throws Exception {
        List<Paint.Text> text =
                new ModernLayoutTest()
                        .texts(
                                """
                                <style>body{margin:0}table{width:100%;table-layout:fixed;border-collapse:collapse}
                                td{padding:0}.plate{width:22%}.model{width:36%}</style>
                                <table><tr><td class='plate'>A</td><td class='model'>B</td><td>C</td><td>D</td></tr>
                                <tr><td>LONG_LONG_LONG_LONG_LONG</td><td>Second</td><td>Third</td><td>Fourth</td></tr></table>
                                """);
        assertEquals(66, text.get(1).x(), .01);
        assertEquals(174, text.get(2).x(), .01);
        assertEquals(237, text.get(3).x(), .01);
    }

    @Test
    void inlinePlateIncludesPaddingBorderAndTrackingInItsIntrinsicWidth() throws Exception {
        List<Paint.Text> text =
                new ModernLayoutTest()
                        .texts(
                                """
                                <style>body{margin:0}.plate{display:inline-block;font:15pt 'Courier New';font-weight:bold;
                                padding:3px 10px;border:2px solid black;letter-spacing:2px;overflow-wrap:anywhere}</style>
                                <span class='plate'>ABC1D23</span><span>NEXT</span>
                                """);
        assertEquals("ABC1D23", text.get(0).value());
        Paint.Text next =
                text.stream()
                        .filter(value -> value.value().equals("NEXT"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(91.5, next.x(), .1);
        assertEquals(text.get(0).y() + text.get(0).ascent(), next.y() + next.ascent(), 10);
    }

    @Test
    void fixedFooterUsesCssPageContentHeight() throws Exception {
        PdfConversionConfig config =
                PdfConversionConfig.builder().diagnostics(diagnostic -> {
                }).build();
        try (PDDocument document =
                Loader.loadPDF(
                        new HtmlPdfConverter(config)
                                .convert(
                                        """
                                        <style>@page{size:200pt 240pt;margin:20pt}body{margin:0}
                                        footer{position:fixed;bottom:0;height:20pt;width:100%;background:red}</style>
                                        <p>BODY</p><footer></footer>
                                        """))) {
            Color pixel =
                    new Color(new PDFRenderer(document).renderImageWithDPI(0, 72).getRGB(50, 210));
            assertTrue(pixel.getRed() > 240 && pixel.getGreen() < 10);
        }
    }
}
