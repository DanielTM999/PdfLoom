package dtm.pdfloom.pagination;

import dtm.pdfloom.config.*;
import dtm.pdfloom.exception.PdfConversionException;
import dtm.pdfloom.layout.LayoutResult;
import dtm.pdfloom.page.*;
import dtm.pdfloom.render.Paint;
import dtm.pdfloom.style.StyleSheet;

import java.util.*;

public final class FragmentPaginator implements Paginator {
    private final PdfConversionConfig config;
    private final StyleSheet css;
    private final PageSpec fragmentSpec;
    private static final double SLICE_EXTENT = 100000;

    public FragmentPaginator(PdfConversionConfig c, StyleSheet css) {
        this(c, css, null);
    }

    public FragmentPaginator(PdfConversionConfig c, StyleSheet css, PageSpec fragmentSpec) {
        config = c;
        this.css = css;
        this.fragmentSpec = fragmentSpec;
    }

    @Override
    public List<RenderPage> paginate(LayoutResult layout) {
        List<LayoutResult.Interval> textInk = new ArrayList<>();
        for (Paint p : layout.commands) {
            inkBounds(p, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, textInk);
        }
        List<LayoutResult.Interval> ink = new ArrayList<>(layout.lines);
        ink.addAll(textInk);
        for (LayoutResult.Interval line : ink) {
            layout.height = Math.max(layout.height, line.end());
        }
        layout.breaks.add(layout.height);
        List<RenderPage> pages = new ArrayList<>();
        Set<Double> appliedParity = new HashSet<>();
        double start = 0;
        String name = "";
        while (start < layout.height - .001) {
            if (pages.size() >= config.maxPages) {
                throw new PdfConversionException("Maximum page count exceeded");
            }
            int number = pages.size() + 1;
            double contentStart = start;
            Double leading = layout.forced.higher(start + .001);
            while (leading != null
                    && leading < layout.height - .001
                    && !hasContent(layout.commands, start, leading)) {
                contentStart = leading;
                leading = layout.forced.higher(leading + .001);
            }
            for (LayoutResult.PageSwitch n : layout.pageNames) {
                if (n.at() <= contentStart + .001) {
                    name = n.name();
                }
            }
            PageSpec spec =
                    fragmentSpec == null
                            ? PageSpec.resolve(config, css, number, name, false)
                            : fragmentSpec;
            String parity = "";
            for (LayoutResult.BreakParity b : layout.parity) {
                if (Math.abs(b.at() - start) < .001) {
                    parity = b.side();
                }
            }
            if (!parity.isEmpty()
                    && appliedParity.add(start)
                    && (parity.equals("left") != (number % 2 == 0))) {
                PageSpec blank = PageSpec.resolve(config, css, number, name, true);
                pages.add(
                        new RenderPage(
                                blank,
                                number,
                                start,
                                start,
                                List.of(),
                                running(layout.running, start, start),
                                strings(layout.strings, start, start)));
                continue;
            }
            List<Paint> commands = new ArrayList<>();
            double repeated = 0;
            for (LayoutResult.TableRepeat t : layout.tables) {
                if (start > t.start() + t.headerHeight() + .001 && start < t.end() - .001) {
                    if (t.headerHeight() >= spec.contentHeight()) {
                        throw new PdfConversionException(
                                "Repeated table header exceeds content height");
                    }
                    for (Paint p : t.header()) {
                        commands.add(p.move(spec.left(), spec.top()));
                    }
                    repeated = Math.max(repeated, t.headerHeight());
                }
            }
            double footerReserve = 0;
            for (LayoutResult.TableRepeat t : layout.tables) {
                if (t.start() < start + spec.contentHeight()
                        && t.end() > start + spec.contentHeight() - repeated) {
                    footerReserve = Math.max(footerReserve, t.footerHeight());
                }
            }
            double available = spec.contentHeight() - repeated - footerReserve;
            if (available <= 0) {
                throw new PdfConversionException("Table header/footer leaves no printable area");
            }
            double limit = Math.min(layout.height, start + available);
            Double forced = layout.forced.higher(contentStart + .001);
            boolean forcedEnd = forced != null && forced <= limit + .001;
            if (forcedEnd) {
                limit = forced;
            }
            double end = limit;
            if (end < layout.height - .001 && !forcedEnd) {
                Double candidate = layout.breaks.floor(limit + .001);
                while (candidate != null && candidate > start + .001) {
                    double c = candidate;
                    boolean forbidden =
                            ink.stream().anyMatch(l -> c > l.start() + .001 && c < l.end() - .001)
                                    || layout.avoid.stream()
                                            .anyMatch(
                                                    i ->
                                                            i.end() - i.start() <= available + .001
                                                                    && c > i.start() + .001
                                                                    && c < i.end() - .001);
                    if (!forbidden) {
                        end = c;
                        break;
                    }
                    candidate = layout.breaks.lower(candidate - .001);
                }
                if (candidate == null || candidate <= start + .001) {
                    LayoutResult.Interval crossing = null;
                    for (LayoutResult.Interval l : layout.lines) {
                        if (limit > l.start() + .001 && limit < l.end() - .001) {
                            crossing = l;
                            break;
                        }
                    }
                    if (crossing != null) {
                        if (crossing.start() > start + .001) {
                            end = crossing.start();
                        } else {
                            end = limit;
                            for (LayoutResult.Interval line : textInk) {
                                if (line.start() < end - .001 && line.end() > end + .001) {
                                    if (line.start() <= start + .001) {
                                        throw new PdfConversionException(
                                                "Text glyph exceeds printable page height");
                                    }
                                    end = Math.min(end, line.start());
                                }
                            }
                        }
                    }
                }
            }
            if (end <= start + .001) {
                throw new PdfConversionException("Pagination cannot advance at " + start);
            }
            List<Paint> body = new ArrayList<>();
            double sliceStart = start <= .001 ? Double.NEGATIVE_INFINITY : start;
            double sliceEnd = end >= layout.height - .001 ? Double.POSITIVE_INFINITY : end;
            for (Paint p : layout.commands) {
                Paint fragment =
                        fragment(
                                p,
                                start,
                                end,
                                Double.NEGATIVE_INFINITY,
                                Double.POSITIVE_INFINITY,
                                sliceStart,
                                sliceEnd);
                if (fragment != null) {
                    body.add(fragment.move(spec.left(), spec.top() + repeated - start));
                }
            }
            if (repeated > 0 || footerReserve > 0) {
                commands.add(
                        new Paint.Group(
                                0,
                                spec.top() + repeated,
                                spec.width(),
                                spec.contentHeight() - repeated - footerReserve,
                                body,
                                true,
                                "none",
                                "50% 50%"));
            } else {
                commands.addAll(body);
            }
            for (Paint p : layout.fixed) {
                commands.add(p.move(spec.left(), spec.top()));
            }
            if (footerReserve > 0) {
                for (LayoutResult.TableRepeat t : layout.tables) {
                    if (t.start() < end && end < t.end() - t.footerHeight() - .001) {
                        for (Paint p : t.footer()) {
                            commands.add(
                                    p.move(
                                            spec.left(),
                                            spec.top() + spec.contentHeight() - t.footerHeight()));
                        }
                    }
                }
            }
            Map<String, LayoutResult.Running> running = running(layout.running, start, end);
            Map<String, String> strings = strings(layout.strings, start, end);
            pages.add(new RenderPage(spec, number, start, end, commands, running, strings));
            start = end;
        }
        if (pages.isEmpty()) {
            pages.add(
                    new RenderPage(
                            PageSpec.resolve(config, css, 1, "", false),
                            1,
                            0,
                            0,
                            layout.fixed,
                            Map.of(),
                            Map.of()));
        }
        return pages;
    }

