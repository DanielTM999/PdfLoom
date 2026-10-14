package dtm.pdfloom.layout;

import dtm.pdfloom.html.HtmlParser;

public interface LayoutEngine {
    LayoutResult layout(HtmlParser.Document document, double availableWidth);
}
