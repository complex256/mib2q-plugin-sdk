package mibsdk.adapter.hqc.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.audi.atip.hmi.model.IntegerListCell;
import de.audi.atip.hmi.model.ListCell;
import de.audi.atip.hmi.model.TextListCell;
import de.audi.atip.hmi.view.ScreenData;
import de.esolutions.hmi.widgets.audi.base.widgets.AbstractWidgetController;
import de.esolutions.hmi.widgets.audi.evo.gridlayout.AxisConstraints;
import de.esolutions.hmi.widgets.audi.evo.gridlayout.GridLayout;
import de.esolutions.hmi.widgets.audi.evo.widgets.CheckboxController;
import de.esolutions.hmi.widgets.audi.evo.widgets.IconController;
import de.esolutions.hmi.widgets.audi.evo.widgets.LabelController;
import de.esolutions.hmi.widgets.audi.evo.widgets.ProgressBarController;
import de.esolutions.hmi.widgets.audi.evo.widgets.RadioButtonController;
import de.esolutions.hmi.widgets.audi.evo.widgets.menu.ListController;
import de.esolutions.hmi.widgets.audi.evo.widgets.menu.MenuItemController;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import mibsdk.adapter.hqc.SdkGlobals;
import mibsdk.platform.UiEntry;
import mibsdk.platform.UiIcon;
import mibsdk.platform.UiPage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The stock side of SDK pages that can run on the host: the list rows and the screen data PageWidgets shows. */
class PageWidgetsTest {

    @BeforeAll
    static void stockLogging() {
        de.audi.atip.hmi.model.AbstractModelBank.initLogging(name -> de.audi.atip.log.NullLogChannel.getInstance());
    }

    private static final UiIcon WIFI = UiIcon.stock("wifi");

    private static PageLayout layoutWith(int id) {
        Map<UiIcon, Integer> m = new HashMap<UiIcon, Integer>();
        m.put(WIFI, id);
        UiPage p = new UiPage("p/main", "Main", Arrays.asList(
                new UiEntry(UiEntry.ITEM, "w", "Wi-Fi", null, false, null).withIcon(WIFI)));
        return PageLayout.of(p, new ItemIcons(m));
    }

    private static MenuItemController row(PageWidgets.Rows rows, int kind) {
        AbstractWidgetController w = rows.createListItem(kind, null);
        assertTrue(w instanceof MenuItemController, "kind " + kind);
        MenuItemController item = (MenuItemController) w;
        assertEquals(HqcUiIds.LIST_ITEM_TYPE, item.getType(), "kind " + kind);
        assertEquals(1, item.getLayoutChoices().length, "one grid layout, kind " + kind);
        Object first = item.getChildren().get(0);
        assertTrue(first instanceof LabelController, "the label first, kind " + kind);
        assertEquals(0, ((LabelController) first).getModelColumn(), "kind " + kind);
        return item;
    }

    private static AxisConstraints columns(MenuItemController item) {
        return (AxisConstraints) ((GridLayout) item.getLayoutChoices()[0]).getColumnConstraints();
    }

    /** ACTION and LABEL rows are spike B's proven row: a list item (type 16) with one label cell on model column 0. */
    @Test
    void actionAndLabelRowsAreOneLabelCell() {
        HostWidgets.init();
        PageWidgets.Rows rows = new PageWidgets.Rows(PageLayout.EMPTY);
        for (int k : new int[] {PageScreens.ROW_ACTION, PageScreens.ROW_LABEL}) {
            assertEquals(1, row(rows, k).getChildren().size(), "kind " + k);
        }
        assertEquals(HqcUiIds.PAGE_COL_VALUE_TEXT, ((LabelController) rows.createCell(4)).getModelColumn());
        assertNull(rows.createCell(1), "column 1's cell depends on the row kind");
    }

    /** Spike D: the kind column is also the record-set column (it picks the row), column 3 greys a row. */
    @Test
    void listUsesRecordSetAndEnabledColumns() {
        HostWidgets.init();
        ListController list = PageWidgets.list(PageLayout.EMPTY);
        assertEquals(2, list.getIdColumn());
        assertEquals(2, list.getRecordSetColumn());
        assertEquals(3, list.getEnabledColumn());
        assertEquals(HqcUiIds.PAGE_LIST_MODEL, list.getModelID());

        ListCell[] c = PageWidgets.cells(new PageRow("Wi-Fi", 1, PageScreens.ROW_TOGGLE, false, null));
        assertEquals(5, c.length);
        assertEquals("Wi-Fi", ((TextListCell) c[0]).getText());
        assertEquals(1, ((IntegerListCell) c[1]).getValue());
        assertEquals(PageScreens.ROW_TOGGLE, ((IntegerListCell) c[2]).getValue());
        assertEquals(0, ((IntegerListCell) c[3]).getValue(), "disabled");
        assertEquals("", ((TextListCell) c[4]).getText(), "no value text");
        assertEquals("on >", ((TextListCell) PageWidgets.cells(
                new PageRow("Wi-Fi", 0, PageScreens.ROW_VALUE, true, "on >"))[4]).getText());
        assertEquals(1, ((IntegerListCell) PageWidgets.cells(
                new PageRow("Wi-Fi", 0, PageScreens.ROW_VALUE, true, "on >"))[3]).getValue());
    }

