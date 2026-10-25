package mibsdk.adapter.hqc.ui;

import de.audi.atip.hmi.event.JoystickEvent;
import de.audi.atip.hmi.event.KeyEvent;
import de.audi.atip.hmi.event.WheelButtonEvent;
import de.esolutions.hmi.widgets.audi.base.widgets.AbstractWidgetController;
import de.esolutions.hmi.widgets.audi.evo.IWidgetKeyHandler;
import mibsdk.adapter.hqc.SdkGlobals;

/**
 * Key handler of the main menu's Plugins row (spike C, C2/C3: SpikeWizardKeyHandler). SELECT ({@link HqcUiIds#KEY_ENTER})
 * is consumed and only drops the Plugins row's press (drawer slot 0) into the UI ring; {@code mibsdk-ui} opens the
 * page. No SMM event is fired. Other keys are left alone. Runs on HMIEvent; never throws.
 */
final class MainMenuKeyHandler implements IWidgetKeyHandler {

    public void keyPressed(AbstractWidgetController w, KeyEvent e) {
        try {
            if (e == null || e.getKeyCode() != HqcUiIds.KEY_ENTER) {
                return;
            }
            e.consume(false);
            UiInputs.rowPressed(SdkGlobals.UI, SdkGlobals.uiSlots, 0);
        } catch (Throwable t) {
            // never propagate into the HMI
        }
    }

    public void keyReleased(AbstractWidgetController w, KeyEvent e) {
    }

    public void keyTurned(AbstractWidgetController w, WheelButtonEvent e) {
    }

    public void keyMoved(AbstractWidgetController w, JoystickEvent e) {
    }
}
