package dtm.pdfloom.layout;

import dtm.pdfloom.html.HtmlNode;
import dtm.pdfloom.style.ComputedStyle;

import java.util.*;

public final class LayoutBox {
    public final HtmlNode node;
    public final ComputedStyle style;
    public final List<LayoutBox> children = new ArrayList<>();
    public double x, y, width, height;
    public double naturalHeight;

    public LayoutBox(HtmlNode node, ComputedStyle style) {
        this.node = node;
        this.style = style;
    }
}
