package mibsdk.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import mibsdk.api.OverrideScope;
import org.junit.jupiter.api.Test;

class StatusLineTest {

    private static SdkState withOverrides(boolean safe, ActiveOverride... o) {
        return new SdkState(safe, safe ? "marker" : null, false, false, "hqc", Arrays.asList(o));
    }

    private static ActiveOverride parking(String value, boolean restoring) {
        return new ActiveOverride("parking.soundDirections", "pdc", OverrideScope.UNTIL_IGNITION_OFF, value, restoring);
    }

    @Test
    void indicatorsFirstThenItemsEachCutToItsShare() {
        assertEquals("SAFE TS... RACK",
                StatusLine.compose(Arrays.asList("SAFE"), Arrays.asList("TS up MQ up", "RACK"), 16));
        assertEquals("SAFE TS up MQ up RACK",
                StatusLine.compose(Arrays.asList("SAFE"), Arrays.asList("TS up MQ up", "", null, "RACK"), 24));
        assertEquals("RACK", StatusLine.compose(Collections.<String>emptyList(), Arrays.asList("RACK"), 24));
    }

    @Test
    void overrideIndicatorForRearMute() {
        assertEquals(Arrays.asList("PDC R OFF"), StatusLine.indicators(withOverrides(false, parking("F1R0L1X1", false))));
        assertEquals(Arrays.asList("SAFE", "PDC R OFF"),
                StatusLine.indicators(withOverrides(true, parking("F1R0L1X1", false))));
        assertEquals(Arrays.asList("OVR PDC"),
                StatusLine.indicators(withOverrides(false, parking("F0R1L1X1", false))), "rear on: short generic");
        assertEquals(Arrays.asList("OVR fan"), StatusLine.indicators(withOverrides(false,
                new ActiveOverride("climate.fan", "x", OverrideScope.PERSISTENT, "3", false))), "last key segment");
        assertEquals(Arrays.asList("OVR seatheat"), StatusLine.indicators(withOverrides(false,
                new ActiveOverride("comfort.seatheaterlevel", "x", OverrideScope.PERSISTENT, "3", false))),
                "capped at 8 characters");
        assertEquals(Collections.<String>emptyList(), StatusLine.indicators(withOverrides(false)));
    }

    @Test
    void overrideIndicatorShowsWhileRestoring() {
        assertEquals(Arrays.asList("PDC R OFF"), StatusLine.indicators(withOverrides(false, parking("F1R0L1X1", true))));
    }

    /** Fix round 1, Minor 1: SAFE and PDC R OFF first; other indicators never crowd out the plugin items. */
    @Test
    void indicatorsNeverFillTheLineWhenItemsExist() {
        SdkState s = withOverrides(true,
                new ActiveOverride("a.alpha", "x", OverrideScope.PERSISTENT, "1", false),
                new ActiveOverride("b.bravo", "x", OverrideScope.PERSISTENT, "1", false),
                parking("F1R0L1X1", false),
                new ActiveOverride("c.charlie", "x", OverrideScope.PERSISTENT, "1", false));
        assertEquals(Arrays.asList("SAFE", "PDC R OFF", "OVR alpha", "OVR bravo", "OVR charlie"), StatusLine.indicators(s));
        assertEquals("SAFE PDC R OFF RACK up", StatusLine.compose(StatusLine.indicators(s), Arrays.asList("RACK up"), 24));
        assertEquals("SAFE PDC R OFF OVR alpha OVR bravo OVR charlie",
                StatusLine.compose(StatusLine.indicators(s), Collections.<String>emptyList(), 60), "no items: all of them");
        assertEquals("SAFE TS up MQ up RACK",
                StatusLine.compose(Arrays.asList("SAFE", "OVR xyz"), Arrays.asList("TS up MQ up", "RACK"), 21),
                "the first indicator always shows");
    }

    // ---- fair share (water-filling) ----

    @Test
    void anEarlyLongItemNoLongerPushesALaterOneOffTheLine() {
        assertEquals("TS up MQ... RACK", StatusLine.compose(null, Arrays.asList("TS up MQ up!", "RACK"), 16));
        assertEquals("RACK TS up M...", StatusLine.compose(null, Arrays.asList("RACK", "TS up MQ up!"), 15));
    }

    @Test
    void shortItemsKeepTheirTextAndDonateTheRestToLongOnes() {
        // 3 items, 22 chars of text: share 7; RACK (4) keeps all, the two long ones share the other 18 (9 each)
        assertEquals("TS up... RACK C801 b...",
                StatusLine.compose(null, Arrays.asList("TS up MQ up", "RACK", "C801 boom!!!"), 24));
        assertEquals("A B CDEFGHIJKL abcdefgh...",
                StatusLine.compose(null, Arrays.asList("A", "B", "CDEFGHIJKL", "abcdefghijkl"), 26),
                "everything that fits stays whole");
    }

    @Test
    void anOddCharacterGoesToTheEarlierItem() {
        assertEquals("ABCDEFGHIJKL abcdefgh...",
                StatusLine.compose(null, Arrays.asList("ABCDEFGHIJKL", "abcdefghijkl"), 24));
    }

