package mibsdk.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class UiPageTextTest {

    @Test
    void everyKind() {
        assertEquals("Apple", UiPageText.line(new UiEntry(UiEntry.ITEM, "a", "Apple", null, false, null)));
        assertEquals("Apple: 3", UiPageText.line(new UiEntry(UiEntry.ITEM, "a", "Apple", "3", false, null)));
        assertEquals("[on] Rack", UiPageText.line(new UiEntry(UiEntry.TOGGLE, "r", "Rack", null, true, null)));
        assertEquals("[off] Rack", UiPageText.line(new UiEntry(UiEntry.TOGGLE, "r", "Rack", null, false, null)));
        assertEquals("SDK >", UiPageText.line(new UiEntry(UiEntry.LINK, "l", "SDK", null, false, "x/y")));
        assertEquals("SDK >", UiPageText.line(new UiEntry(UiEntry.LINK, "l", "SDK", "", false, "x/y")));
        assertEquals("hello  world", UiPageText.line(new UiEntry(UiEntry.INFO, "i", "hello  world", null, false, null)));
    }

    /** Bench finding (UI Task 19): the Plugins menu's state words (LINK values) were never drawn. */
    @Test
    void linkShowsItsValue() {
        assertEquals("Crashy: Quarantined >",
                UiPageText.line(new UiEntry(UiEntry.LINK, "plugin.crashy", "Crashy", "Quarantined", false, "x/y")));
    }
}
