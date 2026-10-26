package mibsdk.adapter.hqc.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import mibsdk.platform.UiDialog;
import mibsdk.platform.UiEntry;
import mibsdk.platform.UiPage;
import org.junit.jupiter.api.Test;

/** The input texts 609040..609082: typed labels and options, the slider's min and max, the dialogs' texts. */
class InputTextIdsTest {

    private static final UiEntry MODE = new UiEntry(UiEntry.DROPDOWN, "mode", "Mode", null, false, null)
            .withOptions(new String[] {"Off", "Low", "High"});
    private static final UiEntry BASS = new UiEntry(UiEntry.SLIDER, "bass", "Bass", null, false, null)
            .withRange(-6, 6, 1, true).withNumber(0);
    private static final UiEntry LEVEL = new UiEntry(UiEntry.SLIDER, "lvl", "Level", null, false, null)
            .withRange(0, 10, 2, false).withNumber(4);

    private static PageScreens.Shown shown() {
        UiPage p = new UiPage("p/main", "Main", Arrays.asList(MODE,
                new UiEntry(UiEntry.ITEM, "go", "Go", null, false, null), BASS, LEVEL));
        return new PageScreens.Shown("p/main", PageLayout.of(p, ItemIcons.NONE), 1);
    }

    private static String text(int id, SliderSession slider, UiDialog d) {
        return InputTextIds.textFor(id, shown(), slider, d);
    }

    @Test
    void labelsAndOptions() {
        assertEquals("Mode", text(609040, null, null));
        assertEquals("Off", text(609041, null, null));
        assertEquals("High", text(609043, null, null));
        assertEquals("", text(609044, null, null), "no 4th option: ours, empty");
        assertEquals("Bass", text(609049, null, null), "slot 1: 609040 + 9");
        assertEquals("Level", text(609058, null, null));
        assertEquals("", text(609067, null, null), "no 4th typed row");
        assertEquals("", InputTextIds.textFor(609040, null, null, null), "no page shown");
    }

    @Test
    void sliderMinAndMax() {
        SliderSession bass = new SliderSession(-6, 1, 12, 0, 1);
        assertEquals("-6", text(HqcUiIds.TEXT_SLIDER_MIN, bass, null));
        assertEquals("+6", text(HqcUiIds.TEXT_SLIDER_MAX, bass, null), "centred: the positive end signed");
        SliderSession level = new SliderSession(0, 2, 5, 4, 2);
        assertEquals("0", text(609076, level, null));
        assertEquals("10", text(609077, level, null));
        assertEquals("", text(609076, null, null), "no slider open");
        assertEquals("", text(609077, new SliderSession(0, 1, 1, 0, 0), null), "slot 0 is a dropdown");
    }

    @Test
    void dialogTexts() {
        UiDialog confirm = new UiDialog(1, UiDialog.CONFIRM, "p/main", "Reset?", "All settings\nare lost", null,
                null, 0);
        UiDialog speller = new UiDialog(2, UiDialog.SPELLER, "p/main", "Name", "Bob", null, null, 20);
        assertEquals("Reset?", text(609078, null, confirm));
        assertEquals("All settings\nare lost", text(609080, null, confirm));
        assertEquals("", text(609079, null, confirm), "the speller title of a confirm");
        assertEquals("Name", text(609079, null, speller));
        assertEquals("", text(609078, null, speller));
        assertEquals("", text(609081, null, speller));
        assertEquals("", text(609082, null, speller));
        assertEquals("", text(609078, null, null));
    }

    @Test
    void outsideTheRangeIsNotOurs() {
        assertNull(text(609039, null, null));
        assertNull(text(609083, null, null));
        assertNull(text(HqcUiIds.TEXT_PAGE_TITLE, null, null));
    }
}
