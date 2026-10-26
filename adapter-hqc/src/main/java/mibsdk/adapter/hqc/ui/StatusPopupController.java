package mibsdk.adapter.hqc.ui;

import de.audi.atip.hmi.modelaccess.ChoiceModelGUI;
import de.esolutions.hmi.widgets.audi.evo.widgets.PartialPopupController;
import mibsdk.adapter.hqc.SdkGlobals;

/**
 * The controller of the status popup {@link HqcUiIds#POPUP_SDK_STATUS}: a stock PartialPopupController that logs, once
 * per boot, a stock hide of 609600 while its visibility model still says shown (1) and a toast is up
 * ({@link SdkToastPopup#up}). The toast never causes that: it is in its own slot, and stock hides a popup for another
 * slot's only from slot 4 to slots 1/2 ({@code AbstractPartialPopupManager.checkForSlotDependencies}). The routine
 * passes (REM entry's {@code setPartialPopupsEnabled}, screens that block partial popups, a deregister) do hide it with
 * its model at 1, so the line is only a hint for bench N9: it disables nothing and hides nothing. HMIEvent (the popup
 * manager); reads volatile state only, never throws from the SDK part.
 */
final class StatusPopupController extends PartialPopupController {

    /** Set by the first logged hide; the line is written at most once per boot. */
    static volatile boolean warned;

    StatusPopupController() {
        super(HqcUiIds.POPUP_SDK_STATUS);
    }

    public int hide(int style) {
        try {
            logIfToastUp();
        } catch (Throwable t) {
            // the stock hide still runs
        }
        return super.hide(style);
    }

    private void logIfToastUp() {
        if (warned || !SdkToastPopup.up) {
            return;
        }
        Object m = model;
        if (!(m instanceof ChoiceModelGUI) || ((ChoiceModelGUI) m).getValue() != 1) {
            return; // the SDK hid the line itself (or no model)
        }
        warned = true;
        SdkGlobals.LOG.append("WARN", "mibsdk", "ui status popup " + HqcUiIds.POPUP_SDK_STATUS
                + " hidden by stock while its model is 1: status popup hidden while a toast is up (toasts stay on)");
    }
}
