package dtm.pdfloom.impl;

import dtm.pdfloom.PdfConverter;
import dtm.pdfloom.config.*;
import dtm.pdfloom.css.*;
import dtm.pdfloom.exception.PdfConversionException;
import dtm.pdfloom.font.*;
import dtm.pdfloom.html.*;
import dtm.pdfloom.image.ImageStore;
import dtm.pdfloom.internal.pdfbox.PdfBoxRenderer;
import dtm.pdfloom.internal.pdfbox.PdfFontRegistry;
import dtm.pdfloom.layout.*;
import dtm.pdfloom.page.*;
import dtm.pdfloom.pagination.*;
import dtm.pdfloom.render.*;
import dtm.pdfloom.resource.*;
import dtm.pdfloom.style.*;

import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;

public final class HtmlPdfConverter implements PdfConverter {
    private final PdfConversionConfig config;

    public HtmlPdfConverter() {
        this(PdfConversionConfig.builder().build());
    }

    public HtmlPdfConverter(ResourceResolver resolver) {
        this(PdfConversionConfig.builder().resourceResolver(resolver).build());
    }

    public HtmlPdfConverter(PdfConversionConfig config) {
        this.config = Objects.requireNonNull(config);
    }

    @Override
    public byte[] convert(String content) {
        Objects.requireNonNull(content);
        return bytes(content, config.baseUri);
    }

    @Override
    public byte[] convert(File source) {
        Objects.requireNonNull(source);
        try (InputStream in = Files.newInputStream(source.toPath())) {
            return bytes(read(in), source.toPath().toAbsolutePath().normalize().toUri());
        } catch (IOException e) {
            throw new PdfConversionException("Cannot read HTML file: " + source, e);
        }
    }

    @Override
    public byte[] convert(InputStream source) {
        Objects.requireNonNull(source);
        return bytes(read(source), config.baseUri);
    }

    @Override
    public void convert(String content, Path destination) {
        Objects.requireNonNull(content);
        file(content, config.baseUri, destination);
    }

    @Override
    public void convert(File source, Path destination) {
        Objects.requireNonNull(source);
        try (InputStream in = Files.newInputStream(source.toPath())) {
            file(read(in), source.toPath().toAbsolutePath().normalize().toUri(), destination);
        } catch (IOException e) {
            throw new PdfConversionException("Cannot read HTML file: " + source, e);
        }
    }

    @Override
    public void convert(InputStream source, Path destination) {
        Objects.requireNonNull(source);
        file(read(source), config.baseUri, destination);
    }

    private String read(InputStream source) {
        try {
            return new String(
                    ResourceLoader.readLimited(source, config.maxInputBytes), config.charset);
        } catch (IOException e) {
            throw new PdfConversionException("Cannot read HTML stream", e);
        }
    }

    private byte[] bytes(String content, URI origin) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            render(content, origin, output);
            return output.toByteArray();
        } catch (IOException e) {
            throw new PdfConversionException("Cannot produce PDF bytes", e);
        }
    }

    private void file(String content, URI origin, Path destination) {
        Objects.requireNonNull(destination);
        Path target = destination.toAbsolutePath().normalize(), temp = null;
        try {
            temp = Files.createTempFile(target.getParent(), ".pdfloom-", ".pdf");
            try (OutputStream out = Files.newOutputStream(temp)) {
                render(content, origin, out);
            }
            try {
                Files.move(
                        temp,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            temp = null;
        } catch (IOException e) {
            throw new PdfConversionException("Cannot write PDF: " + target, e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException e) {
                    config.report(
                            new Diagnostic(
                                    Diagnostic.Severity.ERROR,
                                    "TEMP_CLEANUP_FAILED",
                                    temp.toString()));
                }
            }
        }
    }

    private void render(String html, URI origin, OutputStream output) throws IOException {
        if (html.getBytes(config.charset).length > config.maxInputBytes) {
            throw new PdfConversionException("HTML byte limit exceeded");
        }
        HtmlParser.Document dom =
                new HtmlParser().parse(dtm.pdfloom.html.TableOfContents.prepare(html), config);
        try (ResourceLoader loader = new ResourceLoader(config);
                PDDocument pdf = new PDDocument(IOUtils.createTempFileOnlyStreamCache())) {
            StyleSheet css = new CascadeStyleResolver(config, loader).resolve(dom, origin);
            ImageStore images = new ImageStore(loader);
            PdfFontRegistry fonts = new PdfFontRegistry(pdf, config, loader, css, origin);
            images.fonts(fonts.fontData());
            PageSpec first = PageSpec.resolve(config, css, 1, "", false);
            List<RenderPage> pages = null;
            int guess = 1;
            boolean crossReferences =
                    java.util.stream.Stream.concat(
                                    css.before.values().stream(), css.after.values().stream())
                            .anyMatch(
                                    s ->
                                            s.get("content").contains("target-counter(")
                                                    || s.get("content")
                                                            .contains("target-counters("));
            Map<String, Integer> targets = Map.of();
            for (int pass = 0; pass < config.maxLayoutPasses; pass++) {
                LayoutResult layout =
                        new CssLayoutEngine(config, css, fonts, images, origin, guess, targets)
                                .layout(dom, first.contentWidth());
                pages = new FragmentPaginator(config, css).paginate(layout);
                int count = pages.size();
                Map<String, Integer> nextTargets = new HashMap<>();
                if (crossReferences) {
                    for (RenderPage page : pages) {
                        collectTargets(page.commands(), page.number(), nextTargets);
                    }
                }
                if (count == guess && targets.equals(nextTargets)) {
                    break;
                }
                if (pass == config.maxLayoutPasses - 1) {
                    throw new PdfConversionException("Page counters did not converge");
                }
                guess = count;
                targets = Map.copyOf(nextTargets);
            }
            for (RenderPage p : pages) {
                if (Math.abs(p.spec().contentWidth() - first.contentWidth()) > .1) {
                    config.report(
                            new Diagnostic(
                                    Diagnostic.Severity.WARNING,
                                    "VARIABLE_PAGE_WIDTH",
                                    "Page widths differ; flow width currently follows first page"));
                }
            }
            if (config.metadata.title() == null && !dom.title().isBlank()) {
                pdf.getDocumentInformation().setTitle(dom.title());
            }
            pages =
                    dtm.pdfloom.page.MarginContentLayout.prepare(
                            pages, dom, css, config, fonts, images, origin, targets);
            new PdfBoxRenderer(pdf, fonts, images, config).render(pages, output);
        } catch (PdfConversionException e) {
            throw e;
        } catch (IOException | IllegalArgumentException e) {
            throw new PdfConversionException("HTML/CSS to PDF conversion failed", e);
        }
    }

    private void collectTargets(List<Paint> commands, int page, Map<String, Integer> targets) {
        for (Paint command : commands) {
            if (command instanceof Paint.Destination destination) {
                targets.putIfAbsent(destination.name(), page);
            } else if (command instanceof Paint.Layer layer) {
                collectTargets(layer.commands(), page, targets);
            } else if (command instanceof Paint.Group group) {
                collectTargets(group.commands(), page, targets);
            }
        }
    }
}
