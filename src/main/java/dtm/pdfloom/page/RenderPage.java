package dtm.pdfloom.page;

import dtm.pdfloom.render.Paint;

import java.util.*;

public record RenderPage(
        PageSpec spec,
        int number,
        double flowStart,
        double flowEnd,
        List<Paint> commands,
        Map<String, dtm.pdfloom.layout.LayoutResult.Running> running,
        Map<String, String> strings,
        Map<String, List<Paint>> marginContent) {
    public RenderPage(
            PageSpec spec,
            int number,
            double flowStart,
            double flowEnd,
            List<Paint> commands,
            Map<String, dtm.pdfloom.layout.LayoutResult.Running> running,
            Map<String, String> strings) {
        this(spec, number, flowStart, flowEnd, commands, running, strings, Map.of());
    }

    public RenderPage {
        commands = List.copyOf(commands);
        running = Map.copyOf(running);
        strings = Map.copyOf(strings);
        Map<String, List<Paint>> copied = new LinkedHashMap<>();
        marginContent.forEach((key, value) -> copied.put(key, List.copyOf(value)));
        marginContent = Map.copyOf(copied);
    }
}