    /** F14: the bank's page ListModel has the 5 columns the rows write. */
    @Test
    void pageListModelHas5Columns() {
        boolean was = SdkGlobals.uiEnabled;
        try {
            SdkGlobals.uiEnabled = true;
            new SdkCarModelBank();
            assertEquals(5, SdkCarModelBank.pageList().getMaxColumns());
        } finally {
            SdkGlobals.uiEnabled = was;
        }
    }

    @Test
    void toggleRowHasCheckboxCellOnColumn1() {
        HostWidgets.init();
        MenuItemController item = row(new PageWidgets.Rows(PageLayout.EMPTY), PageScreens.ROW_TOGGLE);
        assertEquals(2, item.getChildren().size());
        CheckboxController cb = (CheckboxController) item.getChildren().get(1);
        assertEquals(1, cb.getModelColumn());
        assertArrayEquals(new int[][] {{0}, {1}}, cb.getMapping());
        AxisConstraints c = columns(item);
        assertEquals(2, c.length);
        assertArrayEquals(new int[] {0, 22, 0}, c.gaps);
        assertArrayEquals(new float[] {1.0f, 0.0f}, c.grow);
        assertArrayEquals(new float[] {1.0f, 0.0f}, c.shrink);
        assertArrayEquals(new int[] {5}, ((AxisConstraints) ((GridLayout) item.getLayoutChoices()[0])
                .getRowConstraints()).alignment);
    }

    @Test
    void eachKindBuildsItsCells() {
        HostWidgets.init();
        PageWidgets.Rows rows = new PageWidgets.Rows(layoutWith(609123));

        MenuItemController radio = row(rows, PageScreens.ROW_RADIO);
        RadioButtonController rb = (RadioButtonController) radio.getChildren().get(1);
        assertEquals(1, rb.getModelColumn());
        assertArrayEquals(new int[] {-1, 33}, columns(radio).max);
        assertArrayEquals(new int[] {0, 22, 0}, columns(radio).gaps);

        MenuItemController progress = row(rows, PageScreens.ROW_PROGRESS);
        ProgressBarController pb = (ProgressBarController) progress.getChildren().get(1);
        assertEquals(1, pb.getModelColumn());
        assertEquals(0, pb.getMinimumModelValue());
        assertEquals(100, pb.getMaximumModelValue());
        assertArrayEquals(new int[] {-1, 300}, columns(progress).min);

        MenuItemController value = row(rows, PageScreens.ROW_VALUE);
        assertEquals(4, ((LabelController) value.getChildren().get(1)).getModelColumn());
        assertArrayEquals(new float[] {1.0f, 0.0f}, columns(value).grow);

        MenuItemController icon = row(rows, PageScreens.ROW_ICON);
        IconController ic = (IconController) icon.getChildren().get(1);
        assertEquals(1, ic.getModelColumn());
        assertEquals(609123, ic.getBitmap(0), "the layout's icon ids");
        assertArrayEquals(new int[] {0, 15, 0}, columns(icon).gaps);
        assertArrayEquals(new float[] {0.0f, 1.0f}, columns(icon).grow);
        assertArrayEquals(new int[] {30, -1}, columns(icon).max);
        GridLayout g = (GridLayout) icon.getLayoutChoices()[0];
        assertSame(ic, g.getCells()[0], "the icon column first");

        MenuItemController both = row(rows, PageScreens.ROW_ICON_VALUE);
        assertEquals(3, both.getChildren().size());
        AxisConstraints c = columns(both);
        assertEquals(3, c.length);
        assertArrayEquals(new int[] {0, 15, 22, 0}, c.gaps);
        assertArrayEquals(new float[] {0.0f, 1.0f, 0.0f}, c.grow);
        assertArrayEquals(new int[] {30, -1, -1}, c.max);
        Object[] cells = ((GridLayout) both.getLayoutChoices()[0]).getCells();
        assertTrue(cells[0] instanceof IconController);
        assertEquals(1, ((IconController) cells[0]).getModelColumn());
        assertEquals(0, ((LabelController) cells[1]).getModelColumn());
        assertEquals(4, ((LabelController) cells[2]).getModelColumn());
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
