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
    void indicatorsFirstThenItemsTruncatedWithEllipsis() {
        assertEquals("SAFE TS up MQ...",
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
}
