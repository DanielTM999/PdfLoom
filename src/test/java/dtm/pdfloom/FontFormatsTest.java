package dtm.pdfloom;

import static org.junit.jupiter.api.Assertions.*;

import dtm.pdfloom.config.PdfConversionConfig;
import dtm.pdfloom.css.CascadeStyleResolver;
import dtm.pdfloom.font.TextMetrics;
import dtm.pdfloom.font.WoffDecoder;
import dtm.pdfloom.html.HtmlParser;
import dtm.pdfloom.impl.HtmlPdfConverter;
import dtm.pdfloom.internal.pdfbox.PdfFontRegistry;
import dtm.pdfloom.resource.ResourceLoader;
import dtm.pdfloom.style.ComputedStyle;
import dtm.pdfloom.style.StyleSheet;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.zip.Deflater;

class FontFormatsTest {
    @Test
    void originalCffFontPaintsContoursAndPreservesSearchableText() throws Exception {
        PdfConversionConfig config =
                PdfConversionConfig.builder()
                        .font(
                                "Source Serif",
                                400,
                                false,
                                "src/test/resources/fonts/source-serif/SourceSerifPro-Regular.otf")
                        .build();
        try (PDDocument pdf =
                Loader.loadPDF(
                        new HtmlPdfConverter(config)
                                .convert(
                                        "<p style='font:28pt Source Serif'>Acentos: ação, órgão,"
                                                + " café.</p>"))) {
            assertTrue(new PDFTextStripper().getText(pdf).contains("Acentos: ação, órgão, café."));
            java.awt.image.BufferedImage image = new PDFRenderer(pdf).renderImageWithDPI(0, 72);
            int dark = 0;
            for (int y = 0; y < 150; y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    if ((image.getRGB(x, y) & 0xffffff) != 0xffffff) {
                        dark++;
                    }
                }
            }
            assertTrue(dark > 500);
        }
    }

    @Test
    void serifUsesActualSerifFaceAndKerningSharesShapingMetrics() throws Exception {
        PdfConversionConfig config = PdfConversionConfig.builder().build();
        try (PDDocument pdf = new PDDocument();
                ResourceLoader loader = new ResourceLoader(config)) {
            StyleSheet sheet =
                    new CascadeStyleResolver(config, loader)
                            .resolve(new HtmlParser().parse("<p>X</p>", config), null);
            PdfFontRegistry fonts = new PdfFontRegistry(pdf, config, loader, sheet, null);
            TextMetrics.Font plain =
                    fonts.font(
                            new ComputedStyle(
                                    Map.of(
                                            "font-family",
                                            "serif",
                                            "font-size",
                                            "40pt",
                                            "font-kerning",
                                            "none")));
            TextMetrics.Font kerned =
                    fonts.font(
                            new ComputedStyle(
                                    Map.of(
                                            "font-family",
                                            "serif",
                                            "font-size",
                                            "40pt",
                                            "font-kerning",
                                            "normal")));
            assertTrue(plain.key().startsWith("liberation serif:"));
            assertTrue(fonts.outlined(kerned.key()));
            assertTrue(fonts.width("AV", kerned) < fonts.width("AV", plain));
        }
    }

    @Test
    void woffDecodesCompressedTablesAndEmbedsAsTrueType() throws Exception {
        byte[] ttf =
                Files.readAllBytes(
                        Path.of(
                                "src/main/resources/dtm/pdfloom/fonts/liberation/LiberationSerif-Regular.ttf"));
        byte[] woff = encode(ttf);
        byte[] decoded = WoffDecoder.decode(woff, 4_000_000);
        ByteBuffer original = ByteBuffer.wrap(ttf);
        ByteBuffer actual = ByteBuffer.wrap(decoded);
        assertEquals(original.getShort(4), actual.getShort(4));
        int count = Short.toUnsignedInt(original.getShort(4));
        for (int index = 0; index < count; index++) {
            int entry = 12 + index * 16;
            assertEquals(original.getInt(entry), actual.getInt(entry));
            int length = original.getInt(entry + 12);
            assertArrayEquals(
                    Arrays.copyOfRange(
                            ttf, original.getInt(entry + 8), original.getInt(entry + 8) + length),
                    Arrays.copyOfRange(
                            decoded, actual.getInt(entry + 8), actual.getInt(entry + 8) + length));
        }
        Path root = Path.of("target/font-formats");
        Files.createDirectories(root);
        Files.write(root.resolve("serif.woff"), woff);
        PdfConversionConfig config =
                PdfConversionConfig.builder()
                        .font(
                                "Web Serif",
                                400,
                                false,
                                root.resolve("serif.woff").toAbsolutePath().toUri().toString())
                        .build();
        try (PDDocument pdf =
                Loader.loadPDF(
                        new HtmlPdfConverter(config)
                                .convert("<p style='font-family:Web Serif'>WOFF ação</p>"))) {
            assertTrue(new PDFTextStripper().getText(pdf).contains("WOFF ação"));
        }
        assertThrows(IOException.class, () -> WoffDecoder.decode(woff, 100));
        assertThrows(
                IOException.class, () -> WoffDecoder.decode(Arrays.copyOf(woff, 30), 4_000_000));
    }

    @Test
    void woffRejectsOverlappingBlocksAndCorruptedChecksums() throws Exception {
        byte[] source =
                Files.readAllBytes(
                        Path.of(
                                "src/main/resources/dtm/pdfloom/fonts/liberation/LiberationSerif-Regular.ttf"));
        byte[] valid = encode(source);
        byte[] overlapping = valid.clone();
        ByteBuffer overlaps = ByteBuffer.wrap(overlapping);
        overlaps.putInt(68, overlaps.getInt(48));
        assertThrows(IOException.class, () -> WoffDecoder.decode(overlapping, 4_000_000));
        byte[] corrupted = valid.clone();
        ByteBuffer checksums = ByteBuffer.wrap(corrupted);
        checksums.putInt(60, checksums.getInt(60) ^ 1);
        assertThrows(IOException.class, () -> WoffDecoder.decode(corrupted, 4_000_000));
        byte[] optional = valid.clone();
        ByteBuffer metadata = ByteBuffer.wrap(optional);
        metadata.putInt(24, metadata.getInt(48));
        metadata.putInt(28, 4);
        metadata.putInt(32, 4);
        assertThrows(IOException.class, () -> WoffDecoder.decode(optional, 4_000_000));
        byte[] missing = valid.clone();
        ByteBuffer.wrap(missing).putInt(32, 4);
        assertThrows(IOException.class, () -> WoffDecoder.decode(missing, 4_000_000));
        assertArrayEquals(source, WoffDecoder.decode(source, 4_000_000));
    }

    private byte[] encode(byte[] source) {
        ByteBuffer input = ByteBuffer.wrap(source);
        int count = Short.toUnsignedInt(input.getShort(4));
        ByteBuffer output = ByteBuffer.allocate(source.length + 44 + count * 20);
        output.putInt(0x774f4646)
                .putInt(input.getInt(0))
                .putInt(0)
                .putShort((short) count)
                .putShort((short) 0)
                .putInt(0);
        int cursor = 44 + count * 20;
        int sfntSize = 12 + count * 16;
        for (int index = 0; index < count; index++) {
            int entry = 12 + index * 16;
            int offset = input.getInt(entry + 8);
            int length = input.getInt(entry + 12);
            byte[] table = Arrays.copyOfRange(source, offset, offset + length);
            Deflater deflater = new Deflater();
            deflater.setInput(table);
            deflater.finish();
            byte[] buffer = new byte[length + 100];
            int compressed = deflater.deflate(buffer);
            deflater.end();
            if (compressed < length) {
                table = Arrays.copyOf(buffer, compressed);
            }
            output.position(44 + index * 20);
            output.putInt(input.getInt(entry))
                    .putInt(cursor)
                    .putInt(table.length)
                    .putInt(length)
                    .putInt(input.getInt(entry + 4));
            output.position(cursor);
            output.put(table);
            cursor = (cursor + table.length + 3) & ~3;
            sfntSize += (length + 3) & ~3;
        }
        output.putInt(8, cursor);
        output.putInt(16, sfntSize);
        return Arrays.copyOf(output.array(), cursor);
    }
}
