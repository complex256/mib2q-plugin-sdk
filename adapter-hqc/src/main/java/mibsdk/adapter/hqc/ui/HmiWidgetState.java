package mibsdk.adapter.hqc.ui;

import java.util.Arrays;

/**
 * What the SDK wants on its stock widgets, as one immutable value: built on {@code mibsdk-ui}, applied on HMIEvent by
 * {@link HmiWidgets}. Holds each drawer slot's bitmaps (an id pair, or null for none) and the status popup's
 * {@link StatusLayout}, of which only the bounds and the built-in icon ids are widget state (texts and file paths are
 * models, written by {@link SdkPopupStatusLine}).
 */
final class HmiWidgetState {

    static final HmiWidgetState EMPTY = new HmiWidgetState(new int[0][]);

    private final int[][] slotBitmaps = new int[HqcUiIds.ROW_SLOTS][];
    private final StatusLayout status;

    /** No status line ({@link StatusLayout#EMPTY}). */
    HmiWidgetState(int[][] slotBitmaps) {
        this(slotBitmaps, StatusLayout.EMPTY);
    }

    /** Copies at most {@link HqcUiIds#ROW_SLOTS} entries; a null entry is a slot without bitmaps. */
    HmiWidgetState(int[][] slotBitmaps, StatusLayout status) {
        for (int i = 0; i < HqcUiIds.ROW_SLOTS && i < slotBitmaps.length; i++) {
            this.slotBitmaps[i] = slotBitmaps[i] == null ? null : (int[]) slotBitmaps[i].clone();
        }
        this.status = status == null ? StatusLayout.EMPTY : status;
    }

    /** The same slots with {@code layout}. */
    HmiWidgetState withStatus(StatusLayout layout) {
        return new HmiWidgetState(slotBitmaps, layout);
    }

    /** The status popup's layout. */
    StatusLayout status() {
        return status;
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

    /** True if every drawer slot has the same bitmaps in both (the status layout aside). */
    boolean sameRows(HmiWidgetState o) {
        for (int i = 0; i < slotBitmaps.length; i++) {
            if (!sameSlot(i, o)) {
                return false;
            }
        }
        return true;
    }

    /** Same rows, and a status layout with the same geometry and built-in icons (texts and paths are models). */
    public boolean equals(Object o) {
        if (!(o instanceof HmiWidgetState)) {
            return false;
        }
        HmiWidgetState s = (HmiWidgetState) o;
        return status.sameGeometry(s.status) && status.sameIcons(s.status) && sameRows(s);
    }

    /** Hashes the slots only: equal states have equal slots (the status layout compares by bounds and ids). */
    public int hashCode() {
        int h = 0;
        for (int i = 0; i < slotBitmaps.length; i++) {
            h = 31 * h + Arrays.hashCode(slotBitmaps[i]);
        }
        return h;
    }
}
