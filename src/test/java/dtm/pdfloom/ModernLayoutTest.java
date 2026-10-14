package dtm.pdfloom;

import static org.junit.jupiter.api.Assertions.*;

import dtm.pdfloom.config.*;
import dtm.pdfloom.css.*;
import dtm.pdfloom.html.*;
import dtm.pdfloom.image.ImageStore;
import dtm.pdfloom.internal.pdfbox.PdfFontRegistry;
import dtm.pdfloom.layout.*;
import dtm.pdfloom.render.Paint;
import dtm.pdfloom.resource.ResourceLoader;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

import java.util.*;

class ModernLayoutTest {
    @Test
    void whitespaceControlsReachTheLineFormatter() throws Exception {
        List<Paint.Text> pre =
                texts(
                        "<style>body{margin:0}pre{margin:0;font-size:12pt;line-height:1.5}</style><pre>FIRST\n"
                            + "    SECOND\tTHIRD</pre>");
        Paint.Text first =
                pre.stream().filter(t -> t.value().equals("FIRST")).findFirst().orElseThrow();
        Paint.Text second =
                pre.stream().filter(t -> t.value().equals("SECOND")).findFirst().orElseThrow();
        assertEquals(18, baseline(second) - baseline(first), .01);
        assertTrue(second.x() > first.x());
        List<Paint.Text> normal = texts("<style>body{margin:0}</style><div>ONE\nTWO</div>");
        assertEquals(baseline(normal.get(0)), baseline(normal.get(2)), .01);
        assertEquals(" ", normal.get(1).value());
    }

    @Test
    void tableCaptionsParticipateInFlowAboveAndBelow() throws Exception {
        List<Paint.Text> above =
                texts(
                        "<style>body{margin:0}</style><table><caption>CAPTION</caption><tr><td>CELL</td></tr></table>");
        assertEquals("CAPTION", above.get(0).value());
        assertTrue(baseline(above.get(1)) > baseline(above.get(0)));
        List<Paint.Text> below =
                texts(
                        "<style>body{margin:0}caption{caption-side:bottom}</style><table><caption>CAPTION</caption><tr><td>CELL</td></tr></table>");
        assertEquals("CELL", below.get(0).value());
        assertTrue(baseline(below.get(1)) > baseline(below.get(0)));
    }

    @Test
    void tocTargetPageCounterConvergesWithPagination() throws Exception {
        String html =
                "<style>@page{size:300pt 300pt;margin:20pt}a::after{content:' página '"
                    + " target-counter(attr(href),page)}</style><a href='#chapter'>Capítulo</a><h1"
                    + " id='chapter' style='break-before:page'>Seção</h1>";
        try (PDDocument document =
                org.apache.pdfbox.Loader.loadPDF(
                        new dtm.pdfloom.impl.HtmlPdfConverter(
                                        PdfConversionConfig.builder().diagnostics(d -> {
                                        }).build())
                                .convert(html))) {
            assertEquals(2, document.getNumberOfPages());
            assertTrue(
                    new org.apache.pdfbox.text.PDFTextStripper()
                            .getText(document)
                            .contains("página 2"));
        }
    }

    List<Paint.Text> texts(String html) throws Exception {
        PdfConversionConfig c = PdfConversionConfig.builder().diagnostics(d -> {
        }).build();
        HtmlParser.Document dom = new HtmlParser().parse(html, c);
        try (ResourceLoader loader = new ResourceLoader(c);
                PDDocument pdf = new PDDocument()) {
            dtm.pdfloom.style.StyleSheet css =
                    new CascadeStyleResolver(c, loader).resolve(dom, null);
            PdfFontRegistry fonts = new PdfFontRegistry(pdf, c, loader, css, null);
            LayoutResult r =
                    new CssLayoutEngine(c, css, fonts, new ImageStore(loader), null, 1)
                            .layout(dom, 300);
            ArrayList<Paint.Text> text = new ArrayList<Paint.Text>();
            collect(r.commands, text);
            return text;
        }
    }

