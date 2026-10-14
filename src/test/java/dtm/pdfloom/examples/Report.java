package dtm.pdfloom.examples;

import dtm.pdfloom.PdfConverter;
import dtm.pdfloom.config.PdfConversionConfig;
import dtm.pdfloom.impl.HtmlPdfConverter;
import dtm.pdfloom.resource.impl.FileSystemResourceResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class Report {
    public static void main(String[] args) throws IOException {
        Path root = Path.of("src/test/resources/examples").toAbsolutePath();
        Path destination =
                Path.of(
                        args.length == 0
                                ? "src/test/output/reports/relatorio-exemplo.pdf"
                                : args[0]);
        Files.createDirectories(destination.toAbsolutePath().getParent());
        PdfConverter converter =
                new HtmlPdfConverter(
                        PdfConversionConfig.builder()
                                .resourceResolver(new FileSystemResourceResolver(root))
                                .metadata("Relatório PdfLoom", "PdfLoom", "Exemplo de paginação")
                                .build());
        converter.convert(root.resolve("report.html").toFile(), destination);
    }
}
