package mibsdk.adapter.hqc.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mibsdk.platform.UiEntry;
import mibsdk.platform.UiIcon;
import org.junit.jupiter.api.Test;

/** The stock-free parts of the page recipe: entry kind to list row, the row's cells, and where an open may happen. */
class PageScreensMappingTest {

    private static UiEntry entry(int kind, String label, String value, boolean on) {
        return new UiEntry(kind, "k", label, value, on, kind == UiEntry.LINK ? "o/p2" : null);
    }

    private static final UiIcon WIFI = UiIcon.stock("wifi");

    /**
     * Every list kind has its own row (the record-set column picks it). ITEM, LINK and CHOICE carry a value column
     * only when they have a value text; an item icon is its own column, and with a value both (ICON_VALUE).
     */
    @Test
    void everyKindMapsToRecipeRow() {
        assertEquals(PageScreens.ROW_LABEL, PageScreens.rowKind(entry(UiEntry.INFO, "Hello", null, false), -1, true));
        assertEquals(PageScreens.ROW_TOGGLE, PageScreens.rowKind(entry(UiEntry.TOGGLE, "Rack", null, true), -1, true));
        assertEquals(PageScreens.ROW_RADIO, PageScreens.rowKind(entry(UiEntry.RADIO, "English", null, true), -1, true));
        assertEquals(PageScreens.ROW_PROGRESS, PageScreens.rowKind(
                entry(UiEntry.PROGRESS, "Battery", null, false).withNumber(40), -1, true));
        assertEquals(PageScreens.ROW_ACTION, PageScreens.rowKind(entry(UiEntry.LINK, "More", null, false), -1, true));
    }

    @Test
    void kindFollowsValueAndIcon() {
        UiEntry plain = entry(UiEntry.ITEM, "Do", null, false);
        UiEntry valued = entry(UiEntry.ITEM, "Do", "now", false);
        assertEquals(PageScreens.ROW_ACTION, PageScreens.rowKind(plain, -1, true));
        assertEquals(PageScreens.ROW_VALUE, PageScreens.rowKind(valued, -1, true));
        assertEquals(PageScreens.ROW_ICON, PageScreens.rowKind(plain.withIcon(WIFI), 0, true));
        assertEquals(PageScreens.ROW_ICON_VALUE, PageScreens.rowKind(valued.withIcon(WIFI), 0, true));
        assertEquals(PageScreens.ROW_VALUE, PageScreens.rowKind(valued.withIcon(WIFI), -1, true),
                "an icon not in the layout: no icon column");

        PageRow icon = PageRow.of(valued.withIcon(WIFI), 2);
        assertEquals(new PageRow("Do", 2, PageScreens.ROW_ICON_VALUE, true, "now"), icon, "the value is the index");

        PageRow link = PageRow.of(entry(UiEntry.LINK, "Wi-Fi", "on", false), -1);
        assertEquals(new PageRow("Wi-Fi", 0, PageScreens.ROW_VALUE, true, "on >"), link);
        assertEquals(new PageRow("More >", 0, PageScreens.ROW_ACTION, true, null),
                PageRow.of(entry(UiEntry.LINK, "More", null, false), -1));

        UiEntry choice = new UiEntry(UiEntry.CHOICE, "lang", "Language", null, false, "p/main#lang")
                .withOptions(new String[] {"Deutsch", "English"}).withNumber(1);
        assertEquals(new PageRow("Language", 0, PageScreens.ROW_VALUE, true, "English"), PageRow.of(choice, -1));
    }

    /** F12: the bench N4 fallback ({@code PAGE_ICON_VALUE_ROWS} false) drops the icon of a valued item. */
    @Test
    void iconValueFallsBackToValueWhenDisabled() {
        UiEntry e = entry(UiEntry.ITEM, "Do", "now", false).withIcon(WIFI);
        assertEquals(PageScreens.ROW_VALUE, PageScreens.rowKind(e, 0, false));
        assertEquals(PageScreens.ROW_ICON, PageScreens.rowKind(entry(UiEntry.ITEM, "Do", null, false).withIcon(WIFI),
                0, false), "an icon without a value keeps its column");
    }

    /** The checkbox cell draws the state: the label has no {@code [on]}/{@code [off]} tag. */
    @Test
    void toggleRowIsLabelOnlyWithValue01() {
        assertEquals(new PageRow("Wi-Fi", 1, PageScreens.ROW_TOGGLE, true, null),
                PageRow.of(entry(UiEntry.TOGGLE, "Wi-Fi", null, true), -1));
        assertEquals(new PageRow("Wi-Fi", 0, PageScreens.ROW_TOGGLE, true, null),
                PageRow.of(entry(UiEntry.TOGGLE, "Wi-Fi", null, false), -1));
        assertEquals(new PageRow("English", 1, PageScreens.ROW_RADIO, true, null),
                PageRow.of(entry(UiEntry.RADIO, "English", null, true), -1));
        assertEquals(new PageRow("Battery", 40, PageScreens.ROW_PROGRESS, true, null),
                PageRow.of(entry(UiEntry.PROGRESS, "Battery", null, false).withNumber(40), -1));
    }

    /** A disabled entry is greyed by the enabled column, never by a text tag. */
    @Test
    void disabledRowWritesEnabled0() {
        PageRow r = PageRow.of(entry(UiEntry.ITEM, "Do", null, false).withEnabled(false).withIcon(WIFI), 0);
        assertFalse(r.enabled);
        assertEquals("Do", r.label, "no [disabled] or icon tag");
        assertTrue(PageRow.of(entry(UiEntry.ITEM, "Do", null, false), -1).enabled);
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
