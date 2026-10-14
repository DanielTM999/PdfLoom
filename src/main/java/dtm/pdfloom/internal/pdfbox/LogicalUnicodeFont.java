package dtm.pdfloom.internal.pdfbox;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

final class LogicalUnicodeFont {
    private final PDDocument document;
    private final PDType0Font font;
    private final COSDictionary dictionary;
    private final COSDictionary descendant;
    private final Map<Integer, Integer> codes = new LinkedHashMap<>();
    private final java.awt.Font metrics;

    LogicalUnicodeFont(PDDocument document, PDType0Font source, java.awt.Font metrics)
            throws IOException {
        this.document = document;
        this.metrics = metrics.deriveFont(1000f);
        dictionary = new COSDictionary(source.getCOSObject());
        descendant = new COSDictionary(source.getDescendantFont().getCOSObject());
        descendant.setItem(COSName.CID_TO_GID_MAP, COSName.IDENTITY);
        descendant.setInt(COSName.DW, 1000);
        COSArray descendants = new COSArray();
        descendants.add(descendant);
        dictionary.setItem(COSName.DESCENDANT_FONTS, descendants);
        dictionary.setItem(COSName.ENCODING, COSName.getPDFName("Identity-H"));
        update();
        font = new PDType0Font(dictionary);
    }

    PDType0Font font() {
        return font;
    }

    String encode(String text) throws IOException {
        StringBuilder hex = new StringBuilder();
        for (int codePoint : text.codePoints().toArray()) {
            Integer code = codes.get(codePoint);
            if (code == null) {
                if (codes.size() >= 65534) {
                    throw new IOException("Logical font character limit exceeded");
                }
                code = codes.size() + 1;
                codes.put(codePoint, code);
            }
            hex.append(String.format(Locale.ROOT, "%04X", code));
        }
        return hex.toString();
    }

    void update() throws IOException {
        StringBuilder cmap =
                new StringBuilder(
                        "/CIDInit /ProcSet findresource begin\n"
                            + "12 dict begin\n"
                            + "begincmap\n"
                            + "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >>"
                            + " def\n"
                            + "/CMapName /PdfLoomLogical def\n"
                            + "/CMapType 2 def\n"
                            + "1 begincodespacerange\n"
                            + "<0000> <FFFF>\n"
                            + "endcodespacerange\n");
        COSArray widths = new COSArray();
        int count = 0;
        for (Map.Entry<Integer, Integer> entry : codes.entrySet()) {
            if (count % 100 == 0) {
                if (count > 0) {
                    cmap.append("endbfchar\n");
                }
                cmap.append(Math.min(100, codes.size() - count)).append(" beginbfchar\n");
            }
            String value = new String(Character.toChars(entry.getKey()));
            String unicode =
                    java.util.HexFormat.of()
                            .withUpperCase()
                            .formatHex(value.getBytes(StandardCharsets.UTF_16BE));
            cmap.append(String.format(Locale.ROOT, "<%04X> <%s>\n", entry.getValue(), unicode));
            widths.add(COSInteger.get(entry.getValue()));
            COSArray width = new COSArray();
            width.add(
                    COSInteger.get(
                            Math.round(
                                    metrics.getStringBounds(
                                                    value,
                                                    new java.awt.font.FontRenderContext(
                                                            null, true, true))
                                            .getWidth())));
            widths.add(width);
            count++;
        }
        if (count > 0) {
            cmap.append("endbfchar\n");
        }
        cmap.append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n");
        PDStream stream = new PDStream(document);
        try (OutputStream output = stream.createOutputStream(COSName.FLATE_DECODE)) {
            output.write(cmap.toString().getBytes(StandardCharsets.US_ASCII));
        }
        dictionary.setItem(COSName.TO_UNICODE, stream);
        descendant.setItem(COSName.W, widths);
    }
}
