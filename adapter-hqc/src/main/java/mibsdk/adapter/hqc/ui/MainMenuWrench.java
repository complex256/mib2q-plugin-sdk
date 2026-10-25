package mibsdk.adapter.hqc.ui;

import de.esolutions.hmi.widgets.audi.evo.widgets.MainWizardController;
import mibsdk.adapter.hqc.SdkGlobals;

/**
 * The wrench icon on the main menu's Plugins row (spike C, Boot B: the {@code wrench} probe): entry
 * {@link HqcUiIds#MAIN_MENU_WIDGET_ID} of the layout's main-wizard icon/decorator mapping, stock {11, 11} (night
 * vision), becomes {16, 9} (the unused wrench cell). The array is the layout's own, reached through a connected
 * wizard; the change shows at the next ring redraw.
 *
 * <p>Called by the HMI job (on HMIEvent) once for each new {@link SdkGlobals#mainMenuWizard}, after it is connected.
 * It never touches {@link SdkGlobals#mainMenu}: without the wrench the row still works, with the night-vision icon.
 */
final class MainMenuWrench {

    private static final String SOURCE = "mibsdk";

    /** Set once a refusal was logged: one line per boot. */
    private static volatile boolean warned;

    private MainMenuWrench() {
    }

    /**
     * Puts the wrench on {@code wizard}'s layout mapping if entry 11 is stock (or already the wrench). True if the
     * entry is the wrench afterwards; false (mapping untouched, logged once) otherwise or on any failure. Never throws.
     */
    static boolean apply(Object wizard) {
        try {
            int[][] map = ((MainWizardController) wizard).getInitContext().getLayout()
                    .getMainWizardIconDecoratorMapping();
            int k = HqcUiIds.MAIN_MENU_WIDGET_ID;
            int[] e = map.length > k ? map[k] : null;
            if (is(e, HqcUiIds.WRENCH_ATLAS_CELL, HqcUiIds.WRENCH_DECORATOR)) {
                return true;
            }
            if (is(e, k, k)) {
                map[k] = new int[] {HqcUiIds.WRENCH_ATLAS_CELL, HqcUiIds.WRENCH_DECORATOR};
                return true;
            }
            warnOnce("ui main menu wrench: mapping[" + k + "] is " + describe(e) + " (length " + map.length
                    + "), not stock: left alone");
            return false;
        } catch (Throwable t) {
            warnOnce("ui main menu wrench failed, night-vision icon kept: " + t);
            return false;
        }
    }

    private static boolean is(int[] e, int cell, int decorator) {
        return e != null && e.length == 2 && e[0] == cell && e[1] == decorator;
    }

    private static String describe(int[] e) {
        if (e == null) {
            return "none";
        }
        StringBuffer b = new StringBuffer("{");
        for (int i = 0; i < e.length; i++) {
            b.append(i == 0 ? "" : ", ").append(e[i]);
        }
        return b.append('}').toString();
    }

    private static void warnOnce(String msg) {
        if (warned) {
            return;
        }
        warned = true;
        try {
            SdkGlobals.LOG.append("WARN", SOURCE, msg);
        } catch (Throwable ignored) {
            // logging never breaks the HMI
        }
    }

    /** Tests: log the next refusal again. */
    static void forgetForTests() {
        warned = false;
    }
}
