package dtm.pdfloom;

import dtm.pdfloom.config.PdfConversionConfig;
import dtm.pdfloom.impl.HtmlPdfConverter;
import dtm.pdfloom.resource.impl.ClasspathResourceResolver;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

public final class CompositionVisualFixtures {
    public static byte[] pdf() {
        PdfConversionConfig config =
                PdfConversionConfig.builder()
                        .resourceResolver(new ClasspathResourceResolver("/"))
                        .font("Arabic", 400, false, "/fonts/NotoSansArabic-Regular.ttf")
                        .diagnostics(diagnostic -> {
                        })
                        .build();
        String html =
                """
                <style>
                @page {size:360pt 480pt; margin:30pt; @bottom-center{content:counter(page) '/' counter(pages);}}
                body{margin:0;font-size:12pt}
                h1{font-size:16pt;margin:0 0 12pt}
                h2{font-size:12pt;margin:12pt 0 6pt}
                .stack{position:relative;height:85pt}
                .stack div{position:absolute;width:75pt;height:60pt;top:5pt}
                .red{background:#e44;left:0;z-index:3}
                .blue{background:#248bd0;left:35pt;z-index:1}
                .green{background:#32a879;left:70pt;z-index:2;opacity:.65}
                .flex{display:flex}
                .flex div{flex:0 1 200pt;height:50pt;box-sizing:border-box;padding:8pt;background:#edf4fb;border:1pt solid #568}
                .flex .minimum{min-width:180pt}
                .grid{display:grid;grid-template-columns:minmax(200pt,1fr) 1fr;gap:5pt}
                .grid div{padding:8pt;background:#eef8ee;border:1pt solid #586}
                .arabic{font-family:Arabic;direction:rtl;font-size:24pt;margin:10pt 0}
                .marked{font-size:18pt}
                </style>
                <h1>Composição e dimensões</h1>
                <h2>Contextos de empilhamento</h2>
                <div class='stack'><div class='red'></div><div class='blue'></div><div class='green'></div></div>
                <h2>Flex: mínimo e redistribuição</h2>
                <div class='flex'><div class='minimum'>180pt</div><div>60pt</div><div>60pt</div></div>
                <h2>Grid: mínimo de 200pt</h2>
                <div class='grid'><div>200pt</div><div>95pt</div></div>
                <h2>Texto bidirecional e marcas</h2>
                <p class='arabic'>سلام سلام</p>
                <p class='marked'>á é ö — Acentuação</p>
                """;
        return new HtmlPdfConverter(config).convert(html);
    }

    public static void main(String[] arguments) throws Exception {
        Path output = Path.of(arguments[0]);
        Files.createDirectories(output);
        byte[] bytes = pdf();
        Files.write(output.resolve("composition.pdf"), bytes);
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFRenderer renderer = new PDFRenderer(document);
            for (int page = 0; page < document.getNumberOfPages(); page++) {
                ImageIO.write(
                        renderer.renderImageWithDPI(page, 144),
                        "png",
                        output.resolve("composition-" + (page + 1) + ".png").toFile());
            }
        }
    }
}