    private void collect(List<Paint> paints, List<Paint.Text> result) {
        for (Paint p : paints) {
            if (p instanceof Paint.Text t) {
                result.add(t);
            } else if (p instanceof Paint.Layer layer) {
                collect(layer.commands(), result);
            } else if (p instanceof Paint.Group g) {
                collect(g.commands(), result);
            }
        }
    }

    private double baseline(Paint.Text t) {
        return t.y() + t.ascent();
    }

    @Test
    void flexColumnDistributesDefiniteHeight() throws Exception {
        List<Paint.Text> t =
                texts(
                        "<style>body{margin:0}.f{display:flex;flex-direction:column;height:200pt}.f"
                            + " div{flex:1}</style><div class='f'><div>A</div><div>B</div></div>");
        assertEquals(100, baseline(t.get(1)) - baseline(t.get(0)), .01);
    }

    @Test
    void flexColumnGrowRatiosAndGap() throws Exception {
        List<Paint.Text> t =
                texts(
                        "<style>body{margin:0}.f{display:flex;flex-direction:column;height:310pt;gap:10pt}.a{flex:1}.b{flex:2}</style><div"
                            + " class='f'><div class='a'>A</div><div class='b'>B</div></div>");
        assertEquals(110, baseline(t.get(1)) - baseline(t.get(0)), .01);
    }

    @Test
    void explicitGridRowLeavesUnoccupiedFirstRow() throws Exception {
        List<Paint.Text> t =
                texts(
                        "<style>body{margin:0}.g{display:grid;grid-template-columns:100pt"
                            + " 100pt;grid-template-rows:50pt"
                            + " 50pt}.a{grid-row:1;grid-column:1}.b{grid-row:2;grid-column:2}</style><div"
                            + " class='g'><div class='a'>A</div><div class='b'>B</div></div>");
        assertEquals(50, baseline(t.get(1)) - baseline(t.get(0)), .01);
        assertEquals(100, t.get(1).x() - t.get(0).x(), .01);
    }

    @Test
    void gridRowSpanAndAutoPlacementAvoidOverlap() throws Exception {
        List<Paint.Text> t =
                texts(
                        "<style>body{margin:0}.g{display:grid;grid-template-columns:100pt"
                                + " 100pt;grid-template-rows:40pt 40pt}.a{grid-row:1 / span"
                                + " 2;grid-column:1}</style><div class='g'><div"
                                + " class='a'>A</div><div>B</div><div>C</div></div>");
        assertEquals(100, t.get(1).x(), .01);
        assertEquals(100, t.get(2).x(), .01);
        assertEquals(40, baseline(t.get(2)) - baseline(t.get(1)), .01);
    }

    @Test
    void inlineBlockKeepsSpecifiedWidthAndWrapsWholeBox() throws Exception {
        List<Paint.Text> t =
                texts(
                        "<style>body{margin:0}span{display:inline-block;width:100pt}</style><span>A</span><span>B</span><span>C</span><span>D</span>");
        assertEquals(100, t.get(1).x() - t.get(0).x(), .01);
        assertEquals(200, t.get(2).x(), .01);
        assertEquals(0, t.get(3).x(), .01);
        assertTrue(baseline(t.get(3)) > baseline(t.get(0)));
    }

    @Test
    void fontShorthandAndFunctionalSpecificity() throws Exception {
        assertEquals(1_000_000_000_000L, CascadeStyleResolver.specificity(":is(.x,#y)"));
        assertEquals(1_000_000L, CascadeStyleResolver.specificity(":where(#y).x"));
        assertEquals(1_000_000L, CascadeStyleResolver.specificity("[data-name='#x.y']"));
        List<Paint.Text> t =
                texts("<style>body{margin:0;font:italic bold 18pt/1.5 Roboto}</style><p>A</p>");
        assertEquals(18, t.get(0).size(), .01);
    }
}
