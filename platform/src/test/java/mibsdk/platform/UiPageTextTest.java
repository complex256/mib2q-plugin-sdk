package mibsdk.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /** F17: the HMI's label and value cells; a LINK's chevron goes on the value cell when there is one. */
    @Test
    void hmiCellsDropTheTogglePrefix() {
        UiEntry toggle = new UiEntry(UiEntry.TOGGLE, "w", "Wi-Fi", null, true, null);
        assertEquals("Wi-Fi", UiPageText.label(toggle));
        assertNull(UiPageText.valueText(toggle));
        UiEntry choice = new UiEntry(UiEntry.CHOICE, "lang", "Language", null, false, "p/main#lang")
                .withOptions(new String[] {"English", "Deutsch"}).withNumber(0);
        assertEquals("Language", UiPageText.label(choice));
        assertEquals("English", UiPageText.valueText(choice));
        UiEntry status = new UiEntry(UiEntry.LINK, "s", "Status", "on", false, "p/s");
        assertEquals("on >", UiPageText.valueText(status));
        assertEquals("Status", UiPageText.label(status));
        UiEntry more = new UiEntry(UiEntry.LINK, "m", "More", null, false, "p/m");
        assertEquals("More >", UiPageText.label(more));
        assertNull(UiPageText.valueText(more));
        UiEntry item = new UiEntry(UiEntry.ITEM, "a", "Apple", "3", false, null);
        assertEquals("Apple", UiPageText.label(item));
        assertEquals("3", UiPageText.valueText(item));
        assertNull(UiPageText.valueText(new UiEntry(UiEntry.ITEM, "a", "Apple", null, false, null)));
        assertNull(UiPageText.valueText(new UiEntry(UiEntry.INFO, null, "hi", null, false, null)));
    }

    @Test
    void entryCopiesAreImmutable() {
        String[] opts = {"Off", "Low"};
        UiEntry base = new UiEntry(UiEntry.DROPDOWN, "lvl", "Level", null, false, null);
        UiEntry e = base.withOptions(opts).withNumber(1).withEnabled(false);
        opts[0] = "changed";
        e.options()[1] = "changed";
        assertEquals("Off", e.options()[0]);
        assertEquals("Low", e.options()[1]);
        assertEquals(1, e.number());
        assertFalse(e.enabled());
        assertTrue(base.enabled(), "the original is untouched");
        assertNull(base.options());
        assertEquals(0, base.number());
        UiEntry s = new UiEntry(UiEntry.SLIDER, "b", "Bass", null, false, null).withRange(-6, 6, 2, true);
        assertEquals(-6, s.min());
        assertEquals(6, s.max());
        assertEquals(2, s.step());
        assertTrue(s.centred());
        UiIcon i = UiIcon.stock("wifi");
        assertSame(i, s.withIcon(i).icon());
        assertNull(s.icon());
    }

    @Test
    void inputCarriesValueAndText() {
        UiInput in = new UiInput(UiInput.VALUE_REQUESTED, "p/main", "lvl", false, 2, null);
        assertEquals(2, in.value());
        assertNull(in.text());
        UiInput old = new UiInput(UiInput.ENTRY_SELECTED, "p/main", "a", false);
        assertEquals(0, old.value());
        assertNull(old.text());
        assertEquals("abc", new UiInput(UiInput.DIALOG_DONE, "p/main", null, false, 1, "abc").text());
    }
}
