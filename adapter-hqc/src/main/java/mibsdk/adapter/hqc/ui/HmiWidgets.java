package mibsdk.adapter.hqc.ui;

import de.esolutions.hmi.widgets.audi.evo.widgets.menu.MenuItemController;

/**
 * Applies an {@link HmiWidgetState} to the stock widgets, on HMIEvent (the applier's HMI job): only what differs from
 * the state applied last. Drawer rows get their bitmaps through {@code setBitmaps}; the change shows at the next Car
 * screen connect (Boot A I3), and nothing tries to force it. A drawer built later takes its bitmaps from
 * {@code SdkGlobals.uiSlots} itself ({@link SdkCarScreenFactory}).
 *
 * <p>The status popup ({@link SdkStatusPopup#current}, once built) gets its cells' bounds when the layout's geometry
 * changed, and the id bitmaps of each cell whose built-in icon changed (so a same-length swap of icons is drawn). A
 * popup built later starts from {@link SdkStatusPopup#wanted} itself.
 */
final class HmiWidgets {

    private volatile HmiWidgetState applied = HmiWidgetState.EMPTY;
    /**
     * A never-set row's bitmaps (the S1f no-icon state), captured once from a fresh {@link MenuItemController} on
     * HMIEvent; a row that loses its icon gets a copy of it back. HMIEvent only.
     */
    private int[] fresh;
    private boolean freshKnown;

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
            applyStatus(want.status(), last.status());
            MenuItemController[] rows = SdkCarScreenFactory.sdkRows;
            for (int slot = 0; rows != null && slot < rows.length && slot < HqcUiIds.ROW_SLOTS; slot++) {
                if (!want.sameSlot(slot, last)) {
                    try {
                        int[] b = want.bitmapsAt(slot);
                        rows[slot].setBitmaps(b != null ? b : freshBitmaps());
                    } catch (Throwable t) {
                        // this row keeps its bitmaps; the others go on
                    }
                }
            }
        } catch (Throwable t) {
            // stock widgets stay as they are
        }
    }

    /** The status popup's bounds and id icons; isolated from the rows. */
    private static void applyStatus(StatusLayout want, StatusLayout last) {
        try {
            SdkStatusPopup p = SdkStatusPopup.current;
            if (p == null) {
                return;
            }
            if (!want.sameGeometry(last)) {
                p.place(want);
            }
            if (!want.sameIcons(last)) {
                p.icons(want, last);
            }
        } catch (Throwable t) {
            // the popup keeps its bounds and icons; the rows go on
        }
    }

    /** A copy of a never-set row's bitmaps (null in the stock HMI: AbstractWidget.bitmapIndices starts null). */
    private int[] freshBitmaps() {
        if (!freshKnown) {
            fresh = new MenuItemController().getBitmapIndices();
            freshKnown = true;
        }
        return fresh == null ? null : (int[]) fresh.clone();
    }
}
