package mibsdk.adapter.hqc.ui;

import de.audi.atip.hmi.modelaccess.ChoiceModelGUI;
import de.esolutions.hmi.widgets.audi.evo.widgets.PartialPopupController;
import mibsdk.adapter.hqc.SdkGlobals;

/**
 * The controller of the status popup {@link HqcUiIds#POPUP_SDK_STATUS}: a stock PartialPopupController with one
 * safety fallback (plan F1). The toast {@link HqcUiIds#POPUP_SDK_TOAST} has its own slot, so it never displaces the
 * status line; if the popup manager nevertheless hides 609600 while its visibility model still says shown (1) and a
 * toast is up ({@link SdkToastPopup#up}), the toast is taken to be the cause: toasts are disabled for the boot
 * ({@code SdkGlobals.toastPopup} := -1, sticky), the toast is hidden, and then the stock hide runs. A hide the SDK
 * asked for (the model written 0) never disables toasts. HMIEvent (the popup manager); reads volatile state only,
 * never throws from the SDK part.
 */
final class StatusPopupController extends PartialPopupController {

    StatusPopupController() {
        super(HqcUiIds.POPUP_SDK_STATUS);
    }

    public int hide(int style) {
        try {
            disableToastsIfCovered();
        } catch (Throwable t) {
            // the stock hide still runs
        }
        return super.hide(style);
    }

    private void disableToastsIfCovered() {
        if (SdkGlobals.toastPopup == -1 || !SdkToastPopup.up) {
            return;
        }
        Object m = model;
        if (!(m instanceof ChoiceModelGUI) || ((ChoiceModelGUI) m).getValue() != 1) {
            return; // the SDK hid the line itself (or no model): not the toast's doing
        }
        SdkCarScreenFactory.toastState(-1, "disabled: status popup hidden");
        if (popupManager != null) {
            popupManager.hidePopup(HqcUiIds.POPUP_SDK_TOAST); // mibsdk-ui writes its visibility 0 on its next pass
        }
    }
}
