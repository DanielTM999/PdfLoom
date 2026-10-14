package dtm.pdfloom;

import static org.junit.jupiter.api.Assertions.*;

import dtm.pdfloom.config.*;
import dtm.pdfloom.impl.HtmlPdfConverter;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class PagedMediaTest {
    @Test
    void leftBreakInsertsBlankAndTotalIncludesIt() throws Exception {
        String html =
                "<style>@page{size:300pt 300pt;margin:20pt;@bottom-center{content:counter(page) '/'"
                    + " counter(pages)}}@page:blank{@top-center{content:'EM"
                    + " BRANCO'}}body{margin:0}</style><h1 style='break-before:left'>ESQUERDA</h1>";
        try (org.apache.pdfbox.pdmodel.PDDocument d =
                Loader.loadPDF(
                        new HtmlPdfConverter(
                                        PdfConversionConfig.builder().diagnostics(x -> {
                                        }).build())
                                .convert(html))) {
            assertEquals(2, d.getNumberOfPages());
            PDFTextStripper t = new PDFTextStripper();
            t.setStartPage(1);
            t.setEndPage(1);
            assertTrue(t.getText(d).contains("EM BRANCO"));
            assertFalse(t.getText(d).contains("ESQUERDA"));
            t.setStartPage(2);
            t.setEndPage(2);
            assertTrue(t.getText(d).contains("ESQUERDA"));
            assertTrue(t.getText(d).contains("2/2"));
        }
    }

    @Test
    void tableFooterRepeatsOnContinuedPages() throws Exception {
        StringBuilder html =
                new StringBuilder(
                        "<style>@page{size:300pt"
                            + " 300pt;margin:20pt}body{margin:0}table{width:100%}td,th{padding:3pt}</style><table><thead><tr><th>CABEÇALHO</th></tr></thead><tbody>");
        for (int i = 0; i < 80; i++) {
            html.append("<tr><td>Registro ").append(i).append("</td></tr>");
        }
        html.append("</tbody><tfoot><tr><td>RODAPÉ TABELA</td></tr></tfoot></table>");
        try (org.apache.pdfbox.pdmodel.PDDocument d =
                Loader.loadPDF(
                        new HtmlPdfConverter(
                                        PdfConversionConfig.builder().diagnostics(x -> {
                                        }).build())
                                .convert(html.toString()))) {
            assertTrue(d.getNumberOfPages() > 2);
            PDFTextStripper t = new PDFTextStripper();
            for (int i = 1; i <= d.getNumberOfPages(); i++) {
                t.setStartPage(i);
                t.setEndPage(i);
                assertTrue(t.getText(d).contains("RODAPÉ TABELA"), "Page " + i);
            }
        }
    }

    @Test
    void nestedCountersAndAfterUseTheCorrectScope() throws Exception {
        String html =
                "<style>body{counter-reset:section}section{counter-increment:section}section::before{content:counters(section,'.')"
                    + " ' '} .nested{counter-reset:section}</style><section>OUTER<div"
                    + " class='nested'><section>INNER</section><section>INNER2</section></div></section><section>NEXT</section>";
        try (org.apache.pdfbox.pdmodel.PDDocument d =
                Loader.loadPDF(
                        new HtmlPdfConverter(
                                        PdfConversionConfig.builder().diagnostics(x -> {
                                        }).build())
                                .convert(html))) {
            String t = new PDFTextStripper().getText(d);
            assertTrue(t.contains("1.1"));
            assertTrue(t.contains("1.2"));
            assertTrue(t.contains("2 NEXT"));
        }
    }
}
