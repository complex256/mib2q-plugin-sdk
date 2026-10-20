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
        assertEquals(Arrays.asList("OVR parking.soundDirections"),
                StatusLine.indicators(withOverrides(false, parking("F0R1L1X1", false))), "rear on: generic");
        assertEquals(Arrays.asList("OVR climate.fan"), StatusLine.indicators(withOverrides(false,
                new ActiveOverride("climate.fan", "x", OverrideScope.PERSISTENT, "3", false))));
        assertEquals(Collections.<String>emptyList(), StatusLine.indicators(withOverrides(false)));
    }

    @Test
    void overrideIndicatorShowsWhileRestoring() {
        assertEquals(Arrays.asList("PDC R OFF"), StatusLine.indicators(withOverrides(false, parking("F1R0L1X1", true))));
    }
}
