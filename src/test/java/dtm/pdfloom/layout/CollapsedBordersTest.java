package dtm.pdfloom.layout;

import static org.junit.jupiter.api.Assertions.*;

import dtm.pdfloom.render.Paint;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

class CollapsedBordersTest {
    private List<Paint.Border> border(double width, String color, String style) {
        return Collections.nCopies(4, new Paint.Border(width, color, style));
    }

    private Paint.Border shared(CollapsedBorders borders) {
        return borders.paint(new double[] {0, 40, 80}, new double[] {0, 30}).stream()
                .filter(
                        command ->
                                command instanceof Paint.BorderLine line
                                        && line.x1() == 40
                                        && line.x2() == 40)
                .map(command -> ((Paint.BorderLine) command).border())
                .findFirst()
                .orElse(null);
    }

    @Test
    void hiddenSuppressesEveryCompetingBorderIncludingWiderCellBorders() {
        CollapsedBorders borders = new CollapsedBorders();
        borders.add(0, 0, 1, 1, border(0, "red", "hidden"), 0);
        borders.add(0, 1, 1, 1, border(8, "blue", "double"), 5);
        assertNull(shared(borders));
    }

    @Test
    void widthStyleAndOriginResolveInThatOrder() {
        CollapsedBorders widths = new CollapsedBorders();
        widths.add(0, 0, 1, 1, border(4, "red", "solid"), 0);
        widths.add(0, 1, 1, 1, border(3, "blue", "double"), 5);
        assertEquals("red", shared(widths).color());
        CollapsedBorders styles = new CollapsedBorders();
        styles.add(0, 0, 1, 1, border(3, "red", "solid"), 5);
        styles.add(0, 1, 1, 1, border(3, "blue", "double"), 0);
        assertEquals("blue", shared(styles).color());
        CollapsedBorders origins = new CollapsedBorders();
        origins.add(0, 0, 1, 1, border(3, "red", "solid"), 4);
        origins.add(0, 1, 1, 1, border(3, "blue", "solid"), 5);
        assertEquals("blue", shared(origins).color());
    }

    @Test
    void directionBreaksColorTiesByPositionRatherThanInsertionOrder() {
        for (boolean rtl : new boolean[] {false, true}) {
            CollapsedBorders borders = new CollapsedBorders(rtl);
            borders.add(0, 1, 1, 1, border(2, "blue", "solid"), 5);
            borders.add(0, 0, 1, 1, border(2, "red", "solid"), 5);
            assertEquals(rtl ? "blue" : "red", shared(borders).color());
        }
    }

    @Test
    void collapsedInsetAndOutsetUseRidgeAndGroove() {
        CollapsedBorders inset = new CollapsedBorders();
        inset.add(0, 0, 1, 1, border(4, "red", "inset"), 5);
        assertEquals("ridge", shared(inset).style());
        CollapsedBorders outset = new CollapsedBorders();
        outset.add(0, 0, 1, 1, border(4, "red", "outset"), 5);
        assertEquals("groove", shared(outset).style());
    }
}
