package dtm.pdfloom.render;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class StackingOrder {
    private StackingOrder() {
    }

    public static List<Paint> resolve(List<Paint> commands) {
        List<Paint> normal = new ArrayList<>();
        List<Paint.Layer> positioned = new ArrayList<>();
        collect(commands, normal, positioned);
        positioned.sort(Comparator.comparingInt(Paint.Layer::order));
        List<Paint> result = new ArrayList<>();
        if (!normal.isEmpty() && background(normal.get(0))) {
            result.add(normal.remove(0));
        }
        positioned.stream().filter(layer -> layer.order() < 0).forEach(result::add);
        result.addAll(normal);
        positioned.stream().filter(layer -> layer.order() >= 0).forEach(result::add);
        return List.copyOf(result);
    }

    private static boolean background(Paint command) {
        if (command instanceof Paint.Rectangle) {
            return true;
        }
        return command instanceof Paint.Group group
                && group.clip()
                && group.commands().size() == 1
                && group.commands().get(0) instanceof Paint.Rectangle;
    }

    private static void collect(
            List<Paint> commands, List<Paint> normal, List<Paint.Layer> positioned) {
        for (Paint command : commands) {
            if (command instanceof Paint.Layer layer && !layer.context()) {
                List<Paint> local = new ArrayList<>();
                collect(layer.commands(), local, positioned);
                positioned.add(
                        new Paint.Layer(
                                layer.x(),
                                layer.y(),
                                layer.width(),
                                layer.height(),
                                local,
                                layer.order(),
                                true,
                                layer.opacity()));
            } else if (command instanceof Paint.Layer layer) {
                positioned.add(layer);
            } else {
                normal.add(command);
            }
        }
    }
}
