package dtm.pdfloom.image;

import dtm.pdfloom.exception.PdfConversionException;
import dtm.pdfloom.resource.ResourceLoader;

import org.apache.batik.bridge.*;
import org.apache.batik.transcoder.*;
import org.apache.batik.transcoder.image.PNGTranscoder;
import org.apache.batik.util.ParsedURL;
import org.w3c.dom.*;

import java.io.*;
import java.net.URI;
import java.util.*;
import java.util.regex.*;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

final class SvgRasterizer {
    private SvgRasterizer() {
    }

    static byte[] rasterize(
            byte[] source,
            URI origin,
            ResourceLoader loader,
            List<dtm.pdfloom.font.FontData> fonts) {
        try {
            SvgFontResolver fontResolver = new SvgFontResolver(fonts);
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(
                    new org.xml.sax.helpers.DefaultHandler() {
                        @Override
                        public void fatalError(org.xml.sax.SAXParseException e)
                                throws org.xml.sax.SAXException {
                            throw e;
                        }
                    });
            Document dom = builder.parse(new ByteArrayInputStream(source));
            Element root = dom.getDocumentElement();
            if (!"svg".equals(root.getLocalName())) {
                throw new IOException("Not an SVG document");
            }
            Deque<Node> stack = new ArrayDeque<>();
            stack.push(dom);
            int elements = 0;
            while (!stack.isEmpty()) {
                Node node = stack.pop();
                if (++elements > 100_000) {
                    throw new IOException("SVG node limit exceeded");
                }
                if (node.getNodeType() == Node.PROCESSING_INSTRUCTION_NODE) {
                    throw new IOException("SVG processing instructions forbidden");
                }
                if (node instanceof Element e) {
                    String tag = e.getLocalName();
                    if (Set.of(
                                    "script",
                                    "foreignObject",
                                    "animate",
                                    "animateMotion",
                                    "animateTransform",
                                    "set")
                            .contains(tag)) {
                        throw new IOException("Dynamic SVG forbidden: " + tag);
                    }
                    NamedNodeMap attrs = e.getAttributes();
                    for (int i = 0; i < attrs.getLength(); i++) {
                        Node attr = attrs.item(i);
                        String name = attr.getNodeName();
                        if (name.toLowerCase(Locale.ROOT).startsWith("on")) {
                            throw new IOException("SVG event handlers forbidden");
                        }
                        String v = attr.getNodeValue();
                        if (name.equals("href") || name.equals("xlink:href")) {
                            if (!v.startsWith("#") && !v.isBlank()) {
                                attr.setNodeValue(inline(v, origin, loader));
                            }
                        } else if (v.contains("url(")) {
                            attr.setNodeValue(rewriteUrls(v, origin, loader));
                        }
                    }
                    if (tag.equals("style")) {
                        String text = e.getTextContent();
                        if (text.toLowerCase(Locale.ROOT).contains("@import")) {
                            throw new IOException("SVG stylesheet imports unsupported");
                        }
                        e.setTextContent(rewriteUrls(text, origin, loader));
                    }
                }
                for (Node child = node.getLastChild();
                        child != null;
                        child = child.getPreviousSibling()) {
                    stack.push(child);
                }
            }
            PNGTranscoder transcoder =
                    new PNGTranscoder() {
                        @Override
                        protected UserAgent createUserAgent() {
                            return new SVGAbstractTranscoderUserAgent() {
                                @Override
                                public FontFamilyResolver getFontFamilyResolver() {
                                    return fontResolver;
                                }

                                @Override
                                public ScriptSecurity getScriptSecurity(
                                        String type, ParsedURL script, ParsedURL doc) {
                                    return new NoLoadScriptSecurity(type);
                                }

                                @Override
                                public ExternalResourceSecurity getExternalResourceSecurity(
                                        ParsedURL resource, ParsedURL doc) {
                                    return () -> {
                                        if (!"data".equals(resource.getProtocol())) {
                                            throw new SecurityException(
                                                    "SVG direct external access denied");
                                        }
                                    };
                                }

                                @Override
                                public void displayError(Exception error) {
                                    throw new PdfConversionException("SVG rendering failed", error);
                                }

                                @Override
                                public void displayError(String error) {
                                    throw new PdfConversionException(
                                            "SVG rendering failed: " + error);
                                }
                            };
                        }
                    };
            transcoder.addTranscodingHint(PNGTranscoder.KEY_EXECUTE_ONLOAD, false);
            transcoder.addTranscodingHint(PNGTranscoder.KEY_MAX_WIDTH, 1600f);
            transcoder.addTranscodingHint(PNGTranscoder.KEY_MAX_HEIGHT, 2000f);
            transcoder.addTranscodingHint(PNGTranscoder.KEY_DEFAULT_FONT_FAMILY, "sans-serif");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            transcoder.transcode(new TranscoderInput(dom), new TranscoderOutput(out));
            return out.toByteArray();
        } catch (PdfConversionException e) {
            throw e;
        } catch (Exception e) {
            throw new PdfConversionException("Static SVG parsing/rendering failed", e);
        }
    }

    private static String inline(String ref, URI origin, ResourceLoader loader) throws IOException {
        if (ref.startsWith("data:")) {
            loader.load(ref, origin);
            return ref;
        }
        ResourceLoader.Data data = loader.load(ref, origin);
        if (data == null) {
            throw new IOException("Missing SVG resource");
        }
        String type = data.mediaType() == null ? "image/png" : data.mediaType();
        return "data:" + type + ";base64," + Base64.getEncoder().encodeToString(data.bytes());
    }

    private static String rewriteUrls(String s, URI origin, ResourceLoader loader)
            throws IOException {
        Matcher m = Pattern.compile("url\\(\\s*(['\"]?)(.*?)\\1\\s*\\)").matcher(s);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String ref = m.group(2);
            m.appendReplacement(
                    out,
                    Matcher.quoteReplacement(
                            "url(\""
                                    + (ref.startsWith("#") ? ref : inline(ref, origin, loader))
                                    + "\")"));
        }
        m.appendTail(out);
        return out.toString();
    }
}
