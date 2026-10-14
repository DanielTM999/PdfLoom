package dtm.pdfloom;

import static org.junit.jupiter.api.Assertions.*;

import dtm.pdfloom.config.PdfConversionConfig;
import dtm.pdfloom.css.CascadeStyleResolver;
import dtm.pdfloom.css.CssNumericValue;
import dtm.pdfloom.css.CssValues;
import dtm.pdfloom.exception.PdfConversionException;
import dtm.pdfloom.html.HtmlNode;
import dtm.pdfloom.html.HtmlParser;
import dtm.pdfloom.impl.HtmlPdfConverter;
import dtm.pdfloom.resource.ResourceLoader;
import dtm.pdfloom.style.StyleSheet;

import org.junit.jupiter.api.Test;

import java.util.List;

class CssConformanceTest {
    @Test
    void numericExponentsRemainDistinctFromFontRelativeUnits() {
        CssNumericValue.Context context = new CssNumericValue.Context(200, 20, 16, 600, 800);
        assertEquals(100, CssNumericValue.parse("1e2pt", context).value(), .001);
        assertEquals(.72, CssNumericValue.parse("1E-2in", context).value(), .001);
        assertEquals(40, CssNumericValue.parse("2em", context).value(), .001);
        assertEquals(110, CssNumericValue.parse("calc(1e2pt + 5e-1em)", context).value(), .001);
        assertThrows(IllegalArgumentException.class, () -> CssNumericValue.parse("1e+pt", context));
        assertThrows(
                IllegalArgumentException.class, () -> CssNumericValue.parse("1e999pt", context));
    }

    private record Resolved(HtmlParser.Document document, StyleSheet sheet) {
        String value(String id, String property) {
            HtmlNode node =
                    document.elements().stream()
                            .filter(element -> element.attr("id").equals(id))
                            .findFirst()
                            .orElseThrow();
            return sheet.style(node).get(property);
        }
    }

    private Resolved resolve(String css, String html) {
        PdfConversionConfig config =
                PdfConversionConfig.builder().diagnostics(diagnostic -> {
                }).build();
        HtmlParser.Document document =
                new HtmlParser().parse("<style>" + css + "</style>" + html, config);
        return new Resolved(
                document,
                new CascadeStyleResolver(config, new ResourceLoader(config))
                        .resolve(document, null));
    }

    @Test
    void layersRespectNormalImportantAndInlinePrecedence() {
        Resolved resolved =
                resolve(
                        "@layer early,late;@layer"
                            + " late{p{color:blue;background-color:blue!important}}@layer"
                            + " early{#x{color:red;background-color:red!important}}p{color:green;background-color:green!important}",
                        "<p id='x'>X</p><p id='y'"
                                + " style='color:orange;background-color:orange!important'>Y</p>");
        assertEquals("green", resolved.value("x", "color"));
        assertEquals("red", resolved.value("x", "background-color"));
        assertEquals("orange", resolved.value("y", "color"));
        assertEquals("orange", resolved.value("y", "background-color"));
    }

    @Test
    void nestedLayersKeepTheirParentOrderAndRevertLayerRollsBack() {
        Resolved resolved =
                resolve(
                        "@layer a,b;@layer a{@layer nested{#x{color:red}}p{color:purple}}@layer"
                                + " b{p{color:blue}#x{color:revert-layer}}",
                        "<p id='x'>X</p><p id='y'>Y</p>");
        assertEquals("purple", resolved.value("x", "color"));
        assertEquals("blue", resolved.value("y", "color"));
    }

    @Test
    void nestingUsesParentSelectorListSpecificityAndPseudoElements() {
        Resolved resolved =
                resolve(
                        ".a,#parent{& > p{color:red;&::before{content:'prefix'}}}@layer"
                                + " other{p{color:blue}}.a p{color:green}",
                        "<div class='a'><p id='x'>X</p></div><p id='y'>Y</p>");
        assertEquals("red", resolved.value("x", "color"));
        assertEquals("blue", resolved.value("y", "color"));
        HtmlNode node =
                resolved.document.elements().stream()
                        .filter(element -> element.attr("id").equals("x"))
                        .findFirst()
                        .orElseThrow();
        assertEquals("'prefix'", resolved.sheet.before.get(node).get("content"));
    }

