package dtm.pdfloom.style;

import dtm.pdfloom.html.HtmlParser;

import java.net.URI;

public interface StyleResolver {
    StyleSheet resolve(HtmlParser.Document document, URI origin);
}
