package dtm.pdfloom.css;

import java.util.List;

public record CssPriority(
        boolean important, boolean inline, List<Integer> layer, long specificity, int order)
        implements Comparable<CssPriority> {
    public CssPriority {
        layer = List.copyOf(layer);
    }

    @Override
    public int compareTo(CssPriority other) {
        int compared = Boolean.compare(important, other.important);
        if (compared == 0) {
            compared = Boolean.compare(inline, other.inline);
        }
        if (compared == 0) {
            int count = Math.min(layer.size(), other.layer.size());
            for (int index = 0; index < count && compared == 0; index++) {
                compared = Integer.compare(layer.get(index), other.layer.get(index));
            }
            if (compared == 0) {
                compared = Integer.compare(layer.size(), other.layer.size());
            }
            if (important) {
                compared = -compared;
            }
        }
        if (compared == 0) {
            compared = Long.compare(specificity, other.specificity);
        }
        return compared == 0 ? Integer.compare(order, other.order) : compared;
    }
}
