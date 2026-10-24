package mibsdk.adapter.hqc.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.audi.atip.hmi.view.ScreenData;
import de.esolutions.hmi.widgets.audi.base.widgets.AbstractWidgetController;
import de.esolutions.hmi.widgets.audi.evo.widgets.LabelController;
import de.esolutions.hmi.widgets.audi.evo.widgets.menu.MenuItemController;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The stock side of SDK pages that can run on the host: the list rows and the screen data PageWidgets shows. */
class PageWidgetsTest {

    @BeforeAll
    static void stockLogging() {
        de.audi.atip.hmi.model.AbstractModelBank.initLogging(name -> de.audi.atip.log.NullLogChannel.getInstance());
    }

    /** Every row kind is spike B's proven row: a list item (type 16) with one label cell on model column 0. */
    @Test
    void everyRowKindIsAListItemWithOneLabelCell() {
        int[] kinds = {PageScreens.ROW_ACTION, PageScreens.ROW_TOGGLE, PageScreens.ROW_LABEL};
        PageWidgets.Rows rows = new PageWidgets.Rows();
        for (int k : kinds) {
            AbstractWidgetController w = rows.createListItem(k, null);
            assertTrue(w instanceof MenuItemController, "kind " + k);
            MenuItemController item = (MenuItemController) w;
            assertEquals(HqcUiIds.LIST_ITEM_TYPE, item.getType(), "kind " + k);
            assertEquals(1, item.getChildren().size(), "kind " + k);
            Object cell = item.getChildren().get(0);
            assertTrue(cell instanceof LabelController, "kind " + k);
            assertEquals(0, ((LabelController) cell).getModelColumn(), "kind " + k);
        }
        assertNull(rows.createCell(1), "no checkbox or value cell");
    }

    /**
     * Fix round 1 (Important 2): returning to the stock screen keeps the partial popups on screen NOW (an OPS/PDC
     * popup that came up meanwhile stays), as copies; the saved data is not changed.
     */
    @Test
    void stockReturnTakesTheCurrentPartialPopups() {
        ScreenData saved = new ScreenData();
        int[] savedPopups = {62};
        saved.setPartialPopups(savedPopups);
        ScreenData current = new ScreenData();
        int[] now = {62, 2100037};
        current.setPartialPopups(now);

        ScreenData back = PageWidgets.returnData(saved, current);
        assertNotSame(saved, back);
        assertArrayEquals(new int[] {62, 2100037}, back.getPartialPopups());
        assertNotSame(now, back.getPartialPopups(), "a copy, not the current screen's array");
        assertSame(savedPopups, saved.getPartialPopups(), "the saved data is untouched");
        assertArrayEquals(new int[] {62}, saved.getPartialPopups());

        assertNull(PageWidgets.returnData(saved, null).getPartialPopups(), "no current screen: no popups");
    }

    @Test
    void popupCopiesAreClones() {
        int[] a = {1, 2};
        int[] b = PageWidgets.copy(a);
        assertArrayEquals(a, b);
        assertNotSame(a, b);
        assertNull(PageWidgets.copy(null));
    }
}
