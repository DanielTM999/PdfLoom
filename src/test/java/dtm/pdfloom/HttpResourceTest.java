package dtm.pdfloom;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;

import dtm.pdfloom.config.*;
import dtm.pdfloom.exception.PdfConversionException;
import dtm.pdfloom.resource.ResourceLoader;

import org.junit.jupiter.api.Test;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

class HttpResourceTest {
    @Test
    void allowlistLimitsAndRedirectValidation() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/ok",
                e -> {
                    byte[] b = "p{color:red}".getBytes(StandardCharsets.UTF_8);
                    e.sendResponseHeaders(200, b.length);
                    e.getResponseBody().write(b);
                    e.close();
                });
        server.createContext(
                "/redirect",
                e -> {
                    e.getResponseHeaders().add("Location", "http://example.invalid/private");
                    e.sendResponseHeaders(302, -1);
                    e.close();
                });
        server.createContext(
                "/large",
                e -> {
                    byte[] b = new byte[100];
                    e.sendResponseHeaders(200, b.length);
                    e.getResponseBody().write(b);
                    e.close();
                });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            try (ResourceLoader loader =
                    new ResourceLoader(PdfConversionConfig.builder().build())) {
                assertThrows(PdfConversionException.class, () -> loader.load(base + "/ok", null));
            }
            try (ResourceLoader loader =
                    new ResourceLoader(
                            PdfConversionConfig.builder()
                                    .remoteHosts("127.0.0.1")
                                    .maxResourceBytes(30)
                                    .remoteTimeout(Duration.ofSeconds(2))
                                    .build())) {
                assertEquals(
                        "p{color:red}",
                        new String(
                                loader.load(base + "/ok", null).bytes(), StandardCharsets.UTF_8));
                assertThrows(
                        PdfConversionException.class, () -> loader.load(base + "/redirect", null));
                assertThrows(
                        PdfConversionException.class, () -> loader.load(base + "/large", null));
            }
        } finally {
            server.stop(0);
        }
    }
}
