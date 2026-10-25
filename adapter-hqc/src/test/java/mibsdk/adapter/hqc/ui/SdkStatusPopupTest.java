package mibsdk.adapter.hqc.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.esolutions.hmi.widgets.audi.evo.high.widgets.IconRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.widgets.IconController;
import de.esolutions.hmi.widgets.audi.evo.widgets.LabelController;
import java.util.ArrayList;
import java.util.List;
import mibsdk.platform.UiIcon;
import mibsdk.platform.UiStatusPart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The per-icon colour flag on popup 609600: own colours (plate entry 0) or tinted (no colour indices). */
class SdkStatusPopupTest {

    private IconController[] ids;
    private IconController[] files;
    private SdkStatusPopup popup;

    @BeforeEach
    void build() {
        HostWidgets.init();
        ids = new IconController[HqcUiIds.STATUS_CELLS];
        files = new IconController[HqcUiIds.STATUS_CELLS];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = icon();
            files[i] = icon();
        }
        popup = new SdkStatusPopup(new LabelController[HqcUiIds.STATUS_CELLS + 1], ids, files);
    }

    private static IconController icon() {
        IconController c = new IconController();
        c.setRenderer(new IconRendererHigh(c));
        c.setCompositesDirty(false); // as after the first paint
        return c;
    }

    /** Two file-icon items: the first own colours if {@code own}, the second always tinted. */
    private static StatusLayout layout(boolean own) {
        String root = HqcUiIds.INSTALL_ROOT + "/icons/hello/";
        List<UiStatusPart> parts = new ArrayList<UiStatusPart>();
        parts.add(new UiStatusPart("A", UiIcon.file("hello", root + "aaaa000000000000.png", "aaaa000000000000", 20, 20)
                .withOwnColours(own), false));
        parts.add(new UiStatusPart("B", UiIcon.file("hello", root + "bbbb000000000000.png", "bbbb000000000000", 20, 20),
                false));
        return StatusLayout.of(parts, null, null);
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

        popup.icons(own, tinted);
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

        popup.icons(own, own);
        assertArrayEquals(new int[] {1, 0, 4, 0}, ids[0].getColorIndices());
        assertArrayEquals(new int[] {1, 0, 4, 0}, files[0].getColorIndices());
        for (int i = 0; i < ids.length; i++) {
            assertFalse(ids[i].getRenderer().areCompositesDirty(), "cell " + i);
            assertFalse(files[i].getRenderer().areCompositesDirty(), "cell " + i);
        }
        assertNull(ids[1].getColorIndices());
    }
}
