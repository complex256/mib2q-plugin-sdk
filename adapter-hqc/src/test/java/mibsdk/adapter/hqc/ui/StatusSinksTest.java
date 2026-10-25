package mibsdk.adapter.hqc.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import mibsdk.platform.UiStatusPart;
import mibsdk.runtime.RingLog;
import org.junit.jupiter.api.Test;

/** Review Focus 4: the SDK popup while it works, popup 65 only as the fallback; a switch hides what was shown. */
class StatusSinksTest {

    static final class RecordingSink implements StatusLineSink {
        final List<String> calls = new ArrayList<String>();

        public void show(List<UiStatusPart> parts, String textLine) {
            calls.add("show " + textLine);
        }

        public void hide() {
            calls.add("hide");
        }
    }

    /** An SDK popup whose models are unavailable or whose writes fail: every call throws. */
    static final class ThrowingSink implements StatusLineSink {
        final List<String> calls = new ArrayList<String>();
        boolean throwing = true;

        public void show(List<UiStatusPart> parts, String textLine) {
            calls.add("show " + textLine);
            if (throwing) {
                throw new IllegalStateException("status popup models unavailable (no SDK Car bank)");
            }
        }

        public void hide() {
            calls.add("hide");
            if (throwing) {
                throw new IllegalStateException("status popup models unavailable (no SDK Car bank)");
            }
        }
    }

    static List<UiStatusPart> parts(String text) {
        return Collections.singletonList(new UiStatusPart(text, null, false));
    }

    @Test
    void fallsBackToPopup65WhenThePopupFailsAndNeverWritesItOtherwise() {
        RecordingSink sdk = new RecordingSink();
        RecordingSink p65 = new RecordingSink();
        int[] state = {1};
        StatusSinks s = new StatusSinks(sdk, p65, () -> state[0]);
        s.show(parts("TS up"), "TS up");
        assertEquals(Arrays.asList("show TS up"), sdk.calls);
        assertTrue(p65.calls.isEmpty());
        state[0] = -1;
        s.show(parts("TS up"), "TS up");
        assertEquals(Arrays.asList("show TS up"), p65.calls);
        assertEquals(Arrays.asList("show TS up", "hide"), sdk.calls);
        state[0] = 2; // the state never goes back from -1 in production; even if it did, 609600 still works here
        s.show(parts("TS dn"), "TS dn");
        assertEquals(Arrays.asList("show TS up", "hide", "show TS dn"), sdk.calls);
        assertEquals(Arrays.asList("show TS up", "hide"), p65.calls, "popup 65 is never written while 609600 works");
    }

    @Test
    void aThrowingSdkPopupFallsBackToPopup65InTheSameCallForTheBoot() {
        ThrowingSink sdk = new ThrowingSink();
        RecordingSink p65 = new RecordingSink();
        RingLog log = new RingLog(16);
        int[] state = {2};
        StatusSinks s = new StatusSinks(sdk, p65, () -> state[0], log);
        assertEquals(1, s.active());
        s.show(parts("SAFE PDC R OFF"), "SAFE PDC R OFF"); // must not throw
        assertEquals(Arrays.asList("show SAFE PDC R OFF"), p65.calls, "the same call reaches popup 65");
        assertEquals(0, s.active(), "the SDK sink is failed for the boot, though the state is still 2");

        sdk.throwing = false; // sticky: even a popup that would work now is not used again this boot
        s.show(parts("SAFE"), "SAFE");
        assertEquals(Arrays.asList("show SAFE PDC R OFF", "show SAFE"), p65.calls);
        assertEquals(Arrays.asList("show SAFE PDC R OFF"), sdk.calls, "never shown on again");
        s.hide();
        assertEquals("hide", p65.calls.get(p65.calls.size() - 1));

        s.show(parts("A"), "A");
        int logged = 0;
        for (String line : log.snapshot()) {
            if (line.contains("status popup")) {
                logged++;
            }
        }
        assertEquals(1, logged, "logged once: " + log.snapshot());
    }

    @Test
    void aThrowingSdkPopupThatWasShownIsHiddenQuietlyOnTheSwitch() {
        ThrowingSink sdk = new ThrowingSink();
        sdk.throwing = false;
        RecordingSink p65 = new RecordingSink();
        int[] state = {1};
        StatusSinks s = new StatusSinks(sdk, p65, () -> state[0]);
        s.show(parts("A"), "A");
        sdk.throwing = true; // a later model write fails
        s.show(parts("B"), "B");
        assertEquals(Arrays.asList("show B"), p65.calls);
        s.hide(); // the failed SDK sink is not hidden again, and nothing throws
        assertEquals(Arrays.asList("show B", "hide"), p65.calls);
    }

    @Test
    void bootWindowShowsOnPopup65ThenTheSwitchHidesIt() {
        RecordingSink sdk = new RecordingSink();
        RecordingSink p65 = new RecordingSink();
        int[] state = {0};
        StatusSinks s = new StatusSinks(sdk, p65, () -> state[0]);
        assertEquals(0, s.active());
        s.show(parts("A"), "A");
        state[0] = 1;
        assertEquals(1, s.active());
        s.show(parts("A"), "A");
        state[0] = 2;
        s.show(parts("B"), "B");
        assertEquals(Arrays.asList("show A", "hide"), p65.calls);
        assertEquals(Arrays.asList("show A", "show B"), sdk.calls, "1 and 2 are both the SDK popup");
    }

    @Test
    void aSinkTheSdkNeverShowedIsNeverHidden() {
        RecordingSink sdk = new RecordingSink();
        RecordingSink p65 = new RecordingSink();
        int[] state = {0};
        StatusSinks s = new StatusSinks(sdk, p65, () -> state[0]);
        s.show(parts("A"), "A");
        s.hide();
        state[0] = 2;
        s.show(parts("B"), "B");
        assertEquals(Arrays.asList("show A", "hide"), p65.calls, "hidden once, not again at the switch");
        assertEquals(Arrays.asList("show B"), sdk.calls);
        s.hide();
        assertEquals(Arrays.asList("show B", "hide"), sdk.calls);
        assertEquals(2, p65.calls.size());
    }
}
