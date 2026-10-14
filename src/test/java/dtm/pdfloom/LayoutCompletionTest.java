package dtm.pdfloom;

import static org.junit.jupiter.api.Assertions.*;

import dtm.pdfloom.config.PdfConversionConfig;
import dtm.pdfloom.css.CascadeStyleResolver;
import dtm.pdfloom.html.HtmlParser;
import dtm.pdfloom.page.PageSpec;
import dtm.pdfloom.render.Paint;
import dtm.pdfloom.resource.ResourceLoader;
import dtm.pdfloom.style.StyleSheet;

import org.junit.jupiter.api.Test;

import java.util.List;

class LayoutCompletionTest {
    private List<Paint.Text> texts(String html) throws Exception {
        return new ModernLayoutTest().texts("<style>body{margin:0}</style>" + html);
    }

    private Paint.Text label(List<Paint.Text> text, String label) {
        return text.stream().filter(item -> item.value().equals(label)).findFirst().orElseThrow();
    }

    @Test
    void flexColumnsWrapIntoIndependentColumnsAndHonorReverse() throws Exception {
        List<Paint.Text> text =
                texts(
                        "<style>.f{display:flex;flex-flow:column"
                            + " wrap;flex-direction:column;flex-wrap:wrap;height:50pt;gap:5pt;align-content:flex-start}.f"
                            + " div{height:20pt;width:40pt}</style><div"
                            + " class='f'><div>A</div><div>B</div><div>C</div></div>");
        assertEquals(label(text, "A").x(), label(text, "B").x(), .1);
        assertEquals(25, label(text, "B").y() - label(text, "A").y(), .1);
        assertEquals(45, label(text, "C").x() - label(text, "A").x(), .1);
        assertEquals(
                label(text, "A").y() + label(text, "A").ascent(),
                label(text, "C").y() + label(text, "C").ascent(),
                .1);
    }

    @Test
    void flexBaselineAndAutoMarginsUseFontAndAvailableSpace() throws Exception {
        List<Paint.Text> text =
                texts(
                        "<style>.f{display:flex;align-items:baseline;width:300pt}.a{font-size:30pt}.b{font-size:10pt;margin-left:auto}</style><div"
                            + " class='f'><div class='a'>A</div><div class='b'>B</div></div>");
        Paint.Text a = label(text, "A");
        Paint.Text b = label(text, "B");
        assertEquals(a.y() + a.ascent(), b.y() + b.ascent(), .1);
        assertEquals(300, b.x() + b.width(), .1);
    }

    @Test
    void gridNamedLinesAreasNegativeIndicesAndImplicitColumns() throws Exception {
        List<Paint.Text> names =
                texts(
                        "<style>.g{display:grid;grid-template-columns:[left]60pt [middle]80pt"
                            + " [right];grid-auto-columns:50pt}.a{grid-column:middle/right}.b{grid-column:4;grid-row:1}</style><div"
                            + " class='g'><div class='a'>A</div><div class='b'>B</div></div>");
        assertEquals(60, label(names, "A").x(), .1);
        assertEquals(190, label(names, "B").x(), .1);
        List<Paint.Text> areas =
                texts(
                        "<style>.g{display:grid;grid-template-columns:60pt"
                                + " 80pt;grid-template-rows:20pt 30pt;grid-template-areas:'top top'"
                                + " 'side main'}.a{grid-area:top}.b{grid-area:main}</style><div"
                                + " class='g'><div class='a'>A</div><div class='b'>B</div></div>");
        assertEquals(60, label(areas, "B").x(), .1);
        assertEquals(20, label(areas, "B").y() - label(areas, "A").y(), .1);
        List<Paint.Text> negative =
                texts(
                        "<style>.g{display:grid;grid-template-columns:60pt"
                                + " 80pt}.a{grid-column:-2/-1}</style><div class='g'><div"
                                + " class='a'>A</div></div>");
        assertEquals(60, label(negative, "A").x(), .1);
    }

