package dtm.pdfloom.layout;

import dtm.pdfloom.render.Paint;

import java.util.*;

public final class LayoutResult {
    public record Interval(double start, double end) {
    }

    public record TableRepeat(
            double start,
            double end,
            double headerHeight,
            List<Paint> header,
            double footerHeight,
            List<Paint> footer) {
            }

    public record PageSwitch(double at, String name) {
    }

    public record Running(
            String name,
            double at,
            double height,
            List<Paint> commands,
            dtm.pdfloom.html.HtmlNode source) {
        public Running(String name, double at, double height, List<Paint> commands) {
            this(name, at, height, commands, null);
        }
    }

    public record NamedString(String name, double at, String value) {
    }

    public record BreakParity(double at, String side) {
    }

    public final List<Paint> commands = new ArrayList<>(), fixed = new ArrayList<>();
    public final NavigableSet<Double> breaks = new TreeSet<>(), forced = new TreeSet<>();
    public final List<Interval> avoid = new ArrayList<>(), lines = new ArrayList<>();
    public final List<TableRepeat> tables = new ArrayList<>();
    public final List<PageSwitch> pageNames = new ArrayList<>();
    public final List<Running> running = new ArrayList<>();
    public final List<NamedString> strings = new ArrayList<>();
    public final List<BreakParity> parity = new ArrayList<>();
    public LayoutBox root;
    public double height;

    public void append(LayoutResult child, double dx, double dy) {
        child.commands.forEach(p -> commands.add(p.move(dx, dy)));
        child.fixed.forEach(p -> fixed.add(p.move(dx, dy)));
        child.breaks.forEach(y -> breaks.add(y + dy));
        child.forced.forEach(y -> forced.add(y + dy));
        child.avoid.forEach(i -> avoid.add(new Interval(i.start + dy, i.end + dy)));
        child.lines.forEach(i -> lines.add(new Interval(i.start + dy, i.end + dy)));
        child.tables.forEach(
                t ->
                        tables.add(
                                new TableRepeat(
                                        t.start + dy,
                                        t.end + dy,
                                        t.headerHeight,
                                        t.header.stream().map(p -> p.move(dx, 0)).toList(),
                                        t.footerHeight,
                                        t.footer.stream().map(p -> p.move(dx, 0)).toList())));
        child.pageNames.forEach(n -> pageNames.add(new PageSwitch(n.at + dy, n.name)));
        child.running.forEach(
                n -> running.add(new Running(n.name, n.at + dy, n.height, n.commands, n.source)));
        child.strings.forEach(n -> strings.add(new NamedString(n.name, n.at + dy, n.value)));
        child.parity.forEach(n -> parity.add(new BreakParity(n.at + dy, n.side)));
    }
}
