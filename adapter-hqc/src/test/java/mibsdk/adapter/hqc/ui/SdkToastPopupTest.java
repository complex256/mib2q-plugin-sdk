package mibsdk.adapter.hqc.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.esolutions.hmi.widgets.audi.evo.high.widgets.LabelRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.widgets.IconController;
import de.esolutions.hmi.widgets.audi.evo.widgets.LabelController;
import java.io.File;
import java.lang.reflect.Field;
import mibsdk.adapter.hqc.SdkGlobals;
import mibsdk.platform.UiIcon;
import mibsdk.platform.UiToast;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Popup 609601: the text right-aligned in the popup, the icon (at most 20 px) just left of it. */
class SdkToastPopupTest {

    @TempDir
    File iconDir;

    @BeforeEach
    @AfterEach
    void reset() {
        HostWidgets.init();
        SdkGlobals.toastPopup = 0;
        SdkToastPopup.current = null;
        SdkToastPopup.wanted = SdkToastPopup.Part.NONE;
        SdkToastPopup.up = false;
    }

    private static Object field(String name) throws Exception {
        Field f = SdkToastPopup.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(SdkToastPopup.current);
    }

    @Test
    void rightAlignedTextWithIcon() throws Exception {
        SdkCarScreenFactoryTest.systemFonts(true);
        IconIds ids = new IconIds(HqcUiIds.ICON_ID_FIRST, HqcUiIds.ICON_ID_LAST);
        SdkToastPopup.wanted = SdkToastPopup.Part.of(new UiToast(1, "Saved", UiIcon.stock("dot")), ids,
                StatusLayoutTest.builtins(iconDir));
        new SdkCarScreenFactory(SdkCarScreenFactoryTest.fw()).getPartialPopup(HqcUiIds.POPUP_SDK_TOAST, 0);
        assertTrue(SdkToastPopup.current != null, "built: " + SdkGlobals.LOG.snapshot());

        LabelController label = (LabelController) field("label");
        Field hAlign = LabelRendererHigh.class.getDeclaredField("hAlign");
        hAlign.setAccessible(true);
        assertEquals(3, hAlign.getInt(label.getRenderer()), "ALIGN_RIGHT");
        assertEquals(HqcUiIds.TOAST_LABEL_MODEL, label.getModelID());
        int right = HqcUiIds.POPUP_TOAST_W - HqcUiIds.STATUS_PAD_PX;
        assertEquals(right, label.getX() + label.getWidth(), "the text ends at the right padding");

        IconController id = (IconController) field("idIcon");
        IconController file = (IconController) field("fileIcon");
        assertEquals(HqcUiIds.TOAST_FILE_ICON_MODEL, file.getModelID());
        int iconX = right - "Saved".length() * HqcUiIds.STATUS_CHAR_PX - HqcUiIds.STATUS_ICON_ADVANCE_PX;
        for (IconController i : new IconController[] {id, file}) {
            assertEquals(iconX, i.getX(), "the icon just left of the text");
            assertTrue(i.getWidth() <= 20 && i.getHeight() <= 20, "at most 20 px");
            assertTrue(i.getX() + i.getWidth() <= right - "Saved".length() * HqcUiIds.STATUS_CHAR_PX);
        }
        assertArrayEquals(new int[] {HqcUiIds.ICON_ID_FIRST}, id.getBitmapIndices(), "the built-in, by id");
        assertNull(id.getColorIndices(), "tinted, as a status cell");

        // A longer text moves the icon left; one with no icon keeps none.
        SdkToastPopup.Part longer = SdkToastPopup.Part.of(new UiToast(2, "Saved to profile 2", null), ids,
                StatusLayoutTest.builtins(iconDir));
        SdkToastPopup.current.place(longer);
        assertEquals(right - 18 * HqcUiIds.STATUS_CHAR_PX - HqcUiIds.STATUS_ICON_ADVANCE_PX, id.getX());
        assertTrue(SdkToastPopup.current.icons(longer, SdkToastPopup.wanted));
        assertEquals(0, id.getBitmapIndices().length, "no icon");
        assertEquals(HqcUiIds.STATUS_PAD_PX, SdkToastPopup.Part.of(new UiToast(3, "12345678901234567890123", null),
                ids, null).iconX(), "never left of the padding");
        assertEquals(HqcUiIds.TOAST_MS, mibsdk.api.Ui.TOAST_MS, "the stock-thread literal follows the API");
    }
}
