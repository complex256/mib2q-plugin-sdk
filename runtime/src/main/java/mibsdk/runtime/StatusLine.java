package mibsdk.runtime;

import java.util.ArrayList;
import java.util.List;
import mibsdk.platform.UiText;

/**
 * The status line: the runtime's own indicators (spec section 6.1: the runtime owns them, so a plugin cannot forget
 * one), then the plugins' items. Pure functions.
 */
final class StatusLine {

    static final String SAFE = "SAFE";
    static final String REAR_PDC_OFF = "PDC R OFF";
    static final String OVERRIDE_PREFIX = "OVR ";

    private StatusLine() {
    }

    /**
     * {@code SAFE} in safe mode, then one per override entry, held or restoring: rear parking sound off
     * ({@code R0} in the value of {@code parking.soundDirections}) is {@code PDC R OFF}, anything else
     * {@code OVR <key>}.
     */
    static List<String> indicators(SdkState s) {
        List<String> out = new ArrayList<String>();
        if (s == null) {
            return out;
        }
        if (s.safeMode) {
            out.add(SAFE);
        }
        for (int i = 0; i < s.overrides.size(); i++) {
            ActiveOverride o = s.overrides.get(i);
            String value = o.activeValue();
            if (ParkingImpl.KEY.equals(o.key()) && value != null && value.indexOf("R0") >= 0) {
                out.add(REAR_PDC_OFF);
            } else {
                out.add(OVERRIDE_PREFIX + o.key());
            }
        }
        return out;
    }

    /** Indicators, then items, joined by one space (empty ones skipped), cleaned and cut to {@code maxChars}. */
    static String compose(List<String> indicators, List<String> items, int maxChars) {
        StringBuffer b = new StringBuffer();
        append(b, indicators);
        append(b, items);
        return UiText.clean(b.toString(), maxChars);
    }

    private static void append(StringBuffer b, List<String> parts) {
        if (parts == null) {
            return;
        }
        for (int i = 0; i < parts.size(); i++) {
            String s = parts.get(i);
            if (s == null || s.length() == 0) {
                continue;
            }
            if (b.length() > 0) {
                b.append(' ');
            }
            b.append(s);
        }
    }
}
