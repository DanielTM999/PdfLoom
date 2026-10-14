package dtm.pdfloom;

import static org.junit.jupiter.api.Assertions.*;

import dtm.pdfloom.impl.HtmlPdfConverter;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class ColumnsAndMarginsTest {
    @Test
    void runningCountersUseTheActualPageNumberWhenAligningOneAndTwoDigitPages() throws Exception {
        StringBuilder html =
                new StringBuilder(
                        "<style>@page{size:240pt"
                            + " 200pt;margin:20pt;@bottom-center{content:element(footer)}}body{margin:0}footer{position:running(footer);text-align:right;font-size:10pt}.number::after{content:counter(page)}</style><footer><span"
                            + " class='number'></span></footer>");
        for (int page = 1; page <= 11; page++) {
            html.append("<p style='break-before:page'>Body</p>");
        }
        try (PDDocument document =
                Loader.loadPDF(new HtmlPdfConverter().convert(html.toString()))) {
            assertEquals(11, document.getNumberOfPages());
            List<Double> ends = new ArrayList<>();
            for (int page : new int[] {1, 10, 11}) {
                List<TextPosition> digits = new ArrayList<>();
                PDFTextStripper text =
                        new PDFTextStripper() {
                            @Override
                            protected void processTextPosition(TextPosition position) {
                                if (position.getYDirAdj() > 180
                                        && position.getUnicode().matches("[0-9]+")) {
                                    digits.add(position);
                                }
                                super.processTextPosition(position);
                            }
                        };
                text.setStartPage(page);
                text.setEndPage(page);
                text.getText(document);
                assertEquals(
                        Integer.toString(page),
                        digits.stream()
                                .map(TextPosition::getUnicode)
                                .collect(java.util.stream.Collectors.joining()));
                ends.add(
                        digits.stream()
                                .mapToDouble(
                                        position ->
                                                position.getXDirAdj() + position.getWidthDirAdj())
                                .max()
                                .orElseThrow());
            }
            assertEquals(ends.get(0), ends.get(1), .05);
            assertEquals(ends.get(0), ends.get(2), .05);
        }
    }

    @Test
    void runningMarkupWrapsAtTheMarginBoxWidthOnEveryPage() throws Exception {
        String html =
                "<style>@page{size:300pt 240pt;margin:60pt 30pt"
                    + " 30pt;@top-left{content:element(header);vertical-align:top;padding-top:6pt}@top-right{content:'RIGHT';vertical-align:top;padding-top:6pt}}body{margin:0}header{position:running(header);font-size:10pt;line-height:12pt}p{margin:0}</style><header>ALPHA"
                    + " BETA GAMMA DELTA</header><p>BODY FIRST</p><p style='break-before:page'>BODY"
                    + " SECOND</p>";
        try (PDDocument document = Loader.loadPDF(new HtmlPdfConverter().convert(html))) {
            assertEquals(2, document.getNumberOfPages());
            for (int page = 1; page <= 2; page++) {
                List<Double> baselines = new ArrayList<>();
                PDFTextStripper stripper =
                        new PDFTextStripper() {
                            @Override
                            protected void writeString(String value, List<TextPosition> positions)
                                    throws java.io.IOException {
                                if (value.contains("ALPHA")
                                        || value.contains("BETA")
                                        || value.contains("GAMMA")
                                        || value.contains("DELTA")) {
                                    for (TextPosition position : positions) {
                                        if (position.getXDirAdj() >= 190) {
                                            continue;
                                        }
                                        assertTrue(
                                                position.getXDirAdj() >= 29.9
                                                        && position.getXDirAdj()
                                                                        + position.getWidthDirAdj()
                                                                <= 110.1);
                                        assertTrue(position.getYDirAdj() < 60);
                                        if (baselines.stream()
                                                .noneMatch(
                                                        baseline ->
                                                                Math.abs(
                                                                                baseline
                                                                                        - position
                                                                                                .getYDirAdj())
                                                                        < .1)) {
                                            baselines.add((double) position.getYDirAdj());
                                        }
                                    }
                                }
                                super.writeString(value, positions);
                            }
                        };
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(document);
                for (String word : List.of("ALPHA", "BETA", "GAMMA", "DELTA")) {
                    assertEquals(1, text.split(word, -1).length - 1);
                }
                assertTrue(baselines.size() >= 2);
            }
        }
    }

    @Test
    void balancedColumnsAndSpanningHeadingKeepEveryLineOnce() throws Exception {
        StringBuilder html =
                new StringBuilder(
                        "<style>@page{size:300pt"
                            + " 240pt;margin:20pt}body{margin:0}.columns{column-count:2;column-gap:20pt;column-rule:1pt"
                            + " solid red}.columns"
                            + " h2{column-span:all}p{margin:0;line-height:20pt}</style><div"
                            + " class='columns'>");
        for (int index = 1; index <= 8; index++) {
            html.append("<p>LINE").append(index).append("</p>");
        }
        html.append("<h2>FULL WIDTH</h2>");
        for (int index = 9; index <= 16; index++) {
            html.append("<p>LINE").append(index).append("</p>");
        }
        html.append("</div>");
        try (PDDocument document =
                Loader.loadPDF(new HtmlPdfConverter().convert(html.toString()))) {
            String text = new PDFTextStripper().getText(document);
            for (int index = 1; index <= 16; index++) {
                assertEquals(1, text.split("LINE" + index + "(?:\\s|$)", -1).length - 1);
            }
            assertTrue(text.contains("FULL WIDTH"));
            List<TextPosition> positions = new ArrayList<>();
            new PDFTextStripper() {
                @Override
                protected void processTextPosition(TextPosition position) {
                    positions.add(position);
                    assertTrue(
                            position.getXDirAdj() >= 19.9
                                    && position.getXDirAdj() + position.getWidthDirAdj() <= 280.1);
                    assertTrue(position.getYDirAdj() >= 19.9 && position.getYDirAdj() <= 220.1);
                    super.processTextPosition(position);
                }
            }.getText(document);
            assertTrue(positions.stream().anyMatch(position -> position.getXDirAdj() >= 160));
        }
    }

    @Test
    void marginTextWrapsAndSideBoxesStayInTheirRegions() throws Exception {
        String html =
                "<style>@page{size:300pt 240pt;margin:50pt;@top-center{content:'HEADER LINE ONE\\A"
                    + " HEADER LINE"
                    + " TWO';white-space:pre-line;line-height:12pt;vertical-align:top;padding-top:4pt}@left-middle{content:'SIDE';font-size:8pt}@bottom-right-corner{content:'CORNER';font-size:6pt}}body{margin:0}</style><p>BODY</p>";
        try (PDDocument document = Loader.loadPDF(new HtmlPdfConverter().convert(html))) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("HEADER LINE ONE"));
            assertTrue(text.contains("HEADER LINE TWO"));
            assertTrue(text.contains("SIDE"));
            assertTrue(text.contains("CORNER"));
            new PDFTextStripper() {
                @Override
                protected void writeString(String value, List<TextPosition> positions)
                        throws java.io.IOException {
                    if (value.contains("HEADER")) {
                        assertTrue(positions.get(0).getYDirAdj() < 50);
                    }
                    if (value.equals("SIDE")) {
                        assertTrue(positions.get(0).getXDirAdj() < 50);
                        assertTrue(
                                positions.get(0).getYDirAdj() > 95
                                        && positions.get(0).getYDirAdj() < 145);
                    }
                    if (value.equals("CORNER")) {
                        assertTrue(positions.get(0).getXDirAdj() >= 250);
                        assertTrue(positions.get(0).getYDirAdj() >= 190);
                    }
                    super.writeString(value, positions);
                }
            }.getText(document);
        }
    }

    @Test
    void namedStringsSelectFirstLastAndFirstExcept() throws Exception {
        String html =
                "<style>@page{size:300pt"
                    + " 240pt;margin:30pt;@top-left{content:string(section,first)}@top-right{content:string(section,last)}@bottom-center{content:string(section,first-except)}}body{margin:0}h2{string-set:section"
                    + " content();font-size:12pt;margin:0}</style><h2>FIRST</h2><p>Body</p><h2>LAST</h2><div"
                    + " style='break-before:page'>Continuation</div>";
        try (PDDocument document = Loader.loadPDF(new HtmlPdfConverter().convert(html))) {
            assertEquals(2, document.getNumberOfPages());
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setEndPage(1);
            String first = stripper.getText(document);
            assertEquals(2, first.split("FIRST", -1).length - 1);
            assertEquals(2, first.split("LAST", -1).length - 1);
            stripper.setStartPage(2);
            stripper.setEndPage(2);
            String second = stripper.getText(document);
            assertEquals(3, second.split("LAST", -1).length - 1);
        }
    }
}
