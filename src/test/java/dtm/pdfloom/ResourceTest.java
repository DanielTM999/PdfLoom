package dtm.pdfloom;

import static org.junit.jupiter.api.Assertions.*;

import dtm.pdfloom.config.*;
import dtm.pdfloom.exception.PdfConversionException;
import dtm.pdfloom.impl.HtmlPdfConverter;
import dtm.pdfloom.resource.*;
import dtm.pdfloom.resource.impl.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;

class ResourceTest {
    @TempDir
    Path directory;

    @Test
    void virtualRootRelativeCssAndTraversal() throws Exception {
        Path root = directory.resolve("root");
        Files.createDirectories(root.resolve("templates"));
        Files.createDirectories(root.resolve("assets/css"));
        Files.writeString(
                root.resolve("assets/css/main.css"), "@import 'nested.css';p{color:green}");
        Files.writeString(root.resolve("assets/css/nested.css"), "p{font-size:15pt}");
        FileSystemResourceResolver resolver = new FileSystemResourceResolver(root);
        try (dtm.pdfloom.resource.ResolvedResource r =
                resolver.resolve(
                        "/assets/css/main.css", root.resolve("templates/report.html").toUri())) {
            assertTrue(r.uri().toString().contains("assets/css"));
        }
        assertThrows(
                IOException.class,
                () -> resolver.resolve("../../escape", root.resolve("templates/r.html").toUri()));
        assertThrows(IOException.class, () -> resolver.resolve("/%2e%2e/escape", null));
        assertThrows(IOException.class, () -> resolver.resolve("https://example.com/a", null));
        assertThrows(IOException.class, () -> resolver.resolve("//server/share", null));
        Files.writeString(
                root.resolve("templates/report.html"),
                "<link rel='stylesheet' href='/assets/css/main.css'><p>Relativo</p>");
        assertTrue(
                new HtmlPdfConverter(resolver)
                                .convert(root.resolve("templates/report.html").toFile())
                                .length
                        > 1000);
    }

    @Test
    void symlinkEscapeDeniedWhenSupported() throws Exception {
        Path root = directory.resolve("root");
        Files.createDirectories(root);
        Path outside = directory.resolve("secret");
        Files.writeString(outside, "secret");
        try {
            Files.createSymbolicLink(root.resolve("link"), outside);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            Assumptions.assumeTrue(false, "Platform cannot create symlink: " + e.getMessage());
        }
        assertThrows(
                IOException.class,
                () -> new FileSystemResourceResolver(root).resolve("link", null));
    }

    @Test
    void memoryAndClasspath() throws Exception {
        MemoryResourceResolver m =
                new MemoryResourceResolver(Map.of("/a.css", "p{color:red}".getBytes()));
        try (dtm.pdfloom.resource.ResolvedResource r =
                m.resolve("../a.css", URI.create("memory:/styles/report.css"))) {
            assertEquals("memory:/a.css", r.uri().toString());
        }
        assertThrows(IOException.class, () -> m.resolve("../../x", URI.create("memory:/a.css")));
        try (dtm.pdfloom.resource.ResolvedResource r =
                new ClasspathResourceResolver("dtm/pdfloom/fonts")
                        .resolve("Roboto-Regular.ttf", null)) {
            assertTrue(r.stream().readNBytes(4).length == 4);
        }
        assertThrows(
                IOException.class,
                () ->
                        new ClasspathResourceResolver("dtm/pdfloom/fonts")
                                .resolve("../LICENSE", null));
    }

    @Test
    void missingPolicyDoesNotSuppressSecurity() throws Exception {
        List<Diagnostic> diag = new ArrayList<>();
        PdfConversionConfig c =
                PdfConversionConfig.builder()
                        .resourceResolver(new FileSystemResourceResolver(directory))
                        .missingResourcePolicy(
                                PdfConversionConfig.MissingResourcePolicy.WARN_AND_SKIP)
                        .diagnostics(diag::add)
                        .build();
        assertTrue(
                new HtmlPdfConverter(c).convert("<img src='missing.png'><p>ok</p>").length > 1000);
        assertTrue(diag.stream().anyMatch(d -> d.code().equals("MISSING_RESOURCE")));
        assertThrows(
                PdfConversionException.class,
                () -> new HtmlPdfConverter(c).convert("<img src='https://example.com/img.png'>"));
    }

    @Test
    void dataUriAndSizeLimits() {
        ResourceLoader l =
                new ResourceLoader(PdfConversionConfig.builder().maxResourceBytes(10).build());
        assertArrayEquals(new byte[] {65, 43, 66}, l.load("data:text/plain,A+B", null).bytes());
        assertThrows(
                PdfConversionException.class,
                () ->
                        l.load(
                                "data:text/plain;base64,"
                                        + Base64.getEncoder().encodeToString(new byte[11]),
                                null));
        assertThrows(
                PdfConversionException.class,
                () ->
                        new HtmlPdfConverter(PdfConversionConfig.builder().maxInputBytes(3).build())
                                .convert("1234"));
    }

    @Test
    void cyclicImportFails() {
        MemoryResourceResolver m =
                new MemoryResourceResolver(
                        Map.of(
                                "a.css",
                                "@import 'b.css';".getBytes(),
                                "b.css",
                                "@import 'a.css';".getBytes()));
        assertThrows(
                PdfConversionException.class,
                () ->
                        new HtmlPdfConverter(
                                        PdfConversionConfig.builder()
                                                .resourceResolver(m)
                                                .baseUri(URI.create("memory:/report.html"))
                                                .build())
                                .convert("<link rel='stylesheet' href='a.css'>"));
    }
}
