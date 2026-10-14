package dtm.pdfloom;

import static org.junit.jupiter.api.Assertions.*;

import dtm.pdfloom.css.GeneratedContent;
import dtm.pdfloom.impl.HtmlPdfConverter;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class TargetCountersTest {
    @Test
    void intrinsicMeasurementResolvesPagePlaceholdersBeforeMeasuringFontGlyphs() throws Exception {
        String html =
                "<style>.row{display:flex;justify-content:space-between}.count::after{content:counter(page)"
                    + " ' / ' counter(pages)}</style><div class='row'><span>FIRST</span><span"
                    + " class='count'></span></div><div style='break-before:page'>SECOND</div>";
        try (PDDocument document = Loader.loadPDF(new HtmlPdfConverter().convert(html))) {
            assertEquals(2, document.getNumberOfPages());
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("1 / 2"), text);
        }
    }

    @Test
    void targetFunctionsRespectQuotedLiteralsNestedArgumentsAndScopedValues() {
        Map<String, Map<String, List<Integer>>> counters =
                Map.of("section", Map.of("chapter", List.of(2, 4)));
        String content =
                "'target-counter(#fake,page)' ' ref:'"
                        + " target-counters(url('#section'),chapter,'.',upper-roman) ' page:'"
                        + " target-counter('#section',page)";
        assertEquals(
                "target-counter(#fake,page) ref:II.IV page:7",
                GeneratedContent.evaluate(content, null, Map.of(), Map.of("section", 7), counters));
        assertEquals(
                "4",
                GeneratedContent.evaluate(
                        "target-counter('#section',chapter)", null, Map.of(), Map.of(), counters));
    }

    @Test
    void forwardReferencesResolveCountersAtTheirTargetRatherThanAtTheLink() throws Exception {
        String html =
                "<style>body{counter-reset:chapter"
                    + " 0}.chapter{counter-increment:chapter;counter-reset:section"
                    + " 0}.section{counter-increment:section}a::after{content:' [chapter '"
                    + " target-counter(attr(href),chapter) ', section '"
                    + " target-counters(attr(href),section,'.')"
                    + " ']'}h2::before{content:counter(chapter) '.' counter(section) ' '}</style><a"
                    + " href='#second'>FORWARD</a><div class='chapter'><h2 class='section'"
                    + " id='first'>First</h2></div><div class='chapter'><h2 class='section'"
                    + " id='second'>Second</h2></div>";
        try (PDDocument document = Loader.loadPDF(new HtmlPdfConverter().convert(html))) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("FORWARD [chapter 2, section 1]"), text);
            assertTrue(text.contains("1.1 First"), text);
            assertTrue(text.contains("2.1 Second"), text);
        }
    }

    @Test
    void pseudoElementsApplyTheirOwnCounterMutations() throws Exception {
        String html =
                "<style>body{counter-reset:item}p::before{counter-increment:item;content:counter(item)"
                    + " ': '}</style><p>Alpha</p><p>Beta</p><p>Gamma</p>";
        try (PDDocument document = Loader.loadPDF(new HtmlPdfConverter().convert(html))) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("1: Alpha"), text);
            assertTrue(text.contains("2: Beta"), text);
            assertTrue(text.contains("3: Gamma"), text);
        }
    }
}
