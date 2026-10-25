package mibsdk.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import mibsdk.api.OverrideScope;
import mibsdk.platform.UiIcon;
import mibsdk.platform.UiStatusPart;
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

    @Test
    void iconCountsTowardTheFairShareAndIsDroppedBeforeTheTextGetsTooShort() {
        UiIcon i = UiIcon.stock("star");
        List<StatusLine.Item> items = Arrays.asList(new StatusLine.Item("a", "ABCDEFGHIJ", i),
                new StatusLine.Item("b", "XYZ", null));
        List<UiStatusPart> p = StatusLine.parts(Collections.<String>emptyList(), items, 24);
        assertEquals("ABCDEFGHIJ", p.get(0).text()); assertSame(i, p.get(0).icon());   // 12 + 1 + 3 <= 24
        List<String> must = Arrays.asList("SAFE", "PDC R OFF");
        List<UiStatusPart> five = StatusLine.parts(must, items, 24);                    // room 9: b 3, a 5
        assertEquals("SAFE PDC R OFF", five.get(0).text());                             // must-show kept whole
        assertSame(i, five.get(1).icon()); assertEquals("ABC", five.get(1).text());     // 5 >= 2 + 3
        List<UiStatusPart> four = StatusLine.parts(must, items, 23);                    // room 8: b 3, a 4
        assertNull(four.get(1).icon()); assertEquals("ABCD", four.get(1).text());       // 4 < 2 + 3
    }

    @Test
    void partsAreTheIndicatorsAsOnePartThenOnePerItemShown() {
        UiIcon i = UiIcon.stock("star");
        List<UiStatusPart> p = StatusLine.parts(Arrays.asList("SAFE"),
                Arrays.asList(new StatusLine.Item("a", "", i), new StatusLine.Item("b", "XYZ", i)), 24);
        assertEquals(2, p.size(), "an empty text hides the item, icon or not");
        assertTrue(p.get(0).indicator());
        assertNull(p.get(0).icon());
        assertEquals("XYZ", p.get(1).text());
        assertFalse(p.get(1).indicator());
        assertSame(i, p.get(1).icon());
        List<UiStatusPart> none = StatusLine.parts(null, Arrays.asList(new StatusLine.Item("b", "XYZ", null)), 24);
        assertEquals(1, none.size(), "no indicator part without indicators");
        assertFalse(none.get(0).indicator());
    }

    @Test
    void shortTextKeepsItsIcon() {
        UiIcon i = UiIcon.stock("star");
        StatusLine.Item ok = new StatusLine.Item("a", "OK", i);
        assertEquals(4, ok.width());
        assertSame(i, ok.iconAt(4), "the whole text fits beside the icon");
        assertEquals("OK", ok.render(4));
        assertNull(ok.iconAt(3));
        assertEquals("OK", ok.render(3));
        StatusLine.Item longer = new StatusLine.Item("a", "ABCDEFGH", i);
        assertSame(i, longer.iconAt(5));
        assertEquals("ABC", longer.render(5));
        assertNull(longer.iconAt(4));
        assertEquals("ABCD", longer.render(4));
    }

    @Test
    void lineWithoutIconsIsUnchanged() {
        UiIcon i = UiIcon.stock("star");
        List<String> ind = Arrays.asList("SAFE", "OVR alpha");
        List<StatusLine.Item> with = Arrays.asList(new StatusLine.Item("a", "TS up MQ up", i),
                new StatusLine.Item("b", "RACK", i));
        List<StatusLine.Item> without = Arrays.asList(new StatusLine.Item("a", "TS up MQ up"),
                new StatusLine.Item("b", "RACK"));
        assertEquals("SAFE TS up MQ up RACK", StatusLine.line(ind, without, 24));
        assertEquals(StatusLine.line(ind, without, 24), StatusLine.line(ind, with, 24), "icons are not in the line");
        assertEquals(StatusLine.line(ind, without, 16), StatusLine.line(ind, with, 16));
        assertEquals("SAFE TS... RACK", StatusLine.line(Arrays.asList("SAFE"), with, 16));
    }

    /** Fix round 1 (Task 3): an icon never costs an indicator; parts and line keep the same indicators. */
    @Test
    void iconsNeverChangeWhichIndicatorsShow() {
        UiIcon i = UiIcon.stock("star");
        List<String> ind = Arrays.asList("SAFE", "OVR fan");
        List<StatusLine.Item> one = Arrays.asList(new StatusLine.Item("a", "RACKSX", i));
        assertEquals("SAFE OVR fan RACKSX", StatusLine.line(ind, one, 20));
        assertEquals("SAFE OVR fan", StatusLine.parts(ind, one, 20).get(0).text());
        List<String> many = Arrays.asList("SAFE", "PDC R OFF", "OVR alpha", "OVR fan", "OVR seat");
        List<StatusLine.Item> two = Arrays.asList(new StatusLine.Item("a", "TS up", i),
                new StatusLine.Item("b", "RACK up MQ", i));
        for (int max = StatusLine.MIN_LINE_CHARS; max <= 40; max++) {
            for (List<String> d : Arrays.asList(ind, many)) {
                for (List<StatusLine.Item> items : Arrays.asList(one, two)) {
                    List<StatusLine.Item> plain = new java.util.ArrayList<StatusLine.Item>();
                    for (StatusLine.Item it : items) {
                        plain.add(new StatusLine.Item(it.owner, it.text));
                    }
                    List<UiStatusPart> p = StatusLine.parts(d, items, max);
                    List<UiStatusPart> lineParts = StatusLine.parts(d, plain, max); // what line() joins
                    assertTrue(p.get(0).indicator() && lineParts.get(0).indicator(), "max " + max);
                    assertEquals(lineParts.get(0).text(), p.get(0).text(), "max " + max);
                    assertTrue(StatusLine.line(d, items, max).startsWith(p.get(0).text()), "max " + max);
                }
            }
        }
    }
}