    @Test
    void narrowSharesAreHardCutWithoutAnEllipsis() {
        // 5 items of 12 in 24 chars: 20 chars of text, 4 each; "..." would leave one character
        assertEquals("aaaa bbbb cccc dddd eeee", StatusLine.compose(null,
                Arrays.asList("aaaaaaaaaaaa", "bbbbbbbbbbbb", "cccccccccccc", "dddddddddddd", "eeeeeeeeeeee"), 24));
        assertEquals("TS u RACK", StatusLine.compose(null, Arrays.asList("TS up MQ up", "RACK"), 9));
        assertEquals("AB EFG", StatusLine.compose(null, Arrays.asList("AB CD", "EFGHIJ"), 7),
                "a cut never ends in a space");
    }

    @Test
    void itemsBelowTheMinimumWidthAreDroppedFromTheEnd() {
        // 7 items at 3 chars + 6 spaces = 27 > 24: the last one goes; 6 share 19 chars: 4 + 3*5
        assertEquals("item ite ite ite ite ite", StatusLine.compose(null,
                Arrays.asList("item1", "item2", "item3", "item4", "item5", "item6", "item7"), 24));
        assertEquals("A B C D E F G H I J K L", StatusLine.compose(null,
                Arrays.asList("A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L"), 24),
                "an item shorter than the minimum needs only its own length");
        assertEquals("SAFE ABC...", StatusLine.compose(Arrays.asList("SAFE"),
                Arrays.asList("ABCDEFG", "HIJKLMN"), 11), "the first one is kept");
        assertEquals("SAFE A u", StatusLine.compose(Arrays.asList("SAFE"), Arrays.asList("A up"), 8),
                "at the minimum width");
        assertEquals("SAFE", StatusLine.compose(Arrays.asList("SAFE"), Arrays.asList("A up"), 7),
                "never narrower than the minimum: no item instead");
        assertEquals("SAFE A", StatusLine.compose(Arrays.asList("SAFE"), Arrays.asList("A"), 6),
                "an item shorter than the minimum needs only its length");
    }

    @Test
    void indicatorsTakeAtMostHalfTheLineAndItemsGetTheRest() {
        assertEquals("SAFE TS up MQ up RACK",
                StatusLine.compose(Arrays.asList("SAFE", "OVR alpha"),
                        Arrays.asList("TS up MQ up", "RACK"), 24), "budget 12: no room for OVR alpha");
        assertEquals("SAFE PDC R OFF RACK up",
                StatusLine.compose(Arrays.asList("SAFE", "PDC R OFF", "OVR alpha"), Arrays.asList("RACK up"), 24),
                "short items leave the indicators more room");
        assertEquals("SAFE OVR fan TS u... C80...", StatusLine.compose(Arrays.asList("SAFE", "OVR fan"),
                Arrays.asList("TS up MQ up", "C801 boom!!!"), 27), "items share what the indicators leave");
    }

    // ---- order ----

    @Test
    void orderIsTheSavedOrderThenTheRestByPluginId() {
        assertEquals(Arrays.asList("b", "a", "c"),
                StatusLine.order(Arrays.asList("x", "b"), Arrays.asList("c", "a", "b")));
        assertEquals(Arrays.asList("a", "c", "zeta"),
                StatusLine.order(Collections.<String>emptyList(), Arrays.asList("zeta", "c", "a")));
        assertEquals(Arrays.asList("c", "a"), StatusLine.order(Arrays.asList("c", "c", "a"), Arrays.asList("a", "c")),
                "duplicates in the saved order count once");
        assertEquals(Arrays.asList("a"), StatusLine.order(null, Arrays.asList("a")));
    }

    // ---- must-show safety indicators ----

    @Test
    void safeAndRearPdcOffAreNeverDroppedForItems() {
        assertEquals("SAFE PDC R OFF TS u C801", StatusLine.compose(Arrays.asList("SAFE", "PDC R OFF"),
                Arrays.asList("TS up MQ up", "C801 boom!!!"), 24), "both show, beyond the half-line budget");
        assertEquals("PDC R OFF TS u... C80...", StatusLine.compose(Arrays.asList("PDC R OFF"),
                Arrays.asList("TS up MQ up", "C801 boom!!!"), 24));
        assertEquals("SAFE PDC R OFF", StatusLine.compose(Arrays.asList("SAFE", "PDC R OFF"), Arrays.asList("RACK"),
                14), "nothing left: no items");
    }

    @Test
    void overrideIndicatorsStayWithinTheBudgetAfterTheMustShowOnes() {
        assertEquals("SAFE PDC R OFF TS u RACK", StatusLine.compose(Arrays.asList("SAFE", "PDC R OFF", "OVR fan"),
                Arrays.asList("TS up MQ up", "RACK"), 24), "OVR fan does not fit the budget");
        assertEquals("SAFE PDC R OFF OVR fan RACK", StatusLine.compose(Arrays.asList("SAFE", "PDC R OFF", "OVR fan"),
                Arrays.asList("RACK"), 40), "it does when the items are short");
    }
}
