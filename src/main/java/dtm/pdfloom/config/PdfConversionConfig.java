package dtm.pdfloom.config;

import dtm.pdfloom.resource.ResourceResolver;
import dtm.pdfloom.resource.impl.FileSystemResourceResolver;

import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

public final class PdfConversionConfig {
    public enum UnsupportedCssPolicy {
        WARN,
        FAIL
    }

    public enum MissingResourcePolicy {
        FAIL,
        WARN_AND_SKIP
    }

    public enum RemoteResourcePolicy {
        DISABLED,
        ALLOWLIST
    }

    public record FontSource(String family, int weight, boolean italic, String reference) {
        public FontSource {
            Objects.requireNonNull(family);
            Objects.requireNonNull(reference);
        }
    }

    public record Metadata(String title, String author, String subject) {
    }

    public final double pageWidth, pageHeight, marginTop, marginRight, marginBottom, marginLeft;
    public final Charset charset;
    public final URI baseUri;
    public final ResourceResolver resolver;
    public final boolean localFiles;
    public final MissingResourcePolicy missingResourcePolicy;
    public final UnsupportedCssPolicy unsupportedCssPolicy;
    public final RemoteResourcePolicy remoteResourcePolicy;
    public final Set<String> remoteHosts;
    public final java.time.Duration remoteTimeout;
    public final long maxInputBytes, maxResourceBytes, maxTotalResourceBytes;
    public final int maxElements, maxDepth, maxPages, maxLayoutPasses;
    public final String defaultFont;
    public final Metadata metadata;
    public final List<FontSource> fonts;
    public final Consumer<Diagnostic> diagnostics;

    private PdfConversionConfig(Builder b) {
        pageWidth = b.width;
        pageHeight = b.height;
        marginTop = b.top;
        marginRight = b.right;
        marginBottom = b.bottom;
        marginLeft = b.left;
        charset = b.charset;
        baseUri = b.base;
        resolver = b.resolver;
        localFiles = b.localFiles;
        missingResourcePolicy = b.policy;
        unsupportedCssPolicy = b.unsupportedCssPolicy;
        remoteHosts = Set.copyOf(b.remoteHosts);
        remoteResourcePolicy =
                remoteHosts.isEmpty()
                        ? RemoteResourcePolicy.DISABLED
                        : RemoteResourcePolicy.ALLOWLIST;
        remoteTimeout = b.remoteTimeout;
        maxInputBytes = b.input;
        maxResourceBytes = b.resource;
        maxTotalResourceBytes = b.totalResource;
        maxElements = b.elements;
        maxDepth = b.depth;
        maxPages = b.pages;
        maxLayoutPasses = b.passes;
        defaultFont = b.font;
        metadata = b.metadata;
        fonts = List.copyOf(b.fonts);
        diagnostics = b.diagnostics;
        if (!Double.isFinite(
                        pageWidth
                                + pageHeight
                                + marginTop
                                + marginRight
                                + marginBottom
                                + marginLeft)
                || pageWidth <= 0
                || pageHeight <= 0
                || marginTop < 0
                || marginRight < 0
                || marginBottom < 0
                || marginLeft < 0
                || marginLeft + marginRight >= pageWidth
                || marginTop + marginBottom >= pageHeight) {
            throw new IllegalArgumentException("Invalid page dimensions/margins");
        }
        if (inputLimitInvalid()) {
            throw new IllegalArgumentException("Limits must be positive");
        }
        if (remoteTimeout.isZero() || remoteTimeout.isNegative()) {
            throw new IllegalArgumentException("Remote timeout must be positive");
        }
    }

    private boolean inputLimitInvalid() {
        return maxInputBytes < 1
                || maxResourceBytes < 1
                || maxTotalResourceBytes < 1
                || maxElements < 1
                || maxDepth < 1
                || maxPages < 1
                || maxLayoutPasses < 1;
    }

    public static Builder builder() {
        return new Builder();
    }

    public void report(Diagnostic diagnostic) {
        if (unsupportedCssPolicy == UnsupportedCssPolicy.FAIL
                && (diagnostic.code().startsWith("UNSUPPORTED")
                        || diagnostic.code().startsWith("PARTIAL")
                        || diagnostic.code().equals("CSS_SKIPPED_SYNTAX"))) {
            throw new dtm.pdfloom.exception.PdfConversionException(
                    diagnostic.code() + ": " + diagnostic.message());
        }
        diagnostics.accept(diagnostic);
    }

