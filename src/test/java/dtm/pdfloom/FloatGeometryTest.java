package dtm.pdfloom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dtm.pdfloom.render.Paint;

import org.junit.jupiter.api.Test;

import java.util.List;

class FloatGeometryTest {
    @Test
    void contentBoxFloatIncludesPaddingBeforePlacingItsSibling() throws Exception {
        List<Paint.Text> texts =
                new ModernLayoutTest()
                        .texts(
                                "<style>body{margin:0}.float{float:left;width:60pt;padding:10pt;"
                                        + "font:12pt Arial}</style><div class='float'>FIRST</div>"
                                        + "<div class='float'>SECOND</div>");
        Paint.Text first =
                texts.stream()
                        .filter(text -> text.value().equals("FIRST"))
                        .findFirst()
                        .orElseThrow();
        Paint.Text second =
                texts.stream()
                        .filter(text -> text.value().equals("SECOND"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(80, second.x() - first.x(), .01);
    }

    @Test
    void percentageFloatWrapsWithinItsWidthAndClearfixContainsItsHeight() throws Exception {
        List<Paint.Text> texts =
                new ModernLayoutTest()
                        .texts(
                                "<style>body{margin:0;font:12pt"
                                    + " Arial}.card{width:260pt;padding:10pt;"
                                    + "overflow:hidden}.title{float:right;width:40%;text-align:right}"
                                    + ".card:after{content:'';display:block;clear:both}p{margin:0}</style><div"
                                    + " class='card'><p class='title'>MONTHLY CONSULTATIONS"
                                    + " REPORT</p></div><p>AFTER</p>");
        List<Paint.Text> title =
                texts.stream()
                        .filter(text -> !text.value().isBlank() && !text.value().equals("AFTER"))
                        .toList();
        Paint.Text after =
                texts.stream()
                        .filter(text -> text.value().equals("AFTER"))
                        .findFirst()
                        .orElseThrow();
        assertTrue(title.stream().map(Paint.Text::y).distinct().count() > 1);
        assertTrue(
                title.stream()
                        .allMatch(
                                text ->
                                        text.x() >= 166 - .01
                                                && text.x() + text.width() <= 270 + .01));
        assertTrue(
                after.y()
                        >= title.stream()
                                        .mapToDouble(text -> text.y() + text.height())
                                        .max()
                                        .orElseThrow()
                                + 9);
    }
}