    @Test
    void gridColumnFlowFillsRowsBeforeCreatingColumns() throws Exception {
        List<Paint.Text> text =
                texts(
                        "<style>.g{display:grid;grid-template-columns:60pt;grid-template-rows:20pt"
                                + " 20pt;grid-auto-columns:80pt;grid-auto-flow:column}</style><div"
                                + " class='g'><div>A</div><div>B</div><div>C</div></div>");
        assertEquals(0, label(text, "B").x(), .1);
        assertEquals(20, label(text, "B").y() - label(text, "A").y(), .1);
        assertEquals(60, label(text, "C").x(), .1);
        assertEquals(
                label(text, "A").y() + label(text, "A").ascent(),
                label(text, "C").y() + label(text, "C").ascent(),
                .1);
    }

    @Test
    void intrinsicGridTracksKeepMinAndMaxContentDistinct() throws Exception {
        List<Paint.Text> minimum =
                texts(
                        "<style>.g{display:grid;grid-template-columns:min-content 1fr}</style><div"
                                + " class='g'><div>ALPHA BETA</div><div>X</div></div>");
        List<Paint.Text> maximum =
                texts(
                        "<style>.g{display:grid;grid-template-columns:max-content 1fr}</style><div"
                                + " class='g'><div>ALPHA BETA</div><div>X</div></div>");
        assertTrue(label(maximum, "X").x() > label(minimum, "X").x() + 10);
    }

    @Test
    void pageCascadePreservesImportantAndMergesMarginProperties() {
        PdfConversionConfig config = PdfConversionConfig.builder().build();
        HtmlParser.Document document =
                new HtmlParser()
                        .parse(
                                "<style>@page{margin-left:30pt!important;@top-center{content:'BASE';color:red!important}}@page:first:right{margin-left:50pt;@top-center{font-size:15pt;color:blue}}</style><p>X</p>",
                                config);
        StyleSheet styles =
                new CascadeStyleResolver(config, new ResourceLoader(config))
                        .resolve(document, null);
        PageSpec page = PageSpec.resolve(config, styles, 1, "", false);
        assertEquals(30, page.left(), .1);
        assertEquals("red", page.marginBoxes().get("top-center").get("color"));
        assertEquals("15.0pt", page.marginBoxes().get("top-center").get("font-size"));
        assertTrue(page.marginBoxes().get("top-center").get("content").contains("BASE"));
    }

    @Test
    void subgridSharesParentTracksInBothAxes() throws Exception {
        List<Paint.Text> text =
                texts(
                        "<style>.g{display:grid;grid-template-columns:[left]60pt [middle]80pt"
                            + " [right];grid-template-rows:20pt"
                            + " 30pt;gap:5pt}.sub{display:grid;grid-column:1/3;grid-row:1/3;grid-template-columns:subgrid;grid-template-rows:subgrid}.a{grid-column:middle/right;grid-row:2}</style><div"
                            + " class='g'><div class='sub'><div class='a'>A</div></div></div>");
        assertEquals(65, label(text, "A").x(), .1);
        assertEquals(
                25,
                label(text, "A").y()
                        + label(text, "A").ascent()
                        - (new ModernLayoutTest().texts("<style>body{margin:0}</style>A").get(0).y()
                                + new ModernLayoutTest()
                                        .texts("<style>body{margin:0}</style>A")
                                        .get(0)
                                        .ascent()),
                .1);
    }

    @Test
    void ellipsisUsesRealFontMetricsAndDoesNotExposeHiddenSuffix() throws Exception {
        List<Paint.Text> text =
                texts(
                        "<style>.clip{width:70pt;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}</style><div"
                            + " class='clip'>PREFIX VERY LONG PRIVATE SUFFIX</div>");
        assertTrue(text.stream().anyMatch(item -> item.value().equals("\u2026")));
        assertFalse(text.stream().anyMatch(item -> item.value().equals("SUFFIX")));
        assertTrue(text.stream().allMatch(item -> item.x() + item.width() <= 70.1));
    }
}
