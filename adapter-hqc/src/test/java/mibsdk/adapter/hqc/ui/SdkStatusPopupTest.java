package mibsdk.adapter.hqc.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.esolutions.hmi.widgets.audi.evo.high.widgets.IconRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.widgets.IconController;
import de.esolutions.hmi.widgets.audi.evo.widgets.LabelController;
import de.esolutions.hmi.widgets.audi.evo.widgets.PartialPopupController;
import java.util.ArrayList;
import java.util.List;
import mibsdk.platform.UiIcon;
import mibsdk.platform.UiStatusPart;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The per-icon colour flag on popup 609600: own colours (plate entry 0) or tinted (no colour indices), and the repaint
 * a colour change needs (bench C1: the job only marks widgets dirty, and nothing paints them until the next repaint).
 */
class SdkStatusPopupTest {

    private IconController[] ids;
    private IconController[] files;
    private LabelController[] labels;
    private SdkStatusPopup popup;
    /** How often the popup asked the HMI for a repaint (stock AbstractPartialPopupController.triggerRepaint). */
    private int repaints;

    @BeforeEach
    void build() {
        HostWidgets.init();
        ids = new IconController[HqcUiIds.STATUS_CELLS];
        files = new IconController[HqcUiIds.STATUS_CELLS];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = icon();
            files[i] = icon();
        }
        PartialPopupController p = new PartialPopupController(HqcUiIds.POPUP_SDK_STATUS) {
            public void triggerRepaint() {
                repaints++;
            }
        };
        for (int i = 0; i < ids.length; i++) {
            p.add(ids[i]);
            p.add(files[i]);
        }
        labels = new LabelController[HqcUiIds.STATUS_CELLS + 1];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = new LabelController();
            p.add(labels[i]);
        }
        popup = new SdkStatusPopup(labels, ids, files, p);
    }

    @AfterEach
    void reset() {
        SdkStatusPopup.current = null;
    }

    private static IconController icon() {
        IconController c = new IconController();
        c.setRenderer(new IconRendererHigh(c));
        c.setCompositesDirty(false); // as after the first paint
        return c;
    }

    /** Two file-icon items: the first own colours if {@code own}, the second always tinted. */
    private static StatusLayout layout(boolean own) {
        return layout("A", own, false);
    }

    /** Two file-icon items with the first item's text {@code a}: each own colours if its flag is set. */
    private static StatusLayout layout(String a, boolean own0, boolean own1) {
        String root = HqcUiIds.INSTALL_ROOT + "/icons/hello/";
        List<UiStatusPart> parts = new ArrayList<UiStatusPart>();
        parts.add(new UiStatusPart(a, UiIcon.file("hello", root + "aaaa000000000000.png", "aaaa000000000000", 20, 20)
                .withOwnColours(own0), false));
        parts.add(new UiStatusPart("B", UiIcon.file("hello", root + "bbbb000000000000.png", "bbbb000000000000", 20, 20)
                .withOwnColours(own1), false));
        return StatusLayout.of(parts, null, null);
    }

    /** Applies {@code want} after {@code last} the way the HMI job does. */
    private void applyAfter(StatusLayout last, StatusLayout want) {
        SdkStatusPopup.current = popup;
        HmiWidgets w = new HmiWidgets();
        w.apply(new HmiWidgetState(new int[0][], last));
        repaints = 0;
        clean();
        w.apply(new HmiWidgetState(new int[0][], want));
    }

    private void clean() {
        for (int i = 0; i < ids.length; i++) {
            ids[i].setCompositesDirty(false);
            files[i].setCompositesDirty(false);
        }
    }

    @Test
    void colourToggleReappliesIndicesAndMarksBothIconsDirty() {
        StatusLayout tinted = layout(false);
        StatusLayout own = layout(true);

        assertTrue(popup.icons(own, tinted), "a colour changed");
        assertArrayEquals(new int[] {1, 0, 4, 0}, ids[0].getColorIndices());
        assertArrayEquals(new int[] {1, 0, 4, 0}, files[0].getColorIndices());
        assertTrue(ids[0].getRenderer().areCompositesDirty(), "the id icon is redrawn");
        assertTrue(files[0].getRenderer().areCompositesDirty(), "the file icon is redrawn");
        for (int i = 1; i < ids.length; i++) {
            assertNull(ids[i].getColorIndices(), "cell " + i);
            assertNull(files[i].getColorIndices(), "cell " + i);
            assertFalse(ids[i].getRenderer().areCompositesDirty(), "cell " + i + " is left alone");
            assertFalse(files[i].getRenderer().areCompositesDirty(), "cell " + i + " is left alone");
        }

        clean();
        popup.icons(tinted, own);
        assertNull(ids[0].getColorIndices(), "tinted again: no colour indices");
        assertNull(files[0].getColorIndices());
        assertTrue(ids[0].getRenderer().areCompositesDirty());
        assertTrue(files[0].getRenderer().areCompositesDirty());
    }

    @Test
    void unchangedColourTouchesNothing() {
        StatusLayout own = layout(true);
        popup.icons(own, StatusLayout.EMPTY);
        clean();

        assertFalse(popup.icons(own, own), "no colour changed");
        assertArrayEquals(new int[] {1, 0, 4, 0}, ids[0].getColorIndices());
        assertArrayEquals(new int[] {1, 0, 4, 0}, files[0].getColorIndices());
        for (int i = 0; i < ids.length; i++) {
            assertFalse(ids[i].getRenderer().areCompositesDirty(), "cell " + i);
            assertFalse(files[i].getRenderer().areCompositesDirty(), "cell " + i);
        }
        assertNull(ids[1].getColorIndices());
    }

    @Test
    void colourOnlyChangeRepaintsOncePerJob() {
        applyAfter(layout("A", false, false), layout("A", true, true));
        assertEquals(1, repaints, "two cells recoloured, one repaint");
        assertArrayEquals(new int[] {1, 0, 4, 0}, files[1].getColorIndices());

        applyAfter(layout("A", true, true), layout("A", false, true));
        assertEquals(1, repaints, "back to tinted repaints too");
        assertNull(files[0].getColorIndices());
        assertFalse(files[1].getRenderer().areCompositesDirty(), "the unchanged cell is left alone");
    }

    @Test
    void passWithoutAColourChangeDoesNotRepaint() {
        applyAfter(layout("A", true, false), layout("A", true, false));
        assertEquals(0, repaints, "nothing changed");

        applyAfter(layout("A", true, false), layout("Z", true, false));
        assertEquals(0, repaints, "a same-length text change repaints through its own label model write");
    }

    /**
     * The line is packed to the right edge (bench B9), so a text-length change moves every cell, the indicators too.
     * setBounds only stores the bounds, and the label's model write may paint before the job: the job repaints once.
     */
    @Test
    void geometryChangeMovesTheCellsAndRepaintsOncePerJob() {
        StatusLayout shorter = layout("A", false, false);
        StatusLayout longer = layout("A longer", false, false);
        applyAfter(shorter, longer);
        assertEquals(1, repaints, "the moved group is drawn now");
        assertEquals(longer.cell(0).iconX, files[0].getX(), "the first icon moved");
        assertEquals(longer.cell(1).labelX, labels[2].getX(), "the last label stays at the right edge");
        assertEquals(shorter.cell(1).labelX, longer.cell(1).labelX);
        assertTrue(longer.cell(0).iconX < shorter.cell(0).iconX, "the group grew to the left");

        applyAfter(layout("A", false, false), layout("A longer", true, false));
        assertEquals(1, repaints, "a move and a colour change in one job: one repaint");
    }
}
