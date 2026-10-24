package mibsdk.adapter.hqc.ui;

import mibsdk.platform.UiInput;
import mibsdk.platform.UiTable;
import mibsdk.runtime.Clock;

/**
 * User input from stock threads (HMIEvent): only ever dropped into {@link UiTable}'s ring, plus the monotonic time of
 * the last press on an SDK drawer row or SDK page entry (an SDK page may open only on a page, or right after such a
 * press). Never throws.
 */
final class UiInputs {

    /** The monotonic clock presses are timed on; set by {@code UiApplier.create}. Null: presses are not recorded. */
    static volatile Clock clock;
    private static volatile long lastPressAt;
    private static volatile boolean pressed;

    private UiInputs() {
    }

    /** An SDK drawer row was pressed: {@code ROW_SELECTED} with the slot's row key; an empty slot is ignored. */
    static void rowPressed(UiTable t, DrawerAssignment a, int slot) {
        try {
            String key = a == null ? null : a.keyAt(slot);
            if (t != null && key != null) {
                userPressed();
                t.input(new UiInput(UiInput.ROW_SELECTED, key, null, false));
            }
        } catch (Throwable ignored) {
            // never propagate into the HMI
        }
    }

    /** Records a user press on SDK UI (a drawer row, a page entry). Never throws. */
    static void userPressed() {
        try {
            Clock c = clock;
            if (c != null) {
                lastPressAt = c.uptimeMs();
                pressed = true;
            }
        } catch (Throwable ignored) {
            // no record: an open that needs it is dropped
        }
    }

    /** Milliseconds since the last SDK press, or {@code Long.MAX_VALUE} if none was recorded. Never throws. */
    static long sinceLastPress() {
        try {
            Clock c = clock;
            if (c == null || !pressed) {
                return Long.MAX_VALUE;
            }
            return c.uptimeMs() - lastPressAt;
        } catch (Throwable t) {
            return Long.MAX_VALUE;
        }
    }

    /** Tests: forget the last press. */
    static void forgetForTests() {
        pressed = false;
        clock = null;
    }
}
