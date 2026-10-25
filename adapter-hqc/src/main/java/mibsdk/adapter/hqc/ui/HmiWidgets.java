package mibsdk.adapter.hqc.ui;

import de.esolutions.hmi.widgets.audi.evo.widgets.menu.MenuItemController;

/**
 * Applies an {@link HmiWidgetState} to the stock widgets, on HMIEvent (the applier's HMI job): only what differs from
 * the state applied last. Drawer rows get their bitmaps through {@code setBitmaps}; the change shows at the next Car
 * screen connect (Boot A I3), and nothing tries to force it. A drawer built later takes its bitmaps from
 * {@code SdkGlobals.uiSlots} itself ({@link SdkCarScreenFactory}).
 */
final class HmiWidgets {

    private volatile HmiWidgetState applied = HmiWidgetState.EMPTY;

    /** The state applied last (read by {@code mibsdk-ui} to decide whether a job is needed). */
    HmiWidgetState applied() {
        return applied;
    }

    /**
     * Applies {@code want}. It is recorded as applied first, so a widget that throws is not retried in a loop of jobs.
     * Each row is isolated; never throws.
     */
    void apply(HmiWidgetState want) {
        try {
            HmiWidgetState last = applied;
            applied = want;
            MenuItemController[] rows = SdkCarScreenFactory.sdkRows;
            for (int slot = 0; rows != null && slot < rows.length && slot < HqcUiIds.ROW_SLOTS; slot++) {
                if (!want.sameSlot(slot, last)) {
                    try {
                        rows[slot].setBitmaps(want.bitmapsAt(slot));
                    } catch (Throwable t) {
                        // this row keeps its bitmaps; the others go on
                    }
                }
            }
        } catch (Throwable t) {
            // stock widgets stay as they are
        }
    }
}
