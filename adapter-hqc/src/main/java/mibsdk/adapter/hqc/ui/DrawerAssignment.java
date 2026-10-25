package mibsdk.adapter.hqc.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Which Car row sits in which SDK drawer slot: slot 0 is the Plugins row, slots 1..{@link HqcUiIds#PLUGIN_ROW_SLOTS}
 * plugin rows. Immutable: the {@code mibsdk-ui} thread builds a new one with {@link DrawerSlots#assign} and swaps
 * {@code SdkGlobals.uiSlots}; stock threads only read it. Out-of-range slots read as empty.
 */
public final class DrawerAssignment {

    public static final DrawerAssignment EMPTY =
            new DrawerAssignment(new String[0], new String[0], new String[0], new int[0][], new ArrayList<String>());

    private final String[] keys = new String[HqcUiIds.ROW_SLOTS];
    private final String[] labels = new String[HqcUiIds.ROW_SLOTS];
    private final String[] pageKeys = new String[HqcUiIds.ROW_SLOTS];
    private final int[][] bitmaps = new int[HqcUiIds.ROW_SLOTS][];
    private final List<String> overflow;

    /**
     * Copies at most {@link HqcUiIds#ROW_SLOTS} entries of each array (a null key leaves the slot empty). A slot's
     * {@code bitmaps} are its image id pair (icon, reflection), or null for none.
     */
    DrawerAssignment(String[] keys, String[] labels, String[] pageKeys, int[][] bitmaps, List<String> overflow) {
        for (int i = 0; i < HqcUiIds.ROW_SLOTS && i < keys.length; i++) {
            if (keys[i] != null) {
                this.keys[i] = keys[i];
                this.labels[i] = i < labels.length ? labels[i] : null;
                this.pageKeys[i] = i < pageKeys.length ? pageKeys[i] : null;
                this.bitmaps[i] = i < bitmaps.length && bitmaps[i] != null ? (int[]) bitmaps[i].clone() : null;
            }
        }
        this.overflow = Collections.unmodifiableList(new ArrayList<String>(overflow));
    }

    /** The row key in {@code slot}, or null. */
    public String keyAt(int slot) {
        return inRange(slot) ? keys[slot] : null;
    }

    /** The row label in {@code slot}, or null. */
    public String labelAt(int slot) {
        return inRange(slot) ? labels[slot] : null;
    }

    /** The page the row in {@code slot} opens, or null. */
    public String pageKeyAt(int slot) {
        return inRange(slot) ? pageKeys[slot] : null;
    }

    /** The image id pair of the row in {@code slot} (a copy), or null (no icon). */
    public int[] iconAt(int slot) {
        int[] b = inRange(slot) ? bitmaps[slot] : null;
        return b == null ? null : (int[]) b.clone();
    }

    /** True if a row is assigned to {@code slot}. */
    public boolean visibleAt(int slot) {
        return keyAt(slot) != null;
    }

    /** Keys of the plugin rows that found no slot, in row order. */
    public List<String> overflow() {
        return overflow;
    }

    private static boolean inRange(int slot) {
        return slot >= 0 && slot < HqcUiIds.ROW_SLOTS;
    }
}
