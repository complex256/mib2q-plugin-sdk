package mibsdk.adapter.hqc.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.esolutions.hmi.widgets.audi.evo.high.widgets.IconRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.LabelRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.widgets.IconController;
import de.esolutions.hmi.widgets.audi.evo.widgets.LabelController;
import de.esolutions.hmi.widgets.audi.evo.widgets.PartialPopupController;
import de.esolutions.hmi.widgets.audi.evo.widgets.menu.MenuItemController;
import java.util.ArrayList;
import java.util.List;
import mibsdk.platform.UiIcon;
import mibsdk.platform.UiRow;
import mibsdk.platform.UiToast;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Fix round 1: a row that loses its icon goes back to exactly a never-set row's bitmaps (the S1f state). */
class HmiWidgetsTest {

    @AfterEach
    void reset() {
        SdkCarScreenFactory.sdkRows = null;
        SdkToastPopup.current = null;
    }

    @org.junit.jupiter.api.io.TempDir
    java.io.File iconDir;

    private int repaints;

    private static IconController icon() {
        IconController c = new IconController();
        c.setRenderer(new IconRendererHigh(c));
        c.setCompositesDirty(false); // as after the first paint
        return c;
    }

    /** F5: a toast's built-in icon is set by id, marked dirty and repainted by the one HMI job. */
    @Test
    void toastIdIconAppliedInJob() throws Exception {
        HostWidgets.init();
        LabelController label = new LabelController();
        label.setRenderer(new LabelRendererHigh(label));
        IconController id = icon();
        IconController file = icon();
        PartialPopupController p = new PartialPopupController(HqcUiIds.POPUP_SDK_TOAST) {
            public void triggerRepaint() {
                repaints++;
            }
        };
        p.add(id);
        p.add(file);
        p.add(label);
        SdkToastPopup.current = new SdkToastPopup(label, id, file, p);
        IconIds ids = new IconIds(HqcUiIds.ICON_ID_FIRST, HqcUiIds.ICON_ID_LAST);
        SdkToastPopup.Part part = SdkToastPopup.Part.of(new UiToast(1, "Done", UiIcon.stock("dot")), ids,
                StatusLayoutTest.builtins(iconDir));
        HmiWidgets w = new HmiWidgets();
        w.apply(HmiWidgetState.EMPTY.withToast(part));
        assertArrayEquals(new int[] {HqcUiIds.ICON_ID_FIRST}, id.getBitmapIndices());
        assertTrue(id.getRenderer().areCompositesDirty(), "the new icon is redrawn");
        assertEquals(part.iconX(), id.getX());
        assertEquals(part.iconX(), file.getX());
        assertEquals(1, repaints, "one repaint in the job");

        w.apply(HmiWidgetState.EMPTY.withToast(part)); // nothing changed
        assertEquals(1, repaints);
        w.apply(HmiWidgetState.EMPTY.withToast(part.hidden())); // the hide is a model write: no widget work
        assertEquals(1, repaints);
        assertArrayEquals(new int[] {HqcUiIds.ICON_ID_FIRST}, id.getBitmapIndices());
    }

    private static MenuItemController[] drawer() {
        MenuItemController[] rows = new MenuItemController[HqcUiIds.ROW_SLOTS];
        for (int i = 0; i < rows.length; i++) {
            rows[i] = new MenuItemController();
        }
        SdkCarScreenFactory.sdkRows = rows;
        return rows;
    }

    private static HmiWidgetState state(DrawerAssignment a) {
        int[][] b = new int[HqcUiIds.ROW_SLOTS][];
        for (int s = 0; s < b.length; s++) {
            b[s] = a.iconAt(s);
        }
        return new HmiWidgetState(b);
    }

    private static DrawerAssignment assign(IconIds ids, String sha16) {
        List<UiRow> rows = new ArrayList<UiRow>();
        rows.add(new UiRow("mibsdk.plugins", "Plugins", "mibsdk.plugins"));
        rows.add(new UiRow("a", "A", "pa", sha16 == null ? null
                : UiIcon.file("hello", HqcUiIds.INSTALL_ROOT + "/icons/hello/" + sha16 + ".png", sha16, 72, 72)));
        return DrawerSlots.assign(DrawerAssignment.EMPTY, rows, HqcUiIds.PLUGIN_ROW_SLOTS, ids, BuiltinIcons.NONE);
    }

    @Test
    void removedIconRestoresAFreshRowsBitmaps() {
        MenuItemController[] rows = drawer();
        HmiWidgets w = new HmiWidgets();
        IconIds ids = new IconIds(HqcUiIds.ICON_ID_FIRST, HqcUiIds.ICON_ID_LAST);
        w.apply(state(assign(ids, "aaaa000000000000")));
        assertArrayEquals(new int[] {609100, 609100}, rows[1].getBitmapIndices());
        w.apply(state(assign(ids, null)));
        assertArrayEquals(new MenuItemController().getBitmapIndices(), rows[1].getBitmapIndices());
    }

    @Test
    void iconChangedAfterTheRangeIsSpentRestoresAFreshRowsBitmaps() {
        MenuItemController[] rows = drawer();
        HmiWidgets w = new HmiWidgets();
        IconIds ids = new IconIds(609100, 609100);
        w.apply(state(assign(ids, "aaaa000000000000")));
        assertArrayEquals(new int[] {609100, 609100}, rows[1].getBitmapIndices());
        w.apply(state(assign(ids, "bbbb000000000000"))); // no id left
        assertArrayEquals(new MenuItemController().getBitmapIndices(), rows[1].getBitmapIndices());
    }
}
