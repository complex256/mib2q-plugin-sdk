package mibsdk.adapter.hqc.ui;

import java.util.Arrays;

/**
 * What the SDK wants on its stock widgets, as one immutable value: built on {@code mibsdk-ui}, applied on HMIEvent by
 * {@link HmiWidgets}. Holds each drawer slot's bitmaps (an id pair, or null for none).
 */
final class HmiWidgetState {

    static final HmiWidgetState EMPTY = new HmiWidgetState(new int[0][]);

    private final int[][] slotBitmaps = new int[HqcUiIds.ROW_SLOTS][];

    /** Copies at most {@link HqcUiIds#ROW_SLOTS} entries; a null entry is a slot without bitmaps. */
    HmiWidgetState(int[][] slotBitmaps) {
        for (int i = 0; i < HqcUiIds.ROW_SLOTS && i < slotBitmaps.length; i++) {
            this.slotBitmaps[i] = slotBitmaps[i] == null ? null : (int[]) slotBitmaps[i].clone();
        }
    }

    /** The bitmaps of {@code slot} (a copy), or null. */
    int[] bitmapsAt(int slot) {
        int[] b = slot >= 0 && slot < slotBitmaps.length ? slotBitmaps[slot] : null;
        return b == null ? null : (int[]) b.clone();
    }

    /** True if {@code slot} has the same bitmaps in both. */
    boolean sameSlot(int slot, HmiWidgetState o) {
        return Arrays.equals(slotBitmaps[slot], o.slotBitmaps[slot]);
    }

    public boolean equals(Object o) {
        if (!(o instanceof HmiWidgetState)) {
            return false;
        }
        HmiWidgetState s = (HmiWidgetState) o;
        for (int i = 0; i < slotBitmaps.length; i++) {
            if (!sameSlot(i, s)) {
                return false;
            }
        }
        return true;
    }

    public int hashCode() {
        int h = 0;
        for (int i = 0; i < slotBitmaps.length; i++) {
            h = 31 * h + Arrays.hashCode(slotBitmaps[i]);
        }
        return h;
    }
}
