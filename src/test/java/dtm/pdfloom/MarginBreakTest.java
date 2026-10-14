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

class MarginBreakTest {
    private double baseline(String policy) throws Exception {
        String html =
                "<style>@page{size:240pt"
                    + " 200pt;margin:20pt}body{margin:0}section{margin-top:10pt;height:40pt;break-inside:avoid;margin-break:"
                        + policy
                        + "}</style><div style='height:125pt'></div><section>KEPT</section>";
        try (PDDocument document = Loader.loadPDF(new HtmlPdfConverter().convert(html))) {
            assertEquals(2, document.getNumberOfPages());
            List<Double> positions = new ArrayList<>();
            PDFTextStripper text =
                    new PDFTextStripper() {
                        @Override
                        protected void processTextPosition(TextPosition position) {
                            if (position.getUnicode().equals("K")) {
                                positions.add((double) position.getYDirAdj());
                            }
                            super.processTextPosition(position);
                        }
                    };
            text.setStartPage(2);
            text.setEndPage(2);
            text.getText(document);
            assertEquals(1, positions.size());
            return positions.get(0);
        }
    }

    @Test
    void indivisibleBlocksKeepTheirLeadingMarginWhenRequested() throws Exception {
        assertEquals(10, baseline("keep") - baseline("auto"), .05);
    }
}
