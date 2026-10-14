package dtm.pdfloom.layout;

import java.util.ArrayList;
import java.util.List;

final class LineBreaks {
    private LineBreaks() {
    }

    static List<String> segments(String word) {
        List<String> segments = new ArrayList<>();
        int start = 0;
        for (int index = 1; index < word.length() - 1; index++) {
            char current = word.charAt(index);
            if ((current == '-' || current == '‐' || current == '–')
                    && Character.isLetterOrDigit(word.charAt(index - 1))
                    && Character.isLetter(word.charAt(index + 1))) {
                segments.add(word.substring(start, index + 1));
                start = index + 1;
            }
        }
        segments.add(word.substring(start));
        return segments;
    }
}
