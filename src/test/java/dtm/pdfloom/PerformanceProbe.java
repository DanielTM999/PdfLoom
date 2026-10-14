package dtm.pdfloom;

import dtm.pdfloom.config.PdfConversionConfig;
import dtm.pdfloom.impl.HtmlPdfConverter;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;

import java.nio.file.*;

public final class PerformanceProbe {
    public static void main(String[] args) throws Exception {
        int rows = args.length == 0 ? 10000 : Integer.parseInt(args[0]);
        StringBuilder html =
                new StringBuilder(
                        "<style>@page{margin:20mm;@bottom-center{content:counter(page) '/'"
                            + " counter(pages)}}body{font-size:9pt;margin:0}table{width:100%;border-collapse:collapse}td,th{padding:2pt;border:.5pt"
                            + " solid"
                            + " #555}</style><table><thead><tr><th>IDENTIFICADOR</th><th>CONTEÚDO</th></tr></thead><tbody>");
        for (int i = 0; i < rows; i++) {
            html.append("<tr><td>ROW-")
                    .append(i)
                    .append("</td><td>Documento extenso com acentuação e conteúdo.</td></tr>");
        }
        html.append("</tbody></table>");
        Path path = Path.of("target/performance.pdf");
        long start = System.nanoTime();
        new HtmlPdfConverter(PdfConversionConfig.builder().diagnostics(d -> {
        }).build())
                .convert(html.toString(), path);
        double seconds = (System.nanoTime() - start) / 1e9;
        try (org.apache.pdfbox.pdmodel.PDDocument pdf = Loader.loadPDF(path.toFile())) {
            String text = new PDFTextStripper().getText(pdf);
            for (int i = 0; i < rows; i++) {
                if (!text.contains("ROW-" + i + "\n") && !text.contains("ROW-" + i + " ")) {
                    throw new AssertionError("Missing row " + i);
                }
            }
            String result =
                    "rows="
                            + rows
                            + " pages="
                            + pdf.getNumberOfPages()
                            + " seconds="
                            + seconds
                            + " bytes="
                            + Files.size(path)
                            + " maxHeap="
                            + Runtime.getRuntime().maxMemory();
            System.out.println(result);
            Files.writeString(Path.of("target/performance-result.txt"), result);
        }
    }
}
