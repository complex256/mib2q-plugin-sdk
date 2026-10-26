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
import java.util.List;
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

    /**
     * Spike D: the kind column is the record-set column (it picks the row), column 3 greys a row. Bench B: the id
     * column is the entry's own row id (column 5), so the stock menu keeps the focus on the same entry when rows are
     * added or removed (it re-finds the focused row by that id).
     */
    @Test
    void listUsesRecordSetEnabledAndIdColumns() {
        HostWidgets.init();
        ListController list = PageWidgets.list(PageLayout.EMPTY);
        assertEquals(5, list.getIdColumn());
        assertEquals(2, list.getRecordSetColumn());
        assertEquals(3, list.getEnabledColumn());
        assertEquals(HqcUiIds.PAGE_LIST_MODEL, list.getModelID());

        ListCell[] c = PageWidgets.cells(new PageRow("Wi-Fi", 1, PageScreens.ROW_TOGGLE, false, null, 42));
        assertEquals(6, c.length);
        assertEquals(42, ((IntegerListCell) c[5]).getValue());
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

    /** F14: the bank's page and confirm ListModels have the 6 columns the rows write (bench B: + the row id). */
    @Test
    void pageListModelHas6Columns() {
        boolean was = SdkGlobals.uiEnabled;
        try {
            SdkGlobals.uiEnabled = true;
            new SdkCarModelBank();
            assertEquals(6, SdkCarModelBank.pageList().getMaxColumns());
            assertEquals(6, SdkCarModelBank.confirmList().getMaxColumns());
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

    /** The Car fonts the page takes, cached (as once the Car bundle loaded them): the host has no font loader. */
    static void carFonts() throws Exception {
        java.lang.reflect.Field arrays = de.audi.tghu.car.hmi.evohigh.FontFactory.class.getDeclaredField("fontArrays");
        arrays.setAccessible(true);
        Object[] a = (Object[]) arrays.get(null);
        for (int i = 0; i < a.length; i++) {
            if (a[i] == null) {
                a[i] = new de.esolutions.hmi.widgets.audi.base.eal.IWrappedFont[0];
            }
        }
    }

    /**
     * §7.2: the typed rows follow the list in the menu, in spec order: the combo row (type 64) on slot 0's
     * ChoiceModel with its option text ids and internal id 609960, the rotary row (type 128) on slot 1's RangeModel
     * with its own key handler and internal id 609961. The screen's views are sorted by id.
     */
    @Test
    void typedRowsFollowTheList() throws Exception {
        HostWidgets.init();
        carFonts();
        UiPage p = new UiPage("p/main", "Main", Arrays.asList(
                new UiEntry(UiEntry.DROPDOWN, "mode", "Mode", null, false, null)
                        .withOptions(new String[] {"Off", "Low", "High"}),
                new UiEntry(UiEntry.ITEM, "go", "Go", null, false, null),
                new UiEntry(UiEntry.SLIDER, "bass", "Bass", null, false, null).withRange(-6, 6, 1, true)));
        SdkCarScreenFactory f = new SdkCarScreenFactory(SdkCarScreenFactoryTest.fw());
        de.audi.atip.hmi.view.Screen screen = PageWidgets.build(f, 0, null, "p/main",
                PageLayout.of(p, ItemIcons.NONE), PageScreens.NO_FOCUS);
        de.esolutions.hmi.widgets.audi.base.AbstractScreenWidget sw =
                (de.esolutions.hmi.widgets.audi.base.AbstractScreenWidget) screen;
        assertEquals(HqcUiIds.PAGE_SCREEN_ID, sw.getID());
        int[] ids = sw.getViewIDs();
        int[] sorted = ids.clone();
        Arrays.sort(sorted);
        assertArrayEquals(sorted, ids, "findViews binary-searches them");
        assertArrayEquals(new int[] {138, HqcUiIds.PAGE_LIST_MODEL, 602764, 602769}, ids);

        ListController list = (ListController) sw.findViews(HqcUiIds.PAGE_LIST_MODEL)[0];
        MenuItemController combo = (MenuItemController) sw.findViews(602764)[0];
        MenuItemController rotary = (MenuItemController) sw.findViews(602769)[0];
        List<?> menu = ((de.esolutions.hmi.widgets.audi.evo.widgets.menu.MenuController) list.getParent())
                .getChildren();
        int at = menu.indexOf(list);
        assertSame(combo, menu.get(at + 1), "the combo row right after the list");
        assertSame(rotary, menu.get(at + 2), "then the rotary row");

        assertEquals(64, combo.getType());
        assertEquals(HqcUiIds.TYPED_CHOICE_MODEL_0, combo.getModelID());
        assertArrayEquals(new int[] {609041, 609042, 609043}, combo.getTextIds());
        assertEquals(609040, combo.getLabelId());
        assertEquals(609960, combo.getInternalID());
        assertEquals(44, combo.getBitmapIndices().length);
        assertEquals(332, combo.getBitmapIndices()[43]);

        assertEquals(128, rotary.getType());
        assertEquals(HqcUiIds.TYPED_RANGE_MODEL_0 + 1, rotary.getModelID());
        assertEquals(609049, rotary.getLabelId());
        assertEquals(609961, rotary.getInternalID());
        assertTrue(rotary.getKeyHandler() instanceof PageWidgets.SliderKeys, "its own key handler");
        assertEquals(1, ((PageWidgets.SliderKeys) rotary.getKeyHandler()).slot);
    }

    /** D6: the slider screen 609501 on slot 1's RangeModel, titled with its label, its views sorted. */
    @Test
    void sliderScreenOnTheSlotsRangeModel() throws Exception {
        HostWidgets.init();
        carFonts();
        SdkCarScreenFactory f = new SdkCarScreenFactory(SdkCarScreenFactoryTest.fw());
        SliderScreen.Screen s = SliderScreen.build(f, 0, 1, PageLayout.EMPTY, null);
        assertEquals(HqcUiIds.SLIDER_SCREEN_ID, s.getID());
        assertArrayEquals(new int[] {138, HqcUiIds.TYPED_RANGE_MODEL_0 + 1}, s.getViewIDs());
        assertTrue(s.findViews(HqcUiIds.TYPED_RANGE_MODEL_0 + 1)[0] instanceof SliderScreen.Knob);
        assertEquals(3, s.getSmallStageType());
    }

    /** Connectivity's font sets, as {@link #carFonts} (the speller's field and band use them). */
    static void connectivityFonts() throws Exception {
        java.lang.reflect.Field arrays = de.audi.tghu.connectivity.hmi.evohigh.FontFactory.class
                .getDeclaredField("fontArrays");
        arrays.setAccessible(true);
        Object[] a = (Object[]) arrays.get(null);
        for (int i = 0; i < a.length; i++) {
            if (a[i] == null) {
                a[i] = new de.esolutions.hmi.widgets.audi.base.eal.IWrappedFont[0];
            }
        }
    }

    /** The first widget of class {@code c} in {@code w}'s tree, depth first; null if none. */
    static <T> T find(de.esolutions.hmi.widgets.audi.base.AbstractWidget w, Class<T> c) {
        if (c.isInstance(w)) {
            return c.cast(w);
        }
        List<?> kids = w.getChildren();
        for (int i = 0; kids != null && i < kids.size(); i++) {
            if (kids.get(i) instanceof de.esolutions.hmi.widgets.audi.base.AbstractWidget) {
                T t = find((de.esolutions.hmi.widgets.audi.base.AbstractWidget) kids.get(i), c);
                if (t != null) {
                    return t;
                }
            }
        }
        return null;
    }

    /**
     * D7 + F9: the confirm screen 609502 (small stage 1) on the Yes/No list; its hint is text id 609080 in a
     * multi-line label that wraps ({@code autoWrap} 0: the renderer has no getter on the unit's jar, so the field is
     * read).
     */
    @Test
    void confirmLabelAutoWrapIsZero() throws Exception {
        HostWidgets.init();
        carFonts();
        SdkCarScreenFactory f = new SdkCarScreenFactory(SdkCarScreenFactoryTest.fw());
        de.esolutions.hmi.widgets.audi.evo.ScreenWidgetEVO s = ConfirmScreen.build(f, 0, null);
        assertEquals(HqcUiIds.CONFIRM_SCREEN_ID, s.getID());
        assertEquals(1, s.getSmallStageType());
        assertArrayEquals(new int[] {138, HqcUiIds.CONFIRM_LIST_MODEL}, s.getViewIDs());
        assertTrue(s.findViews(HqcUiIds.CONFIRM_LIST_MODEL)[0] instanceof ListController);
        de.esolutions.hmi.widgets.audi.evo.InstructionTextContoller it =
                find(s, de.esolutions.hmi.widgets.audi.evo.InstructionTextContoller.class);
        LabelController hint = it.getHintText();
        assertArrayEquals(new int[] {609080}, hint.getTextIds());
        java.lang.reflect.Field wrap = de.esolutions.hmi.widgets.audi.evo.high.widgets.MultiLineLabelRendererHigh.class
                .getDeclaredField("autoWrap");
        wrap.setAccessible(true);
        assertEquals(0, wrap.getInt(hint.getRenderer()));
    }

    /** The confirm's rows: Yes then No, ACTION rows, enabled. */
    @Test
    void confirmRowsAreYesThenNo() {
        PageRow[] r = ConfirmScreen.rows(new mibsdk.platform.UiDialog(3, mibsdk.platform.UiDialog.CONFIRM, "p/main",
                "Reset?", "Sure?", "Do it", "Keep", 0));
        assertEquals(2, r.length);
        assertEquals("Do it", r[0].label);
        assertEquals("Keep", r[1].label);
        for (int i = 0; i < 2; i++) {
            assertEquals(PageScreens.ROW_ACTION, r[i].kind);
            assertTrue(r[i].enabled);
            assertEquals(1, ((IntegerListCell) PageWidgets.cells(r[i])[HqcUiIds.PAGE_COL_ENABLED]).getValue());
        }
    }

    /** D9: the speller screen 609503 builds on the host (Connectivity's fonts); the model gets max length and text. */
    @Test
    void spellerScreenBuildsOnHost() throws Exception {
        HostWidgets.init();
        carFonts();
        connectivityFonts();
        SdkCarScreenFactory f = new SdkCarScreenFactory(SdkCarScreenFactoryTest.fw());
        de.audi.atip.hmi.model.SpellerModel m = new de.audi.atip.hmi.model.SpellerModel(HqcUiIds.SPELLER_MODEL);
        de.esolutions.hmi.widgets.audi.evo.ScreenWidgetEVO s = SpellerScreen.build(f, 0, m,
                new mibsdk.platform.UiDialog(4, mibsdk.platform.UiDialog.SPELLER, "p/main", "Name", "Bob", null, null,
                        20), null);
        assertEquals(HqcUiIds.SPELLER_SCREEN_ID, s.getID());
        assertArrayEquals(new int[] {138, 5602, 5606, HqcUiIds.SPELLER_MODEL}, s.getViewIDs());
        assertTrue(s.findViews(HqcUiIds.SPELLER_MODEL)[0]
                instanceof de.esolutions.hmi.widgets.audi.evo.widgets.TouchController);
        assertEquals("Bob", m.getText());
        assertEquals(20, m.getMaxLength());
    }

    // ---- focus (bench findings B and C) ----------------------------------------------------------------------------

    /** A page ListModel that records the row operations a write makes (the stock menu sees each as one event). */
    static final class RecordingList extends de.audi.atip.hmi.model.ListModel {
        final List<String> ops = new java.util.ArrayList<String>();

        RecordingList() {
            super(HqcUiIds.PAGE_LIST_MODEL);
            setMaxColumns(HqcUiIds.PAGE_COLUMNS);
            setMaxRows(64);
        }

        public void setRow(int i, ListCell[] c) {
            ops.add("set " + i + " " + ((TextListCell) c[0]).getText());
            super.setRow(i, c);
        }

        public void insertRow(int i, ListCell[] c) {
            ops.add("insert " + i + " " + ((TextListCell) c[0]).getText());
            super.insertRow(i, c);
        }

        public void addRow(ListCell[] c) {
            ops.add("add " + ((TextListCell) c[0]).getText());
            super.addRow(c);
        }

        public void removeRow(int i) {
            ops.add("remove " + i);
            super.removeRow(i);
        }

        List<String> labels() {
            List<String> l = new java.util.ArrayList<String>();
            for (int i = 0; i < getLength(); i++) {
                l.add(getRow(i).getText(HqcUiIds.PAGE_COL_LABEL));
            }
            return l;
        }

        List<String> take() {
            List<String> l = new java.util.ArrayList<String>(ops);
            ops.clear();
            return l;
        }
    }

    private static PageRow[] rows(String... keys) {
        PageRow[] r = new PageRow[keys.length];
        for (int i = 0; i < keys.length; i++) {
            r[i] = PageRow.of(new UiEntry(UiEntry.ITEM, keys[i], keys[i].toUpperCase(), null, false, null), -1);
        }
        return r;
    }

    /** Each entry's row id is its key's, never negative; equal content compares equal whatever the id. */
    @Test
    void rowIdIsTheEntryKeys() {
        PageRow a = rows("a")[0];
        assertEquals(PageRow.rowId("a"), a.id);
        assertTrue(PageRow.rowId("a") != PageRow.rowId("b"));
        for (String k : new String[] {"", "a", "lang", "polygenelubricants", "\u0000"}) {
            assertTrue(PageRow.rowId(k) >= 0, k);
        }
        assertEquals(new PageRow("A", 0, PageScreens.ROW_ACTION, true, null), a, "the drawn content");
    }

    /**
     * Bench B (in-place refresh): a write keeps each kept entry in its own row, so the stock menu's focus (by row id)
     * stays on it: a removed entry's row is removed, a new entry's row is inserted where it goes, the rest rewritten
     * in place. A page that shares no entry with the rows there (another page) is written in place, as before.
     */
    @Test
    void writeKeepsEachEntrysRowWhenRowsAreAddedOrRemoved() {
        RecordingList m = new RecordingList();
        PageWidgets.write(m, rows("a", "b", "c"));
        assertEquals(Arrays.asList("add A", "add B", "add C"), m.take());

        PageWidgets.write(m, rows("a", "c"));
        assertEquals(Arrays.asList("remove 1", "set 0 A", "set 1 C"), m.take(), "B's row goes");
        assertEquals(Arrays.asList("A", "C"), m.labels());

        PageWidgets.write(m, rows("a", "n", "c", "z"));
        assertEquals(Arrays.asList("set 0 A", "insert 1 N", "set 2 C", "add Z"), m.take(), "N goes in before C");
        assertEquals(Arrays.asList("A", "N", "C", "Z"), m.labels());

        PageWidgets.write(m, rows("x", "y"));
        assertEquals(Arrays.asList("set 0 X", "set 1 Y", "remove 3", "remove 2"), m.take(), "another page: in place");
        assertEquals(Arrays.asList("X", "Y"), m.labels());

        PageWidgets.write(m, rows("y", "x", "w"));
        assertEquals(Arrays.asList("Y", "X", "W"), m.labels(), "a reorder ends right too");
        PageWidgets.write(m, new PageRow[0]);
        assertEquals(new java.util.ArrayList<String>(), m.labels());
    }

    /**
     * The page menu's focus codes (bench B, C): list row {@code r} is the list's item {@code r}; typed slot {@code s}
     * ({@link PageScreens#typedFocus}) is the {@code s}-th row after the list. The build's focus is the one set once
     * the menu connects.
     */
    @Test
    void pageMenuMapsFocusCodesToItsItems() throws Exception {
        HostWidgets.init();
        carFonts();
        UiPage p = new UiPage("p/main", "Main", Arrays.asList(
                new UiEntry(UiEntry.DROPDOWN, "mode", "Mode", null, false, null)
                        .withOptions(new String[] {"Off", "Low", "High"}),
                new UiEntry(UiEntry.ITEM, "go", "Go", null, false, null),
                new UiEntry(UiEntry.SLIDER, "bass", "Bass", null, false, null).withRange(-6, 6, 1, true)));
        SdkCarScreenFactory f = new SdkCarScreenFactory(SdkCarScreenFactoryTest.fw());
        de.audi.atip.hmi.view.Screen screen = PageWidgets.build(f, 0, null, "p/main",
                PageLayout.of(p, ItemIcons.NONE), PageScreens.typedFocus(1));
        de.esolutions.hmi.widgets.audi.base.AbstractScreenWidget sw =
                (de.esolutions.hmi.widgets.audi.base.AbstractScreenWidget) screen;
        ListController list = (ListController) sw.findViews(HqcUiIds.PAGE_LIST_MODEL)[0];
        PageWidgets.PageMenu menu = (PageWidgets.PageMenu) list.getParent();
        int at = menu.getChildren().indexOf(list);
        assertEquals(PageScreens.typedFocus(1), menu.focusOnConnect(), "the build's focus");

        de.esolutions.hmi.widgets.audi.evo.widgets.menu.MenuItemIndex i = menu.indexOf(3);
        assertEquals(at, i.widget);
        assertEquals(3, i.widgetPart);
        i = menu.indexOf(PageScreens.typedFocus(0));
        assertEquals(at + 1, i.widget, "the combo row");
        assertEquals(0, i.widgetPart);
        assertEquals(at + 2, menu.indexOf(PageScreens.typedFocus(1)).widget, "the rotary row");
        assertNull(menu.indexOf(PageScreens.typedFocus(2)), "only 2 typed rows");
        assertNull(menu.indexOf(PageScreens.NO_FOCUS));

        assertEquals(3, menu.codeOf(new de.esolutions.hmi.widgets.audi.evo.widgets.menu.MenuItemIndex(at, 3)));
        assertEquals(PageScreens.typedFocus(1),
                menu.codeOf(new de.esolutions.hmi.widgets.audi.evo.widgets.menu.MenuItemIndex(at + 2, 0)));
        assertEquals(PageScreens.NO_FOCUS, menu.codeOf(null));
        assertEquals(PageScreens.NO_FOCUS,
                menu.codeOf(new de.esolutions.hmi.widgets.audi.evo.widgets.menu.MenuItemIndex(at + 3, 0)));
        if (at > 0) {
            assertEquals(PageScreens.NO_FOCUS,
                    menu.codeOf(new de.esolutions.hmi.widgets.audi.evo.widgets.menu.MenuItemIndex(at - 1, 0)));
        }
        assertEquals(PageScreens.NO_FOCUS, new PageWidgets(null).focused(screen), "not connected: no focus");
        assertEquals(PageScreens.NO_FOCUS, new PageWidgets(null).focused("not a page"));
    }
}
