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

    /**
     * Fix round 1 (Important 3): an open is honoured on an SDK page, or on a Car screen (the drawer's) within
     * OPEN_TTL_MS of an SDK press; never from another module, never without a recent press.
     */
    @Test
    void opensOnlyOnSdkPagesOrRightAfterAnSdkPress() {
        long recent = PageScreens.OPEN_TTL_MS;
        long stale = PageScreens.OPEN_TTL_MS + 1;
        assertTrue(PageScreens.openAllowed(600044, false, -1, recent), "Car screen, just pressed");
        assertFalse(PageScreens.openAllowed(600044, false, -1, stale), "Car screen, no recent press");
        assertFalse(PageScreens.openAllowed(600044, false, -1, Long.MAX_VALUE), "Car screen, never pressed");
        assertTrue(PageScreens.openAllowed(HqcUiIds.PAGE_SCREEN_ID, false, -1, stale), "an SDK page");
        assertFalse(PageScreens.openAllowed(1300014, false, -1, recent), "another module's screen");
        assertFalse(PageScreens.openAllowed(-1, false, -1, recent), "nothing known on screen");
    }

    /** Carried requirement: never during RVC/PDC, and never over a popup or into a screen change away from Car. */
    @Test
    void neverOverParkingPopupsOrScreenChanges() {
        long recent = 0;
        assertFalse(PageScreens.openAllowed(2100001, false, -1, recent), "EarlyApps parking/RVC popup screen");
        assertFalse(PageScreens.openAllowed(600044, true, -1, recent), "Car screen under a popup or parking overlay");
        assertFalse(PageScreens.openAllowed(HqcUiIds.PAGE_SCREEN_ID, true, -1, recent), "SDK page under one");
        assertFalse(PageScreens.openAllowed(600044, false, 2100001, recent), "a change to the RVC is pending");
        assertFalse(PageScreens.openAllowed(HqcUiIds.PAGE_SCREEN_ID, false, 2100001, recent), "from a page too");
        assertTrue(PageScreens.openAllowed(600044, false, 600033, recent), "a change to another Car screen is pending");
    }

    /** Fix round 1 (Important 1): the gate before every screen change the SDK makes (show, BACK, close). */
    @Test
    void screenChangeAllowedOnlyWithoutParkingOrPendingChangeAway() {
        assertTrue(PageScreens.screenChangeAllowed(false, -1));
        assertTrue(PageScreens.screenChangeAllowed(false, 600044));
        assertTrue(PageScreens.screenChangeAllowed(false, HqcUiIds.PAGE_SCREEN_ID));
        assertFalse(PageScreens.screenChangeAllowed(true, -1));
        assertFalse(PageScreens.screenChangeAllowed(false, 2100001));
        assertFalse(PageScreens.screenChangeAllowed(false, 1300014));
    }

    @Test
    void parkingOverlayIdsAreEarlyApps() {
        assertTrue(PageScreens.isParkingOverlay(2100000));
        assertTrue(PageScreens.isParkingOverlay(2100037));
        assertFalse(PageScreens.isParkingOverlay(600044));
        assertFalse(PageScreens.isParkingOverlay(65));
    }
}
