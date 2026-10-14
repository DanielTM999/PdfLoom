package dtm.pdfloom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dtm.pdfloom.config.Diagnostic;
import dtm.pdfloom.config.PdfConversionConfig;
import dtm.pdfloom.impl.HtmlPdfConverter;
import dtm.pdfloom.render.Paint;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class ViewportUnitTest {
    @Test
    void viewportLengthsAreIgnoredSoTheCascadeFallsBack() throws Exception {
        List<Paint.Text> texts =
                new ModernLayoutTest()
                        .texts(
                                "<style>body{margin:0}div{font:10pt Arial;text-align:right}"
                                        + ".box{width:100pt}.box{width:100vh}"
                                        + ".auto{width:calc(50vw + 10pt)}</style>"
                                        + "<div class='box'>BOX</div><div class='auto'>AUTO</div>");
        Paint.Text box =
                texts.stream().filter(text -> text.value().equals("BOX")).findFirst().orElseThrow();
        Paint.Text auto =
                texts.stream()
                        .filter(text -> text.value().equals("AUTO"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(100, box.x() + box.width(), .01);
        assertEquals(300, auto.x() + auto.width(), .01);
    }

    @Test
    void ignoredViewportDeclarationIsReported() throws Exception {
        List<Diagnostic> diagnostics = new ArrayList<>();
        new HtmlPdfConverter(
                        PdfConversionConfig.builder()
                                .missingResourcePolicy(
                                        PdfConversionConfig.MissingResourcePolicy.WARN_AND_SKIP)
                                .diagnostics(diagnostics::add)
                                .build())
                .convert(
                        "<style>.section{width:100vh;background:url(img-10vh.png)}</style>"
                                + "<div class='section'>TEXT</div>");
        assertEquals(
                1,
                diagnostics.stream()
                        .filter(diagnostic -> diagnostic.code().equals("UNSUPPORTED_VIEWPORT_UNIT"))
                        .count());
        assertTrue(
                diagnostics.stream()
                        .anyMatch(diagnostic -> diagnostic.message().startsWith("width")));
        assertTrue(
                diagnostics.stream()
                        .anyMatch(diagnostic -> diagnostic.code().equals("MISSING_RESOURCE")));
    }
}