    public static final class Builder {
        private double width = 595.2756,
                height = 841.8898,
                top = 56.6929,
                right = 56.6929,
                bottom = 56.6929,
                left = 56.6929;
        private Charset charset = StandardCharsets.UTF_8;
        private URI base;
        private ResourceResolver resolver = new FileSystemResourceResolver(Path.of("."));
        private boolean customResolver;
        private MissingResourcePolicy policy = MissingResourcePolicy.FAIL;
        private boolean localFiles;
        private UnsupportedCssPolicy unsupportedCssPolicy = UnsupportedCssPolicy.WARN;
        private long input = 64L * 1024 * 1024,
                resource = 32L * 1024 * 1024,
                totalResource = 128L * 1024 * 1024;
        private int elements = 200_000, depth = 512, pages = 10_000, passes = 6;
        private String font = "Roboto";
        private Metadata metadata = new Metadata(null, null, null);
        private final List<FontSource> fonts = new ArrayList<>();
        private Consumer<Diagnostic> diagnostics =
                d ->
                        java.util.logging.Logger.getLogger("dtm.pdfloom")
                                .warning(d.code() + ": " + d.message());
        private final Set<String> remoteHosts = new HashSet<>();
        private java.time.Duration remoteTimeout = java.time.Duration.ofSeconds(15);

        public Builder pageSize(double width, double height) {
            this.width = width;
            this.height = height;
            return this;
        }

        public Builder landscape() {
            double w = width;
            width = height;
            height = w;
            return this;
        }

        public Builder margins(double all) {
            return margins(all, all, all, all);
        }

        public Builder margins(double t, double r, double b, double l) {
            top = t;
            right = r;
            bottom = b;
            left = l;
            return this;
        }

        public Builder charset(Charset c) {
            charset = Objects.requireNonNull(c);
            return this;
        }

        public Builder baseUri(URI u) {
            base = u;
            return this;
        }

        public Builder baseDirectory(Path p) {
            if (!customResolver) {
                resolver = new FileSystemResourceResolver(p);
            }
            base = p.toAbsolutePath().normalize().toUri();
            if (!base.toString().endsWith("/")) {
                base = URI.create(base + "/");
            }
            return this;
        }

        public Builder resourceResolver(ResourceResolver r) {
            resolver = Objects.requireNonNull(r);
            customResolver = true;
            return this;
        }

        public Builder allowLocalFiles() {
            localFiles = true;
            return this;
        }

        public Builder missingResourcePolicy(MissingResourcePolicy p) {
            policy = Objects.requireNonNull(p);
            return this;
        }

        public Builder unsupportedCssPolicy(UnsupportedCssPolicy policy) {
            unsupportedCssPolicy = Objects.requireNonNull(policy);
            return this;
        }

        public Builder remoteHosts(String... hosts) {
            for (String h : hosts) {
                String host = Objects.requireNonNull(h).toLowerCase(Locale.ROOT);
                if (host.isBlank() || host.contains("/") || host.contains("*")) {
                    throw new IllegalArgumentException("Use exact host names");
                }
                remoteHosts.add(host);
            }
            return this;
        }

        public Builder remoteTimeout(java.time.Duration timeout) {
            remoteTimeout = Objects.requireNonNull(timeout);
            return this;
        }

        public Builder maxInputBytes(long n) {
            input = n;
            return this;
        }

        public Builder maxResourceBytes(long n) {
            resource = n;
            return this;
        }

        public Builder maxTotalResourceBytes(long n) {
            totalResource = n;
            return this;
        }

        public Builder maxElements(int n) {
            elements = n;
            return this;
        }

        public Builder maxDepth(int n) {
            depth = n;
            return this;
        }

        public Builder maxPages(int n) {
            pages = n;
            return this;
        }

        public Builder maxLayoutPasses(int n) {
            passes = n;
            return this;
        }

        public Builder defaultFont(String f) {
            font = Objects.requireNonNull(f);
            return this;
        }

        public Builder font(String family, int weight, boolean italic, String ref) {
            fonts.add(new FontSource(family, weight, italic, ref));
            return this;
        }

        public Builder standardFont(String family, int weight, boolean italic, String pdfName) {
            return font(family, weight, italic, "standard14:" + Objects.requireNonNull(pdfName));
        }

        public Builder metadata(String title, String author, String subject) {
            metadata = new Metadata(title, author, subject);
            return this;
        }

        public Builder diagnostics(Consumer<Diagnostic> callback) {
            diagnostics = Objects.requireNonNull(callback);
            return this;
        }

        public PdfConversionConfig build() {
            return new PdfConversionConfig(this);
        }
    }
}
