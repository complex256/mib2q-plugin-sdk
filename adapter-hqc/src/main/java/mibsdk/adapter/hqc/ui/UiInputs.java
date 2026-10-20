package mibsdk.adapter.hqc.ui;

import mibsdk.platform.UiInput;
import mibsdk.platform.UiTable;

/** User input from stock threads (HMIEvent): only ever dropped into {@link UiTable}'s ring. Never throws. */
final class UiInputs {

    private UiInputs() {
    }

    /** An SDK drawer row was pressed: {@code ROW_SELECTED} with the slot's row key; an empty slot is ignored. */
    static void rowPressed(UiTable t, DrawerAssignment a, int slot) {
        try {
            String key = a == null ? null : a.keyAt(slot);
            if (t != null && key != null) {
                t.input(new UiInput(UiInput.ROW_SELECTED, key, null, false));
            }
        } catch (Throwable ignored) {
            // never propagate into the HMI
        }
    }
}
