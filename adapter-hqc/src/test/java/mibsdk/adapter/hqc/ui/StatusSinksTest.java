package mibsdk.adapter.hqc.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import mibsdk.platform.UiStatusPart;
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