    @Test
    void supportsEvaluatesCapabilitiesInsteadOfPropertyNameOnly() {
        Resolved resolved =
                resolve(
                        "@supports (display:flex) and (width:calc(10px +"
                                + " 2em)){#x{color:red}}@supports"
                                + " (display:invented){#x{color:blue}}@supports not"
                                + " (display:invented){#y{color:green}}",
                        "<p id='x'>X</p><p id='y'>Y</p>");
        assertEquals("red", resolved.value("x", "color"));
        assertEquals("green", resolved.value("y", "color"));
    }

    @Test
    void mediaQueriesCheckDimensionsOrientationAndResolution() {
        Resolved resolved =
                resolve(
                        "@media print and (min-width:500pt) and"
                                + " (orientation:portrait){#x{color:red}}@media print and"
                                + " (max-width:400pt){#x{color:blue}}@media screen, print and"
                                + " (resolution:1dppx){#y{color:green}}@media not"
                                + " print{#y{color:blue}}",
                        "<p id='x'>X</p><p id='y'>Y</p>");
        assertEquals("red", resolved.value("x", "color"));
        assertEquals("green", resolved.value("y", "color"));
    }

    @Test
    void remUsesComputedRootAndRootRemUsesInitialFont() throws Exception {
        Resolved resolved =
                resolve(
                        "html{font-size:2rem}#x{font-size:1.5rem}#y{font-size:50%}",
                        "<div id='x'><p id='y'>Y</p></div>");
        assertEquals(24, resolved.sheet.rootFontSize, .001);
        assertEquals("36.0pt", resolved.value("x", "font-size"));
        assertEquals("18.0pt", resolved.value("y", "font-size"));
        List<dtm.pdfloom.render.Paint.Text> text =
                new ModernLayoutTest()
                        .texts(
                                "<style>html{font-size:20pt}body{margin:0}.g{display:grid;grid-template-columns:2rem"
                                    + " 1fr}</style><div class='g'><div>A</div><div>B</div></div>");
        assertEquals(40, text.get(1).x() - text.get(0).x(), .01);
    }

    @Test
    void invalidDimensionalArithmeticAndIndefinitePercentagesAreRejected() {
        CssNumericValue.Context context = new CssNumericValue.Context(100, 12, 20, 500, 800);
        assertEquals(30, CssNumericValue.parse("calc(10pt * 3)", context).value(), .001);
        assertThrows(
                IllegalArgumentException.class,
                () -> CssNumericValue.parse("calc(10pt + 3)", context));
        assertThrows(
                IllegalArgumentException.class,
                () -> CssNumericValue.parse("calc(10pt * 3pt)", context));
        assertThrows(
                IllegalArgumentException.class,
                () -> CssNumericValue.parse("clamp(1pt,2pt)", context));
        assertEquals(-1, CssValues.length("50%", Double.NaN, 12, 20, 500, 800, -1));
    }

    @Test
    void revertRestoresUserAgentAndInheritedValues() {
        Resolved resolved =
                resolve("body{color:red}p{color:blue}#x{color:revert}", "<p id='x'>X</p>");
        assertEquals("red", resolved.value("x", "color"));
    }

    @Test
    void strictCssPolicyRejectsUnsupportedFeatures() {
        PdfConversionConfig config =
                PdfConversionConfig.builder()
                        .unsupportedCssPolicy(PdfConversionConfig.UnsupportedCssPolicy.FAIL)
                        .build();
        PdfConversionException error =
                assertThrows(
                        PdfConversionException.class,
                        () ->
                                new HtmlPdfConverter(config)
                                        .convert("<p style='unknown-property:1'>X</p>"));
        assertTrue(error.getMessage().contains("UNSUPPORTED_PROPERTY"));
        assertTrue(error.getMessage().contains("unknown-property"));
    }
}
