package dtm.pdfloom.image;

import dtm.pdfloom.config.*;
import dtm.pdfloom.exception.PdfConversionException;
import dtm.pdfloom.resource.*;

import java.io.*;
import java.net.URI;
import java.util.*;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

public final class ImageStore {
    public record Image(String key, byte[] bytes, int width, int height) {
    }

    private final Map<String, Image> images = new LinkedHashMap<>();
    private final ResourceLoader loader;
    private List<dtm.pdfloom.font.FontData> fonts = List.of();

    public ImageStore(ResourceLoader loader) {
        this.loader = loader;
    }

    public Image load(String ref, URI origin) {
        String key = (origin == null ? "" : origin) + "|" + ref;
        Image cached = images.get(key);
        if (cached != null) {
            return cached;
        }
        ResourceLoader.Data data = loader.load(ref, origin);
        if (data == null) {
            return null;
        }
        byte[] bytes = data.bytes();
        String prefix =
                new String(
                        bytes,
                        0,
                        Math.min(bytes.length, 500),
                        java.nio.charset.StandardCharsets.UTF_8);
        if ((data.mediaType() != null && data.mediaType().contains("svg"))
                || prefix.contains("<svg")) {
            bytes =
                    SvgRasterizer.rasterize(
                            bytes,
                            data.uri().getScheme().equals("data") ? origin : data.uri(),
                            loader,
                            fonts);
        }
        try (ImageInputStream in =
                ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new IOException("Unsupported image format (PNG/JPEG/GIF/BMP/SVG supported)");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                int w = reader.getWidth(0), h = reader.getHeight(0);
                if (w < 1 || h < 1 || (long) w * h > 40_000_000) {
                    throw new IOException("Image pixel limit exceeded");
                }
                Image image = new Image("image:" + images.size(), bytes, w, h);
                images.put(key, image);
                return image;
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            if (loader.skipInvalid(ref, e)) {
                return null;
            }
            throw new PdfConversionException(
                    "Image decoding failed: " + (ref.startsWith("data:") ? "data URI" : ref), e);
        }
    }

    public Collection<Image> all() {
        return images.values();
    }

    public void fonts(List<dtm.pdfloom.font.FontData> fonts) {
        this.fonts = List.copyOf(fonts);
    }

    public Image inlineSvg(String markup, URI origin) {
        String normalized =
                markup.replace("viewbox=", "viewBox=")
                        .replace("preserveaspectratio=", "preserveAspectRatio=");
        String reference =
                "data:image/svg+xml;base64,"
                        + java.util.Base64.getEncoder()
                                .encodeToString(
                                        normalized.getBytes(
                                                java.nio.charset.StandardCharsets.UTF_8));
        return load(reference, origin);
    }

    public Image generated(String key) {
        return images.get("generated:" + key);
    }

    public void accountGenerated(long bytes) {
        loader.accountGenerated(bytes);
    }

    public Image generated(String key, byte[] bytes, int width, int height) {
        Image cached = images.get("generated:" + key);
        if (cached != null) {
            return cached;
        }
        if (width < 1 || height < 1 || (long) width * height > 40_000_000) {
            throw new PdfConversionException("Generated image pixel limit exceeded");
        }
        loader.accountGenerated(bytes.length);
        Image image = new Image("image:" + images.size(), bytes, width, height);
        images.put("generated:" + key, image);
        return image;
    }
}
