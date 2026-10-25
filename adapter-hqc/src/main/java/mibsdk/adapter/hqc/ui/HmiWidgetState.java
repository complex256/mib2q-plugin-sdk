package mibsdk.adapter.hqc.ui;

import java.util.Arrays;

/**
 * What the SDK wants on its stock widgets, as one immutable value: built on {@code mibsdk-ui}, applied on HMIEvent by
 * {@link HmiWidgets}. Holds each drawer slot's bitmaps (an id pair, or null for none), the status popup's
 * {@link StatusLayout}, of which only the bounds and the built-in icon ids are widget state (texts and file paths are
 * models, written by {@link SdkPopupStatusLine}), and whether the main menu's Plugins row is visible
 * ({@code SdkGlobals.mainMenuRow}: the UI is not down and the main menu is ACTIVE).
 */
final class HmiWidgetState {

    static final HmiWidgetState EMPTY = new HmiWidgetState(new int[0][]);

    private final int[][] slotBitmaps = new int[HqcUiIds.ROW_SLOTS][];
    private final StatusLayout status;
    private final boolean mainMenuRowVisible;

    /** No status line ({@link StatusLayout#EMPTY}). */
    HmiWidgetState(int[][] slotBitmaps) {
        this(slotBitmaps, StatusLayout.EMPTY);
    }

    /** Copies at most {@link HqcUiIds#ROW_SLOTS} entries; a null entry is a slot without bitmaps. */
    HmiWidgetState(int[][] slotBitmaps, StatusLayout status) {
        this(slotBitmaps, status, false);
    }

    /** {@code mainMenuRowVisible}: the main menu's Plugins row is wanted visible. */
    HmiWidgetState(int[][] slotBitmaps, StatusLayout status, boolean mainMenuRowVisible) {
        for (int i = 0; i < HqcUiIds.ROW_SLOTS && i < slotBitmaps.length; i++) {
            this.slotBitmaps[i] = slotBitmaps[i] == null ? null : (int[]) slotBitmaps[i].clone();
        }
        this.status = status == null ? StatusLayout.EMPTY : status;
        this.mainMenuRowVisible = mainMenuRowVisible;
    }

    /** The same slots and main-menu row with {@code layout}. */
    HmiWidgetState withStatus(StatusLayout layout) {
        return new HmiWidgetState(slotBitmaps, layout, mainMenuRowVisible);
    }

    /** The same slots and status layout; the main menu's Plugins row wanted visible iff {@code visible}. */
    HmiWidgetState withMainMenuRow(boolean visible) {
        return visible == mainMenuRowVisible ? this : new HmiWidgetState(slotBitmaps, status, visible);
    }

    /** Whether the main menu's Plugins row is wanted visible. */
    boolean mainMenuRowVisible() {
        return mainMenuRowVisible;
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

    /**
     * Same rows, and a status layout with the same geometry and built-in icons (texts and paths are models). The
     * main-menu row is left out: {@link HmiWidgets} compares it with the row it applied it to (screen 46 may be rebuilt).
     */
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
