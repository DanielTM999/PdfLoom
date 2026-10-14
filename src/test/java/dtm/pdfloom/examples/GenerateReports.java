package dtm.pdfloom.examples;

import dtm.pdfloom.CompositionVisualFixtures;
import dtm.pdfloom.VisualFixtures;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

public final class GenerateReports {
    private record Entry(String title, String file, String group, String preview, int pages) {
    }

    private final Path output;
    private final List<Entry> entries = new ArrayList<>();

    private GenerateReports(Path output) {
        this.output = output;
    }

    public static void main(String[] arguments) throws Exception {
        Path output = Path.of(arguments.length == 0 ? "src/test/output/reports" : arguments[0]);
        Files.createDirectories(output);
        GenerateReports generator = new GenerateReports(output);
        generator.generate();
        System.out.println(
                "Generated "
                        + generator.entries.size()
                        + " PDFs: "
                        + output.resolve("index.html").toAbsolutePath());
    }

    private void generate() throws Exception {
        Report.main(new String[] {output.resolve("relatorio-exemplo.pdf").toString()});
        register("Relatório de exemplo", "relatorio-exemplo.pdf", "Exemplos");
        Files.write(output.resolve("layout-multipagina.pdf"), VisualFixtures.pdf());
        register("Layout com tabelas e paginação", "layout-multipagina.pdf", "Exemplos");
        Files.write(output.resolve("composicao-avancada.pdf"), CompositionVisualFixtures.pdf());
        register("Composição, Flex/Grid e tipografia", "composicao-avancada.pdf", "Exemplos");
        new dtm.pdfloom.impl.HtmlPdfConverter()
                .convert(
                        Files.readString(
                                Path.of("src/test/resources/examples/advanced-print.html")),
                        output.resolve("recursos-impressao.pdf"));
        register(
                "Camadas CSS, sumário, colunas e contadores", "recursos-impressao.pdf", "Exemplos");

        copyExisting(
                "target/performance.pdf",
                "documento-extenso-10000-registros.pdf",
                "Documento com 10.000 registros",
                "Documento extenso");
        registerExisting("example-ordinary.pdf", "Exemplo com JAR convencional", "Distribuição");
        registerExisting("example-shaded.pdf", "Exemplo com JAR Shade", "Distribuição");
        catalog();
    }

    private void copyExisting(String source, String destination, String title, String group)
            throws IOException {
        if (Files.isRegularFile(Path.of(source))) {
            Files.copy(
                    Path.of(source),
                    output.resolve(destination),
                    StandardCopyOption.REPLACE_EXISTING);
            register(title, destination, group);
        }
    }

    private void registerExisting(String file, String title, String group) throws IOException {
        if (Files.isRegularFile(output.resolve(file))) {
            register(title, file, group);
        }
    }

    private void register(String title, String file, String group) throws IOException {
        String preview = "previews/" + file.replace(".pdf", ".png");
        Path image = output.resolve(preview);
        Files.createDirectories(image.getParent());
        try (PDDocument document = Loader.loadPDF(output.resolve(file).toFile())) {
            if (document.getNumberOfPages() < 1) {
                throw new IOException("PDF has no pages: " + file);
            }
            if (!ImageIO.write(
                    new PDFRenderer(document).renderImageWithDPI(0, 96), "png", image.toFile())) {
                throw new IOException("Cannot write preview: " + image);
            }
            entries.add(new Entry(title, file, group, preview, document.getNumberOfPages()));
        }
    }

    private void catalog() throws IOException {
        Map<String, List<Entry>> groups = new LinkedHashMap<>();
        for (Entry entry : entries) {
            groups.computeIfAbsent(entry.group(), key -> new ArrayList<>()).add(entry);
        }
        StringBuilder html =
                new StringBuilder(
                        """
                        <!doctype html>
                        <html lang="pt-BR">
                        <head>
                        <meta charset="UTF-8">
                        <meta name="viewport" content="width=device-width, initial-scale=1">
                        <title>Relatórios PdfLoom</title>
                        <style>
                        *{box-sizing:border-box}body{margin:0;background:#f2f5f8;color:#183044;font:16px Arial,sans-serif}
                        main{max-width:1150px;margin:0 auto;padding:36px 24px}h1{margin:0 0 12px}h2{margin:34px 0 16px}
                        p{line-height:1.6}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(250px,1fr));gap:22px}
                        article{background:white;border:1px solid #d9e3ea;border-radius:10px;overflow:hidden}
                        article img{display:block;width:100%;height:300px;object-fit:contain;object-position:top;background:#e8eef3;padding:12px}
                        .details{padding:18px}h3{margin:0 0 10px;font-size:18px}.details p{margin:0 0 14px;color:#536575}
                        a{color:#086990}a.button{display:inline-block;padding:9px 14px;background:#086990;color:white;text-decoration:none;border-radius:5px}
                        </style>
                        </head>
                        <body><main>
                        <h1>Relatórios PdfLoom</h1>
                        <p>Abra os PDFs abaixo para revisar todas as páginas. As prévias mostram a primeira página.</p>
                        """);
        StringBuilder markdown =
                new StringBuilder(
                        "# Relatórios gerados\n\n"
                            + "[Abrir catálogo com prévias](index.html). Os PDFs de exemplo usam"
                            + " dados e imagens demonstrativos.\n\n");
        for (Map.Entry<String, List<Entry>> group : groups.entrySet()) {
            html.append("<h2>").append(escape(group.getKey())).append("</h2><div class='grid'>\n");
            markdown.append("## ").append(group.getKey()).append("\n\n");
            for (Entry entry : group.getValue()) {
                html.append("<article><a href='")
                        .append(entry.file())
                        .append("'><img src='")
                        .append(entry.preview())
                        .append("' alt='Prévia de ")
                        .append(escape(entry.title()))
                        .append("'></a><div class='details'><h3>")
                        .append(escape(entry.title()))
                        .append("</h3><p>")
                        .append(entry.pages())
                        .append(" página(s)</p><a class='button' href='")
                        .append(entry.file())
                        .append("'>Abrir PDF</a></div></article>\n");
                markdown.append("- [")
                        .append(entry.title())
                        .append("](")
                        .append(entry.file())
                        .append(") — ")
                        .append(entry.pages())
                        .append(" página(s).\n");
            }
            html.append("</div>\n");
            markdown.append('\n');
        }
        html.append("</main></body></html>\n");
        Files.writeString(output.resolve("index.html"), html, StandardCharsets.UTF_8);
        Files.writeString(output.resolve("README.md"), markdown, StandardCharsets.UTF_8);
    }

    private String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
