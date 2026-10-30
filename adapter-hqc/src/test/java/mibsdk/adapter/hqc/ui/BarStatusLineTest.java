package mibsdk.adapter.hqc.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The built-in bar's row geometry (a shared right-aligned icon row, or one full-width text row). */
class BarStatusLineTest {

    @Test
    void oneIconEndsFlushWithTheRightEdge() {
        assertEquals(6, BarStatusLine.iconRowCapacity(), "200 / 32");
        assertEquals(170, BarStatusLine.iconX(0, 1), "30 px icon, right edge at 200");
    }

    @Test
    void iconsRunLeftToRightInPriorityOrder() {
        assertEquals(138, BarStatusLine.iconX(0, 2), "highest priority is leftmost");
        assertEquals(170, BarStatusLine.iconX(1, 2));
    }

    @Test
    void aFullIconRowHoldsSixAndLowerPriorityIconsOverflow() {
        assertEquals(0, BarStatusLine.iconPageCount(0));
        assertEquals(1, BarStatusLine.iconPageCount(6));
        assertEquals(2, BarStatusLine.iconPageCount(7));
        assertEquals(170, BarStatusLine.iconX(0, 1), "the 7th icon is alone on the second row");
    }

    @Test
    void textRowsUseTheWholeWidth() {
        assertEquals(34, BarStatusLine.textLabelX());
        assertEquals(166, BarStatusLine.textLabelW());
    }

    @Test
    void rowsFollowTheGlobalSourceOrder() {
        List<BarStatusLine.RowSpec> rows = BarStatusLine.arrange(
                Arrays.asList(1, 3), Arrays.asList(0, 2));

        assertEquals(3, rows.size());
        assertEquals(BarStatusLine.RowSpec.ICONS, rows.get(0).kind, "the icons at cell 0 and 2 share a row");
        assertEquals(0, rows.get(0).iconStart);
        assertEquals(2, rows.get(0).iconCount);
        assertEquals(BarStatusLine.RowSpec.TEXT, rows.get(1).kind);
        assertEquals(0, rows.get(1).textIndex);
        assertEquals(BarStatusLine.RowSpec.TEXT, rows.get(2).kind);
        assertEquals(1, rows.get(2).textIndex);
    }

    @Test
    void overflowPagesKeepTheHighestPriorityIconsFirst() {
        List<BarStatusLine.RowSpec> rows = BarStatusLine.arrange(
                Arrays.asList(5),
                Arrays.asList(0, 1, 2, 3, 4, 5, 6));

        assertEquals(3, rows.size());
        assertEquals(BarStatusLine.RowSpec.ICONS, rows.get(0).kind);
        assertEquals(0, rows.get(0).iconStart, "cells 0..5 stay on the first icon row");
        assertEquals(6, rows.get(0).iconCount);
        assertEquals(BarStatusLine.RowSpec.TEXT, rows.get(1).kind, "rank 5 sits between the pages");
        assertEquals(BarStatusLine.RowSpec.ICONS, rows.get(2).kind);
        assertEquals(6, rows.get(2).iconStart, "the 7th icon overflows");
        assertEquals(1, rows.get(2).iconCount);
    }

    @Test
    void ringPositionsWrapAndApplyThePageOffset() {
        assertEquals(0, BarStatusLine.ringY(2, 2, 3));
        assertEquals(30, BarStatusLine.ringY(0, 2, 3));
        assertEquals(60, BarStatusLine.ringY(1, 2, 3));
    }

    @Test
    void theViewportIsReanchoredToTheRightGroup() {
        assertEquals(62, BarStatusLine.viewportX(262));
        assertEquals(0, BarStatusLine.viewportX(200));
        assertEquals(0, BarStatusLine.viewportX(150), "a shrunk group never puts the viewport left of it");
    }

    @Test
    void theViewportAnchorsToTheStockIconsWhenTheyAreVisible() {
        assertEquals(62, BarStatusLine.anchoredViewportX(262, 262));
        assertEquals(50, BarStatusLine.anchoredViewportX(250, 262), "the stock icon's right edge wins");
        assertEquals(62, BarStatusLine.anchoredViewportX(-1, 262), "no stock icon: group edge");

        assertEquals(0, BarStatusLine.anchoredViewportY(0), "the native icon cell's top");
        assertEquals(3, BarStatusLine.anchoredViewportY(3), "follow the stock icon if it moves");
        assertEquals(0, BarStatusLine.anchoredViewportY(-1), "no stock icon: keep the native top, do not jump");
    }

    @Test
    void theStockPageDoesNotShiftTheVisibleCustomRow() {
        assertEquals(3, BarStatusLine.pageAfterStockChange(2, false, true), "stock appears above the current row");
        assertEquals(2, BarStatusLine.pageAfterStockChange(3, true, false), "stock disappears; stay on the same row");
        assertEquals(0, BarStatusLine.pageAfterStockChange(0, true, false), "the stock page itself falls back to row 0");
        assertEquals(1, BarStatusLine.pageAfterStockChange(0, false, true));
        assertEquals(2, BarStatusLine.pageAfterStockChange(2, true, true), "no change");
    }
}
