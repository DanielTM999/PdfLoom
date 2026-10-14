package dtm.pdfloom;

import static org.junit.jupiter.api.Assertions.*;

import dtm.pdfloom.config.*;
import dtm.pdfloom.css.*;
import dtm.pdfloom.font.*;
import dtm.pdfloom.html.*;
import dtm.pdfloom.image.*;
import dtm.pdfloom.internal.pdfbox.PdfFontRegistry;
import dtm.pdfloom.layout.*;
import dtm.pdfloom.render.*;
import dtm.pdfloom.resource.*;
import dtm.pdfloom.style.*;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.*;

import java.util.*;

class StyleAndLayoutTest {
    final PdfConversionConfig c = PdfConversionConfig.builder().diagnostics(d -> {
    }).build();

    StyleSheet styles(String html) {
        HtmlParser.Document d = new HtmlParser().parse(html, c);
        return new CascadeStyleResolver(c, new ResourceLoader(c)).resolve(d, null);
    }

    @Test
    void specificityInheritanceImportantVariables() {
        HtmlParser.Document d =
                new HtmlParser()
                        .parse(
                                "<style>body{color:red;--size:18pt}div"
                                    + " p{color:green}#x{color:blue}.a{color:purple!important}p[data-q='v']{font-size:var(--size)}</style><div><p"
                                    + " id='x' class='a' data-q='v'"
                                    + " style='color:orange'>text<span>child</span></p></div>",
                                c);
        StyleSheet s = new CascadeStyleResolver(c, new ResourceLoader(c)).resolve(d, null);
        HtmlNode p =
                d.elements().stream()
                        .filter(n -> n.attr("id").equals("x"))
                        .findFirst()
                        .orElseThrow();
        assertEquals("purple", s.style(p).get("color"));
        assertEquals("18.0pt", s.style(p).get("font-size"));
        HtmlNode child =
                p.children.stream().filter(n -> n.tag.equals("span")).findFirst().orElseThrow();
        assertEquals("purple", s.style(child).get("color"));
    }

    @Test
    void unitsCalcAndFunctions() {
        assertEquals(72, CssValues.length("25.4mm", 100, 12, 12, 500, 800, 0), .001);
        assertEquals(132, CssValues.length("calc(50% + 2em)", 216, 12, 12, 500, 800, 0), .001);
        assertEquals(
                100, CssValues.length("clamp(20pt, 50%, 100pt)", 300, 12, 12, 500, 800, 0), .001);
    }

    @Test
    void mediaAndStaticSelectors() {
        HtmlParser.Document d =
                new HtmlParser()
                        .parse(
                                "<style>@media screen{p{color:red}}@media"
                                    + " print{p:nth-child(2){color:blue}}p:first-child{font-weight:bold}</style><div><p>A</p><p>B</p></div>",
                                c);
        StyleSheet s = new CascadeStyleResolver(c, new ResourceLoader(c)).resolve(d, null);
        List<HtmlNode> p = d.elements().stream().filter(n -> n.tag.equals("p")).toList();
        assertEquals("700", s.style(p.get(0)).get("font-weight").replace("bold", "700"));
        assertEquals("blue", s.style(p.get(1)).get("color"));
    }

    LayoutResult layout(String html) throws Exception {
        HtmlParser.Document d = new HtmlParser().parse(html, c);
        ResourceLoader loader = new ResourceLoader(c);
        StyleSheet s = new CascadeStyleResolver(c, loader).resolve(d, null);
        try (PDDocument pdf = new PDDocument()) {
            PdfFontRegistry fonts = new PdfFontRegistry(pdf, c, loader, s, null);
            return new CssLayoutEngine(c, s, fonts, new ImageStore(loader), null, 1).layout(d, 300);
        }
    }

    @Test
    void flexDistributesActualWidths() throws Exception {
        LayoutResult r =
                layout(
                        "<style>body{margin:0}.flex{display:flex;width:300pt;gap:10pt}.flex"
                                + " div{flex:1;padding:0}</style><div"
                                + " class='flex'><div>LEFT</div><div>RIGHT</div></div>");
        List<Paint.Text> t =
                r.commands.stream()
                        .filter(p -> p instanceof Paint.Text)
                        .map(p -> (Paint.Text) p)
                        .toList();
        assertEquals(0, t.get(0).x(), .01);
        assertEquals(155, t.get(1).x(), .01);
        assertEquals(t.get(0).y() + t.get(0).ascent(), t.get(1).y() + t.get(1).ascent(), .01);
    }

    @Test
    void gridFractionAndColumnSpan() throws Exception {
        LayoutResult r =
                layout(
                        "<style>body{margin:0}.g{display:grid;grid-template-columns:1fr"
                            + " 2fr;gap:10pt}.span{grid-column:span 2}</style><div"
                            + " class='g'><div>A</div><div>B</div><div class='span'>C</div></div>");
        List<Paint.Text> t =
                r.commands.stream()
                        .filter(p -> p instanceof Paint.Text)
                        .map(p -> (Paint.Text) p)
                        .toList();
        assertEquals(290.0 / 3 + 10, t.get(1).x(), .01);
        assertTrue(t.get(2).y() > t.get(0).y());
        assertEquals(0, t.get(2).x(), .01);
    }
}
