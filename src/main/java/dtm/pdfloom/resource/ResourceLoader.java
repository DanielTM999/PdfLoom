package dtm.pdfloom.resource;

import dtm.pdfloom.config.*;
import dtm.pdfloom.exception.PdfConversionException;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class ResourceLoader implements AutoCloseable {
    public record Data(URI uri, String mediaType, byte[] bytes) {
    }

    private final PdfConversionConfig config;
    private long loadedBytes;
    private dtm.pdfloom.resource.impl.HttpResourceResolver http;

    public ResourceLoader(PdfConversionConfig c) {
        config = c;
    }

    public static byte[] readLimited(InputStream stream, long limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = stream.read(buf)) != -1) {
            total += n;
            if (total > limit) {
                throw new IOException("Resource/input byte limit exceeded: " + limit);
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    public Data load(String reference, URI origin) {
        try {
            if (reference.startsWith("data:")) {
                int comma = reference.indexOf(',');
                if (comma < 0) {
                    throw new IOException("Malformed data URI");
                }
                if (reference.length() > config.maxResourceBytes * 4 + 1024) {
                    throw new IOException("Data URI exceeds limit");
                }
                String head = reference.substring(5, comma),
                        payload = reference.substring(comma + 1);
                byte[] bytes =
                        head.toLowerCase(Locale.ROOT).endsWith(";base64")
                                ? Base64.getDecoder().decode(payload)
                                : decodePercent(payload);
                if (bytes.length > config.maxResourceBytes) {
                    throw new IOException("Data URI exceeds limit");
                }
                account(bytes.length);
                return new Data(URI.create("data:resource"), head.split(";")[0], bytes);
            }
            if (config.localFiles) {
                java.nio.file.Path local =
                        dtm.pdfloom.resource.impl.LocalFiles.path(reference, origin);
                if (local != null) {
                    if (!java.nio.file.Files.isRegularFile(local)) {
                        return missing(
                                reference, new java.nio.file.NoSuchFileException(local.toString()));
                    }
                    try (InputStream stream = java.nio.file.Files.newInputStream(local)) {
                        byte[] bytes =
                                readLimited(
                                        stream,
                                        Math.min(
                                                config.maxResourceBytes,
                                                config.maxTotalResourceBytes - loadedBytes));
                        account(bytes.length);
                        return new Data(
                                local.toUri(), java.nio.file.Files.probeContentType(local), bytes);
                    }
                }
            }
            URI ref = URI.create(reference.replace(" ", "%20"));
            URI absolute = origin == null ? ref : origin.resolve(ref);
            boolean remote =
                    Set.of("http", "https")
                            .contains(
                                    absolute.getScheme() == null
                                            ? ""
                                            : absolute.getScheme().toLowerCase(Locale.ROOT));
            ResourceResolver resolver = config.resolver;
            if (remote) {
                if (config.remoteResourcePolicy
                        == PdfConversionConfig.RemoteResourcePolicy.DISABLED) {
                    throw new IOException("Remote resources disabled");
                }
                if (http == null) {
                    http =
                            new dtm.pdfloom.resource.impl.HttpResourceResolver(
                                    config.resolver,
                                    config.remoteHosts,
                                    config.remoteTimeout,
                                    config.maxResourceBytes);
                }
                resolver = http;
            }
            try (ResolvedResource r = resolver.resolve(reference, origin)) {
                byte[] bytes =
                        readLimited(
                                r.stream(),
                                Math.min(
                                        config.maxResourceBytes,
                                        config.maxTotalResourceBytes - loadedBytes));
                account(bytes.length);
                return new Data(r.uri(), r.mediaType(), bytes);
            }
        } catch (FileNotFoundException | NoSuchElementException e) {
            return missing(reference, e);
        } catch (java.nio.file.NoSuchFileException e) {
            return missing(reference, e);
        } catch (IOException | IllegalArgumentException e) {
            throw new PdfConversionException(
                    "Resource resolution failed: " + abbreviate(reference), e);
        }
    }

    private void account(long count) throws IOException {
        loadedBytes += count;
        if (loadedBytes > config.maxTotalResourceBytes) {
            throw new IOException("Total resource budget exceeded");
        }
    }

    public void accountGenerated(long bytes) {
        try {
            if (bytes > config.maxResourceBytes) {
                throw new IOException("Generated resource budget exceeded");
            }
            account(bytes);
        } catch (IOException exception) {
            throw new PdfConversionException("Generated resource limit exceeded", exception);
        }
    }

    @Override
    public void close() {
        if (http != null) {
            http.close();
        }
    }

    public boolean skipInvalid(String ref, Exception e) {
        if (config.missingResourcePolicy == PdfConversionConfig.MissingResourcePolicy.FAIL) {
            return false;
        }
        config.report(
                new Diagnostic(
                        Diagnostic.Severity.WARNING,
                        "INVALID_RESOURCE",
                        abbreviate(ref.startsWith("data:") ? "data URI" : ref)
                                + ": "
                                + e.getMessage()));
        return true;
    }

    private Data missing(String ref, Exception e) {
        if (config.missingResourcePolicy == PdfConversionConfig.MissingResourcePolicy.FAIL) {
            throw new PdfConversionException("Missing resource: " + abbreviate(ref), e);
        }
        config.report(
                new Diagnostic(Diagnostic.Severity.WARNING, "MISSING_RESOURCE", abbreviate(ref)));
        return null;
    }

    private String abbreviate(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    private byte[] decodePercent(String value) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < value.length(); ) {
            if (value.charAt(i) == '%') {
                if (i + 2 >= value.length()) {
                    throw new IOException("Invalid percent encoding");
                }
                out.write(Integer.parseInt(value.substring(i + 1, i + 3), 16));
                i += 3;
            } else {
                int cp = value.codePointAt(i);
                out.writeBytes(new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8));
                i += Character.charCount(cp);
            }
        }
        return out.toByteArray();
    }
}
