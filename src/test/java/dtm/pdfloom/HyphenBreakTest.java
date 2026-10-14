package dtm.pdfloom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dtm.pdfloom.render.Paint;

import org.junit.jupiter.api.Test;

import java.util.List;

class HyphenBreakTest {
    @Test
    void wordsBreakAfterAHyphenFollowedByALetter() throws Exception {
        List<Paint.Text> texts =
                new ModernLayoutTest()
                        .texts(
                                "<style>body{margin:0}div{width:60pt;font:10pt Arial}</style>"
                                        + "<div>ALPHA-BETA-GAMMA</div>");
        assertTrue(texts.stream().map(Paint.Text::y).distinct().count() > 1);
        assertTrue(texts.stream().allMatch(text -> text.x() + text.width() <= 60.01));
        assertEquals(
                "ALPHA-BETA-GAMMA",
                texts.stream().map(Paint.Text::value).reduce("", String::concat));
    }

    @Test
    void hyphenBeforeADigitAndNowrapTextStayTogether() throws Exception {
        List<Paint.Text> numeric =
                new ModernLayoutTest()
                        .texts(
                                "<style>body{margin:0}div{width:20pt;font:10pt Arial}</style>"
                                        + "<div>AB-12</div>");
        assertEquals(1, numeric.stream().map(Paint.Text::y).distinct().count());
        List<Paint.Text> nowrap =
                new ModernLayoutTest()
                        .texts(
                                "<style>body{margin:0}div{width:40pt;font:10pt Arial;"
                                        + "white-space:nowrap}</style><div>ALPHA-BETA-GAMMA</div>");
        assertEquals(1, nowrap.stream().map(Paint.Text::y).distinct().count());
    }

    @Test
    void automaticTableUsesHyphenSegmentsForItsMinimumWidth() throws Exception {
        List<Paint.Text> texts =
                new ModernLayoutTest()
                        .texts(
                                "<style>body{margin:0}table{width:220pt;border-spacing:0}"
                                    + "td{padding:0;font:10pt Arial}.fixed{white-space:nowrap}"
                                    + "</style><table><tr><td>8F857DD8-C78D-4F91-ACB8-4A58509FEC40"
                                    + "</td><td class='fixed'>NOWRAP VALUE HERE</td></tr></table>");
        Paint.Text fixed =
                texts.stream()
                        .filter(text -> text.value().startsWith("NOWRAP"))
                        .findFirst()
                        .orElseThrow();
        Paint.Text last =
                texts.stream()
                        .filter(text -> text.value().equals("HERE"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(fixed.y(), last.y(), .5);
        assertTrue(last.x() + last.width() <= 220.01, "Nowrap cell must stay inside the table");
        assertTrue(
                texts.stream()
                        .filter(text -> text.x() < fixed.x())
                        .allMatch(text -> text.x() + text.width() <= fixed.x() + .01));
    }
}
