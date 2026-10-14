package dtm.pdfloom.style;

import java.util.*;

public record ComputedStyle(Map<String, String> values) {
    public ComputedStyle {
        values = Map.copyOf(values);
    }

    public String get(String property) {
        return values.getOrDefault(property, "");
    }

    public String get(String property, String fallback) {
        return values.getOrDefault(property, fallback);
    }

    public boolean is(String key, String value) {
        return get(key).equalsIgnoreCase(value);
    }
}