    private Map<String, LayoutResult.Running> running(
            List<LayoutResult.Running> values, double start, double end) {
        Map<String, LayoutResult.Running> previous = new LinkedHashMap<>();
        Map<String, List<LayoutResult.Running>> current = new LinkedHashMap<>();
        for (LayoutResult.Running value : values) {
            if (value.at() < start - .001) {
                previous.put(value.name(), value);
            } else if (value.at() < end - .001) {
                current.computeIfAbsent(value.name(), key -> new ArrayList<>()).add(value);
            }
        }
        Set<String> names = new LinkedHashSet<>(previous.keySet());
        names.addAll(current.keySet());
        Map<String, LayoutResult.Running> result = new LinkedHashMap<>();
        for (String name : names) {
            List<LayoutResult.Running> assignments = current.getOrDefault(name, List.of());
            LayoutResult.Running prior = previous.get(name);
            LayoutResult.Running first = assignments.isEmpty() ? prior : assignments.get(0);
            LayoutResult.Running last =
                    assignments.isEmpty() ? prior : assignments.get(assignments.size() - 1);
            LayoutResult.Running initial =
                    !assignments.isEmpty() && assignments.get(0).at() <= start + .001
                            ? first
                            : prior;
            if (first != null) {
                result.put(name, first);
                result.put(name + ":first", first);
            }
            if (last != null) {
                result.put(name + ":last", last);
            }
            if (initial != null) {
                result.put(name + ":start", initial);
            }
            if (assignments.isEmpty() && prior != null) {
                result.put(name + ":first-except", prior);
            }
        }
        return result;
    }

