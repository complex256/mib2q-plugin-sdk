package mibsdk.adapter.hqc.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mibsdk.platform.UiEntry;
import org.junit.jupiter.api.Test;

/** The stock-free parts of the page recipe: entry kind to list row, row text, and where an open may happen. */
class PageScreensMappingTest {

    private static UiEntry entry(int kind, String label, String value, boolean on) {
        return new UiEntry(kind, "k", label, value, on, kind == UiEntry.LINK ? "o/p2" : null);
    }

    /**
     * INFO is a label row; ITEM and LINK are action rows; TOGGLE is its own kind (spike B: the stock checkbox cell
     * draws nothing, so the state is in the row text). Every row's text is {@code UiPageText.line}.
     */
    @Test
    void everyKindMapsToRecipeRow() {
        assertEquals(PageScreens.ROW_LABEL, PageScreens.rowKind(entry(UiEntry.INFO, "Hello", null, false)));
        assertEquals(PageScreens.ROW_ACTION, PageScreens.rowKind(entry(UiEntry.ITEM, "Do", "now", false)));
        assertEquals(PageScreens.ROW_TOGGLE, PageScreens.rowKind(entry(UiEntry.TOGGLE, "Rack", null, true)));
        assertEquals(PageScreens.ROW_ACTION, PageScreens.rowKind(entry(UiEntry.LINK, "More", null, false)));

        assertEquals("Hello", PageScreens.rowText(entry(UiEntry.INFO, "Hello", null, false)));
        assertEquals("Do: now", PageScreens.rowText(entry(UiEntry.ITEM, "Do", "now", false)));
        assertEquals("Do", PageScreens.rowText(entry(UiEntry.ITEM, "Do", null, false)));
        assertEquals("[on] Rack", PageScreens.rowText(entry(UiEntry.TOGGLE, "Rack", null, true)));
        assertEquals("[off] Rack", PageScreens.rowText(entry(UiEntry.TOGGLE, "Rack", null, false)));
        assertEquals("More >", PageScreens.rowText(entry(UiEntry.LINK, "More", null, false)));
    }

    /** The list kinds are distinct, small ids (they select the item factory's row). */
    @Test
    void rowKindsAreDistinct() {
        assertTrue(PageScreens.ROW_ACTION != PageScreens.ROW_TOGGLE);
        assertTrue(PageScreens.ROW_ACTION != PageScreens.ROW_LABEL);
        assertTrue(PageScreens.ROW_TOGGLE != PageScreens.ROW_LABEL);
    }

    /** Carried requirement: an open is honoured only on a Car screen (the drawer's) or an SDK page. */
    @Test
    void opensOnlyFromCarScreensOrSdkPages() {
        assertTrue(PageScreens.openAllowed(600044, false, -1), "Car main screen (the drawer's)");
        assertTrue(PageScreens.openAllowed(HqcUiIds.PAGE_SCREEN_ID, false, -1), "an SDK page");
        assertFalse(PageScreens.openAllowed(1300014, false, -1), "another module's screen");
        assertFalse(PageScreens.openAllowed(-1, false, -1), "nothing known on screen");
    }

    /** Carried requirement: never during RVC/PDC, and never over a popup or into a screen change away from Car. */
    @Test
    void neverOverParkingPopupsOrScreenChanges() {
        assertFalse(PageScreens.openAllowed(2100001, false, -1), "EarlyApps parking/RVC popup screen");
        assertFalse(PageScreens.openAllowed(600044, true, -1), "Car screen under a popup or parking overlay");
        assertFalse(PageScreens.openAllowed(600044, false, 2100001), "a change to the RVC is pending");
        assertTrue(PageScreens.openAllowed(600044, false, 600033), "a change to another Car screen is pending");
    }

    @Test
    void parkingOverlayIdsAreEarlyApps() {
        assertTrue(PageScreens.isParkingOverlay(2100000));
        assertTrue(PageScreens.isParkingOverlay(2100037));
        assertFalse(PageScreens.isParkingOverlay(600044));
        assertFalse(PageScreens.isParkingOverlay(65));
    }
}
