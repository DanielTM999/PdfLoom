package dtm.pdfloom;

import dtm.pdfloom.config.PdfConversionConfig;
import dtm.pdfloom.css.CascadeStyleResolver;
import dtm.pdfloom.font.TextMetrics;
import dtm.pdfloom.html.HtmlParser;
import dtm.pdfloom.impl.HtmlPdfConverter;
import dtm.pdfloom.internal.pdfbox.PdfFontRegistry;
import dtm.pdfloom.resource.ResourceLoader;
import dtm.pdfloom.style.ComputedStyle;
import dtm.pdfloom.style.StyleSheet;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StandardFontsTest {
    @TempDir
    Path tempDir;

    @Test
    void writesNativeCourierBoldWithItsExactWidthsAndSearchableText() throws Exception {
        PdfConversionConfig config = PdfConversionConfig.builder()
                .standardFont("Document Mono", 700, false, "Courier-Bold").build();
        try (PDDocument pdf = Loader.loadPDF(new HtmlPdfConverter(config).convert(
                "<p style='font: bold 25pt Document Mono'>ABC1D23</p>"))) {
            assertTrue(new PDFTextStripper().getText(pdf).contains("ABC1D23"));
            PDResources resources = pdf.getPage(0).getResources();
            boolean found = false;
            for (COSName name : resources.getFontNames()) {
                PDFont font = resources.getFont(name);
                if (font.getName().equals("Courier-Bold")) {
                    found = true;
                    assertInstanceOf(PDType1Font.class, font);
                    assertFalse(font.isEmbedded());
                    assertEquals(105, font.getStringWidth("ABC1D23") / 1000 * 25, .001);
                }
            }
            assertTrue(found);
        }
    }

    @ParameterizedTest
    @EnumSource(Standard14Fonts.FontName.class)
    void registersEveryStandardFaceUsingTheSameApi(Standard14Fonts.FontName name) throws Exception {
        PdfConversionConfig config = PdfConversionConfig.builder()
                .standardFont("Registered", 400, false, name.getName()).build();
        try (PDDocument pdf = new PDDocument(); ResourceLoader loader = new ResourceLoader(config)) {
            StyleSheet sheet = new CascadeStyleResolver(config, loader)
                    .resolve(new HtmlParser().parse("<p>X</p>", config), null);
            PdfFontRegistry fonts = new PdfFontRegistry(pdf, config, loader, sheet, null);
            TextMetrics.Font selected = fonts.font(new ComputedStyle(Map.of("font-family", "Registered", "font-size", "25pt")));
            assertEquals(name.getName(), fonts.pdfFont(selected.key()).getName());
            assertInstanceOf(PDType1Font.class, fonts.pdfFont(selected.key()));
            assertTrue(selected.ascent() > 0);
            assertTrue(selected.descent() >= 0);
        }
    }

    @Test
    void keepsExternalFontsConfigurableWithoutChangingTheLibrary() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("external fonts"));
        Path font = root.resolve("custom-bold.ttf");
        Files.copy(Path.of("src/main/resources/dtm/pdfloom/fonts/liberation/LiberationSerif-Bold.ttf"), font);
        PdfConversionConfig config = PdfConversionConfig.builder()
                .baseDirectory(root)
                .font("User Font", 700, false, "custom-bold.ttf")
                .standardFont("PDF Mono", 700, false, "Courier-Bold").build();
        try (PDDocument pdf = Loader.loadPDF(new HtmlPdfConverter(config).convert(
                "<p style='font:bold 22pt User Font'>External typeface</p>"
                        + "<p style='font:bold 22pt PDF Mono'>Native typeface</p>"))) {
            String text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains("External typeface"));
            assertTrue(text.contains("Native typeface"));
            PDResources resources = pdf.getPage(0).getResources();
            boolean external = false, nativeFont = false;
            for (COSName name : resources.getFontNames()) {
                PDFont selected = resources.getFont(name);
                if (selected.getName().contains("LiberationSerif-Bold")) {
                    external = true;
                    assertTrue(selected.isEmbedded());
                }
                nativeFont |= selected.getName().equals("Courier-Bold");
            }
            assertTrue(external);
            assertTrue(nativeFont);
        }
    }

    @Test
    void fallsBackForUnicodeThatCannotBeEncodedInNativeCourier() throws Exception {
        PdfConversionConfig config = PdfConversionConfig.builder()
                .standardFont("PDF Mono", 700, false, "Courier-Bold").build();
        try (PDDocument pdf = Loader.loadPDF(new HtmlPdfConverter(config).convert(
                "<p style='font:bold 22pt PDF Mono'>ASCII ação Ω кириллица</p>"))) {
            String text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains("ASCII ação Ω кириллица"), text);
            assertFalse(text.contains("\uFFFD"));
        }
    }

    @Test
    void rendersNativeFontShadowsWithoutChangingTheTextFont() throws Exception {
        PdfConversionConfig config = PdfConversionConfig.builder()
                .standardFont("PDF Mono", 700, false, "Courier-Bold").build();
        try (PDDocument pdf = Loader.loadPDF(new HtmlPdfConverter(config).convert(
                "<p style='font:bold 25pt PDF Mono;text-shadow:2px 2px 1px red'>ABC1D23</p>"))) {
            assertTrue(new PDFTextStripper().getText(pdf).contains("ABC1D23"));
            assertNotNull(new PDFRenderer(pdf).renderImageWithDPI(0, 72));
        }
    }

    @Test
    void rejectsUnknownNativeFontNames() {
        PdfConversionConfig config = PdfConversionConfig.builder()
                .standardFont("Unknown", 700, false, "Unknown-Bold").build();
        assertThrows(dtm.pdfloom.exception.PdfConversionException.class,
                () -> new HtmlPdfConverter(config).convert("<p>Text</p>"));
    }

    @Test
    void preservesNativeTypefaceInGeneratedPageCounters() throws Exception {
        PdfConversionConfig config = PdfConversionConfig.builder()
                .standardFont("PDF Mono", 700, false, "Courier-Bold").build();
        try (PDDocument pdf = Loader.loadPDF(new HtmlPdfConverter(config).convert(
                "<style>body{font-family:'PDF Mono';font-weight:bold}.page:before{content:counter(page)}"
                        + ".total:before{content:counter(pages)}</style>"
                        + "<p>Page <span class='page'></span> of <span class='total'></span></p>"))) {
            assertTrue(new PDFTextStripper().getText(pdf).contains("Page 1 of 1"));
            PDResources resources = pdf.getPage(0).getResources();
            for (COSName name : resources.getFontNames()) {
                assertEquals("Courier-Bold", resources.getFont(name).getName());
            }
        }
    }

    @Test
    void preservesExistingCourierSubstitutionWhenNotExplicitlyRegistered() throws Exception {
        PdfConversionConfig config = PdfConversionConfig.builder().build();
        try (PDDocument pdf = new PDDocument(); ResourceLoader loader = new ResourceLoader(config)) {
            StyleSheet sheet = new CascadeStyleResolver(config, loader)
                    .resolve(new HtmlParser().parse("<p>X</p>", config), null);
            PdfFontRegistry fonts = new PdfFontRegistry(pdf, config, loader, sheet, null);
            TextMetrics.Font selected = fonts.font(new ComputedStyle(
                    Map.of("font-family", "Courier", "font-weight", "700", "font-size", "25pt")));
            assertTrue(fonts.pdfFont(selected.key()).getName().contains("LiberationMono-Bold"));
        }
    }
}
