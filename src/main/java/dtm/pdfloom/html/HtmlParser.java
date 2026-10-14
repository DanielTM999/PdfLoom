package dtm.pdfloom.html;

import dtm.pdfloom.config.PdfConversionConfig;
import dtm.pdfloom.exception.PdfConversionException;

import org.jsoup.Jsoup;
import org.jsoup.nodes.*;

import java.util.*;

public final class HtmlParser {
    public record Document(HtmlNode root, HtmlNode body, String title, List<HtmlNode> elements) {
    }

    private record Frame(Node source, HtmlNode target, int depth) {
    }

    public Document parse(String html, PdfConversionConfig config) {
        org.jsoup.nodes.Document doc = Jsoup.parse(html);
        HtmlNode root = new HtmlNode(doc, null), body = root;
        List<HtmlNode> elements = new ArrayList<>();
        Deque<Frame> stack = new ArrayDeque<>();
        stack.push(new Frame(doc, root, 0));
        int count = 0;
        while (!stack.isEmpty()) {
            Frame f = stack.pop();
            if (++count > config.maxElements || f.depth > config.maxDepth) {
                throw new PdfConversionException("HTML element/depth limit exceeded");
            }
            if (f.target.tag.equals("body")) {
                body = f.target;
            }
            if (!f.target.tag.equals("#text")) {
                elements.add(f.target);
            }
            List<Frame> pending = new ArrayList<>();
            for (Node n : f.source.childNodes()) {
                HtmlNode child;
                if (n instanceof Element e) {
                    child = new HtmlNode(e, null);
                } else if (n instanceof TextNode t) {
                    child = new HtmlNode(null, t.getWholeText());
                } else if (n instanceof DataNode d) {
                    child = new HtmlNode(null, d.getWholeData());
                } else {
                    continue;
                }
                child.parent = f.target;
                f.target.children.add(child);
                pending.add(new Frame(n, child, f.depth + 1));
            }
            for (int i = pending.size() - 1; i >= 0; i--) {
                stack.push(pending.get(i));
            }
        }
        return new Document(root, body, doc.title(), List.copyOf(elements));
    }
}
