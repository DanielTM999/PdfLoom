package dtm.pdfloom.html;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class TableOfContents {
    private TableOfContents() {
    }

    public static String prepare(String html) {
        if (!html.contains("data-pdfloom-toc")) {
            return html;
        }
        Document document = Jsoup.parse(html);
        document.outputSettings().prettyPrint(false);
        List<Element> markers = document.select("[data-pdfloom-toc]");
        if (markers.isEmpty()) {
            return html;
        }
        List<Element> headings =
                document.select("h1,h2,h3,h4,h5,h6").stream()
                        .filter(heading -> heading.closest("[data-pdfloom-toc]") == null)
                        .toList();
        Set<String> ids = new HashSet<>();
        document.select("[id]").forEach(element -> ids.add(element.id()));
        int next = 1;
        for (Element heading : headings) {
            if (heading.id().isBlank()) {
                String id;
                do {
                    id = "pdfloom-heading-" + next++;
                } while (ids.contains(id));
                heading.attr("id", id);
                ids.add(id);
            }
        }
        for (Element marker : markers) {
            marker.empty();
            for (Element heading : headings) {
                Element entry =
                        marker.appendElement("div")
                                .attr("data-pdfloom-toc-entry", heading.tagName().substring(1));
                entry.appendElement("a").attr("href", "#" + heading.id()).text(heading.text());
                entry.appendElement("span")
                        .attr("data-pdfloom-toc-page", "")
                        .attr("data-target", "#" + heading.id());
            }
        }
        document.head()
                .prependElement("style")
                .text(
                        "[data-pdfloom-toc-entry]{display:flex;justify-content:space-between;break-inside:avoid}[data-pdfloom-toc-page]::after{content:target-counter(attr(data-target),page)}");
        return document.outerHtml();
    }
}
