package mibsdk.adapter.hqc.ui;

import de.esolutions.hmi.widgets.audi.base.widgets.AbstractWidgetController;
import de.esolutions.hmi.widgets.audi.evo.widgets.menu.MenuItemController;
import mibsdk.adapter.hqc.SdkGlobals;

/**
 * Applies an {@link HmiWidgetState} to the stock widgets, on HMIEvent (the applier's HMI job): only what differs from
 * the state applied last. Drawer rows get their bitmaps through {@code setBitmaps}; the change shows at the next Car
 * screen connect (Boot A I3), and nothing tries to force it. A drawer built later takes its bitmaps from
 * {@code SdkGlobals.uiSlots} itself ({@link SdkCarScreenFactory}).
 *
 * <p>The status popup ({@link SdkStatusPopup#current}, once built) gets its cells' bounds when the layout's geometry
 * changed, the id bitmaps of each cell whose built-in icon changed (so a same-length swap of icons is drawn), and the
 * colour indices of each cell whose colour flag changed. After a colour change or a move it asks for one repaint
 * ({@link SdkStatusPopup#redraw}): no model write follows a colour change (bench C1), and a move is only marked dirty
 * (stock AbstractWidgetController.setBounds), while the line is packed to the right edge (bench B9), so a
 * text-length change moves every cell. A popup built later starts from
 * {@link SdkStatusPopup#wanted} itself.
 *
 * <p>The toast popup ({@link SdkToastPopup#current}, once built) gets its icons' place when the toast's text length
 * moved it, its id icon's bitmaps when the built-in icon changed, and both icons' colour indices when the colour flag
 * changed, then one repaint (F5). A hidden toast is left as it is: hiding it is a model write. A popup built later
 * starts from {@link SdkToastPopup#wanted}.
 *
 * <p>The main menu's Plugins row ({@code SdkGlobals.mainMenuRow}, read from SdkGlobals so no System factory class is
 * loaded) gets {@code setVisible} when its wanted visibility changed or screen 46 was rebuilt with a new row; per Boot B
 * the change shows at the next ring re-layout. Each new {@code SdkGlobals.mainMenuWizard} gets
 * {@link MainMenuWrench#apply} once, on the first job that finds it connected; until then {@link #wrenchPending} says
 * so, and the applier posts again on its next pass (never from a job's after-hook: no loop of jobs on HMIEvent).
 */
final class HmiWidgets {

    private volatile HmiWidgetState applied = HmiWidgetState.EMPTY;
    /**
     * A never-set row's bitmaps (the S1f no-icon state), captured once from a fresh {@link MenuItemController} on
     * HMIEvent; a row that loses its icon gets a copy of it back. HMIEvent only.
     */
    private int[] fresh;
    private boolean freshKnown;
    /** The main-menu row whose visibility was applied last, and that visibility. Written on HMIEvent. */
    private volatile Object menuRow;
    private volatile boolean menuRowVisible;
    /** The newest main wizard the wrench was applied to (or given up on). Written on HMIEvent. */
    private volatile Object wrenched;

    /** The state applied last (read by {@code mibsdk-ui} to decide whether a job is needed). */
    HmiWidgetState applied() {
        return applied;
    }

    /** True if the main menu's Plugins row exists and {@code want} differs from what was applied to it. */
    boolean mainMenuRowNeedsJob(HmiWidgetState want) {
        Object row = SdkGlobals.mainMenuRow;
        return row != null && (row != menuRow || want.mainMenuRowVisible() != menuRowVisible);
    }

    /** True if the newest main wizard has not had its wrench yet (it may not be connected yet). */
    boolean wrenchPending() {
        Object w = SdkGlobals.mainMenuWizard;
        return w != null && w != wrenched;
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
            applyToast(want.toast(), last.toast());
            applyMainMenu(want.mainMenuRowVisible());
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

    /** The main menu's Plugins row visibility and the wrench; isolated from the rest, each part from the other. */
    private void applyMainMenu(boolean visible) {
        try {
            Object row = SdkGlobals.mainMenuRow;
            if (row != null && (row != menuRow || visible != menuRowVisible)) {
                menuRow = row; // recorded first: a throwing row is not retried in a loop of jobs
                menuRowVisible = visible;
                ((MenuItemController) row).setVisible(visible);
            }
        } catch (Throwable t) {
            // the row keeps its visibility
        }
        Object w = SdkGlobals.mainMenuWizard;
        try {
            if (w != null && w != wrenched && ((AbstractWidgetController) w).isConnected()) {
                wrenched = w;
                MainMenuWrench.apply(w); // logs and keeps the night-vision icon on failure
            }
        } catch (Throwable t) {
            wrenched = w; // given up on this wizard: the row works without the wrench
        }
    }

    /** The status popup's bounds and id icons; isolated from the rows. */
    private static void applyStatus(StatusLayout want, StatusLayout last) {
        try {
            SdkStatusPopup p = SdkStatusPopup.current;
            if (p == null) {
                return;
            }
            boolean repaint = false;
            if (!want.sameGeometry(last)) {
                p.place(want);
                repaint = true; // stock setBounds marks each moved widget dirty; this repaint paints them (the
                                // label's model write may have painted before the move)
            }
            if (!want.sameIcons(last) && p.icons(want, last)) {
                repaint = true; // a colour change writes no model, so nothing else repaints it (C1)
            }
            if (repaint) {
                p.redraw(); // once per job
            }
        } catch (Throwable t) {
            // the popup keeps its bounds and icons; the rows go on
        }
    }

    /** The toast popup's icon place, id icon and colours; isolated from the rest. */
    private static void applyToast(SdkToastPopup.Part want, SdkToastPopup.Part last) {
        try {
            SdkToastPopup p = SdkToastPopup.current;
            if (p == null || !want.visible || want.sameDrawn(last)) {
                return;
            }
            SdkToastPopup.Part from = last.visible ? last : null; // after a hidden one: set everything again
            boolean repaint = false;
            if (from == null || want.iconX() != from.iconX()) {
                p.place(want);
                repaint = true;
            }
            if (p.icons(want, from)) {
                repaint = true;
            }
            if (repaint) {
                p.redraw(); // once per job
            }
        } catch (Throwable t) {
            // the toast keeps its icon; the rest goes on
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