    private Map<String, String> strings(
            List<LayoutResult.NamedString> values, double start, double end) {
        Map<String, String> previous = new LinkedHashMap<>();
        Map<String, List<LayoutResult.NamedString>> current = new LinkedHashMap<>();
        for (LayoutResult.NamedString value : values) {
            if (value.at() < start - .001) {
                previous.put(value.name(), value.value());
            } else if (value.at() < end - .001) {
                current.computeIfAbsent(value.name(), key -> new ArrayList<>()).add(value);
            }
        }
        Set<String> names = new LinkedHashSet<>(previous.keySet());
        names.addAll(current.keySet());
        Map<String, String> result = new LinkedHashMap<>();
        for (String name : names) {
            List<LayoutResult.NamedString> assignments = current.getOrDefault(name, List.of());
            String prior = previous.getOrDefault(name, "");
            String first = assignments.isEmpty() ? prior : assignments.get(0).value();
            String last =
                    assignments.isEmpty() ? prior : assignments.get(assignments.size() - 1).value();
            String initial =
                    !assignments.isEmpty() && assignments.get(0).at() <= start + .001
                            ? first
                            : prior;
            result.put(name, first);
            result.put(name + ":first", first);
            result.put(name + ":last", last);
            result.put(name + ":start", initial);
            result.put(name + ":first-except", assignments.isEmpty() ? prior : "");
        }
        return result;
    }

    private boolean hasContent(List<Paint> commands, double from, double to) {
        for (Paint paint : commands) {
            if (paint instanceof Paint.Layer layer) {
                if (hasContent(layer.commands(), from, to)) {
                    return true;
                }
            } else if (paint instanceof Paint.Group group) {
                if (hasContent(group.commands(), from, to)) {
                    return true;
                }
            } else if (paint instanceof Paint.Destination) {
                continue;
            } else if (paint.y() >= from - .001 && paint.y() < to - .001) {
                if (paint instanceof Paint.Text text && text.value().isBlank()) {
                    continue;
                }
                if (paint instanceof Paint.Rectangle rectangle && !visible(rectangle)) {
                    continue;
                }
                if (paint instanceof Paint.Rectangle && paint.y() + paint.height() > to + .001) {
                    continue;
                }
                return true;
            }
        }
        return false;
    }

    private boolean visible(Paint.Rectangle rectangle) {
        boolean filled =
                rectangle.fill() != null
                        && !rectangle.fill().isBlank()
                        && !rectangle.fill().equals("transparent")
                        && !rectangle.fill().equals("none");
        boolean bordered =
                rectangle.borders().stream()
                        .anyMatch(
                                border ->
                                        border.width() > 0
                                                && !Set.of("none", "hidden").contains(border.style()));
        boolean shadow = rectangle.shadow() != null && !rectangle.shadow().equals("none");
        return filled || bordered || shadow;
    }

