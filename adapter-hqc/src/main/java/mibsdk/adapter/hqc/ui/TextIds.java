package mibsdk.adapter.hqc.ui;

/**
 * The SDK drawer rows' text ids: {@code base + slot}, one per slot. The factory's {@code getText} serves them from the
 * current {@link DrawerAssignment} (labels update live; model-bound {@code %1} labels do not render on this HMI).
 * Stock-thread safe: no lock, no allocation beyond the answer, never throws for any id.
 */
final class TextIds {

    private final int base;

    TextIds(int base) {
        this.base = base;
    }

    int idFor(int slot) {
        return base + slot;
    }

    /** The label for an SDK text id ("" for an empty slot), or null if {@code id} is not one of ours. */
    String textFor(int id, DrawerAssignment a) {
        int slot = id - base;
        if (slot < 0 || slot >= HqcUiIds.ROW_SLOTS) {
            return null;
        }
        String label = a == null ? null : a.labelAt(slot);
        return label == null ? "" : label;
    }
}
