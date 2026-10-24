package mibsdk.runtime;

import java.util.ArrayList;
import java.util.List;
import mibsdk.platform.UiText;

/**
 * The status line: the runtime's own indicators (spec section 6.1: the runtime owns them, so a plugin cannot forget
 * one), then the plugins' items. Pure functions.
 *
 * <p>Priority: {@code SAFE} first, then {@code PDC R OFF}, then the other overrides. Indicators never crowd out the
 * plugin items: while there are items, indicators after the first get at most the line minus the items' share (the
 * items' length, at most half the line); the rest are dropped (the SDK page lists every override).
 */
final class StatusLine {

    static final String SAFE = "SAFE";
    static final String REAR_PDC_OFF = "PDC R OFF";
    static final String OVERRIDE_PREFIX = "OVR ";
    /** Parking keys of any other kind show as {@code OVR PDC}. */
    static final String PARKING_PREFIX = "parking.";
    /** Other keys show their last dot-separated segment, cut to this many characters. */
    static final int MAX_KEY_CHARS = 8;

    private StatusLine() {
    }

    /**
     * {@code SAFE} in safe mode; {@code PDC R OFF} for a held or restoring {@code parking.soundDirections} entry with
     * rear off ({@code R0}); then one short {@code OVR <name>} per other entry: {@code OVR PDC} for {@code parking.*},
     * else the key's last segment (at most {@link #MAX_KEY_CHARS}). No duplicates.
     */
    static List<String> indicators(SdkState s) {
        List<String> out = new ArrayList<String>();
        if (s == null) {
            return out;
        }
        if (s.safeMode) {
            out.add(SAFE);
        }
        List<String> others = new ArrayList<String>();
        for (int i = 0; i < s.overrides.size(); i++) {
            ActiveOverride o = s.overrides.get(i);
            String key = o.key() == null ? "" : o.key();
            String value = o.activeValue();
            if (ParkingImpl.KEY.equals(key) && value != null && value.indexOf("R0") >= 0) {
                addOnce(out, REAR_PDC_OFF);
            } else if (key.startsWith(PARKING_PREFIX)) {
                addOnce(others, OVERRIDE_PREFIX + "PDC");
            } else {
                String last = key.substring(key.lastIndexOf('.') + 1);
                addOnce(others, OVERRIDE_PREFIX + (last.length() > MAX_KEY_CHARS ? last.substring(0, MAX_KEY_CHARS) : last));
            }
        }
        for (int i = 0; i < others.size(); i++) {
            addOnce(out, others.get(i));
        }
        return out;
    }

    /**
     * Indicators (the first always; the others while they fit their budget, see the class comment), then items,
     * joined by one space (empty ones skipped), cleaned and cut to {@code maxChars}.
     */
    static String compose(List<String> indicators, List<String> items, int maxChars) {
        StringBuffer it = new StringBuffer();
        append(it, items);
        int reserve = it.length() == 0 ? 0 : Math.min(it.length() + 1, maxChars / 2);
        int budget = maxChars - reserve;
        StringBuffer b = new StringBuffer();
        if (indicators != null) {
            for (int i = 0; i < indicators.size(); i++) {
                String s = indicators.get(i);
                if (s == null || s.length() == 0) {
                    continue;
                }
                int len = b.length() + (b.length() > 0 ? 1 : 0) + s.length();
                if (b.length() > 0 && len > budget) {
                    break;
                }
                if (b.length() > 0) {
                    b.append(' ');
                }
                b.append(s);
            }
        }
        if (it.length() > 0) {
            if (b.length() > 0) {
                b.append(' ');
            }
            b.append(it.toString());
        }
        return UiText.clean(b.toString(), maxChars);
    }

    private static void addOnce(List<String> list, String s) {
        if (!list.contains(s)) {
            list.add(s);
        }
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