    private void inkBounds(
            Paint p, double clipStart, double clipEnd, List<LayoutResult.Interval> ink) {
        if (p instanceof Paint.Layer layer) {
            for (Paint child : layer.commands()) {
                inkBounds(child, clipStart, clipEnd, ink);
            }
        } else if (p instanceof Paint.Group group) {
            double start = group.clip() ? Math.max(clipStart, group.y()) : clipStart,
                    end = group.clip() ? Math.min(clipEnd, group.y() + group.height()) : clipEnd;
            for (Paint child : group.commands()) {
                inkBounds(child, start, end, ink);
            }
        } else if (p instanceof Paint.Text) {
            double start = Math.max(clipStart, p.y()), end = Math.min(clipEnd, p.y() + p.height());
            if (end > start) {
                ink.add(new LayoutResult.Interval(start, end));
            }
        }
    }

    private Paint fragment(
            Paint paint,
            double start,
            double end,
            double clipStart,
            double clipEnd,
            double sliceStart,
            double sliceEnd) {
        if (paint instanceof Paint.Layer layer) {
            List<Paint> commands =
                    fragmentChildren(
                            layer.commands(), start, end, clipStart, clipEnd, sliceStart, sliceEnd);
            if (commands.isEmpty()) {
                return null;
            }
            return new Paint.Layer(
                    layer.x(),
                    layer.y(),
                    layer.width(),
                    layer.height(),
                    commands,
                    layer.order(),
                    layer.context(),
                    layer.opacity());
        }
        if (paint instanceof Paint.Group group) {
            double lower = group.clip() ? Math.max(clipStart, group.y()) : clipStart;
            double upper = group.clip() ? Math.min(clipEnd, group.y() + group.height()) : clipEnd;
            List<Paint> commands =
                    fragmentChildren(
                            group.commands(), start, end, lower, upper, sliceStart, sliceEnd);
            if (commands.isEmpty()) {
                return null;
            }
            return new Paint.Group(
                    group.x(),
                    group.y(),
                    group.width(),
                    group.height(),
                    commands,
                    group.clip(),
                    group.transform(),
                    group.transformOrigin());
        }
        if (paint instanceof Paint.Destination) {
            return paint.y() >= start - .001 && paint.y() < end - .001 ? paint : null;
        }
        if (paint instanceof Paint.BorderLine line && Math.abs(line.y1() - line.y2()) < .001) {
            double lineY = line.y1();
            boolean below = start <= .001 ? lineY < start - .001 : lineY <= start + .001;
            if (below
                    || lineY > end + .001
                    || lineY < clipStart - .001
                    || lineY > clipEnd + .001) {
                return null;
            }
            return paint;
        }
        double lower = Math.max(paint.y(), clipStart);
        double upper = Math.min(paint.y() + paint.height(), clipEnd);
        if (upper <= start + .001 || lower >= end - .001 || upper <= lower) {
            return null;
        }
        if (paint instanceof Paint.Text text && (lower < start - .01 || upper > end + .01)) {
            double center = paint.y() + paint.height() / 2;
            if (center < start || center >= end) {
                return null;
            }
            double outside = Math.max(0, start - lower) + Math.max(0, upper - end);
            if (outside > paint.height() * .3) {
                throw new PdfConversionException(
                        "Pagination would cut text '" + text.value() + "' at " + end);
            }
            return paint;
        }
        double top = paint.y();
        double bottom = paint.y() + paint.height();
        if (paint instanceof Paint.BorderLine line) {
            top = Math.min(line.y1(), line.y2());
            bottom = Math.max(line.y1(), line.y2());
        }
        if (bottom - top > .001 && (top < sliceStart - .001 || bottom > sliceEnd + .001)) {
            double clipTop = Math.max(top, sliceStart);
            double clipBottom = Math.min(bottom, sliceEnd);
            return new Paint.Group(
                    -SLICE_EXTENT,
                    clipTop,
                    2 * SLICE_EXTENT,
                    clipBottom - clipTop,
                    List.of(paint),
                    true,
                    "none",
                    "50% 50%");
        }
        return paint;
    }

    private List<Paint> fragmentChildren(
            List<Paint> source,
            double start,
            double end,
            double clipStart,
            double clipEnd,
            double sliceStart,
            double sliceEnd) {
        List<Paint> commands = new ArrayList<>();
        for (Paint paint : source) {
            Paint fragment =
                    fragment(paint, start, end, clipStart, clipEnd, sliceStart, sliceEnd);
            if (fragment != null) {
                commands.add(fragment);
            }
        }
        return commands;
    }
}
