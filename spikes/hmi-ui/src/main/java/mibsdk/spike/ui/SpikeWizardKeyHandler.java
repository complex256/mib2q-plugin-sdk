package mibsdk.spike.ui;

import de.audi.atip.hmi.event.JoystickEvent;
import de.audi.atip.hmi.event.KeyEvent;
import de.audi.atip.hmi.event.WheelButtonEvent;
import de.esolutions.hmi.widgets.audi.base.widgets.AbstractWidgetController;
import de.esolutions.hmi.widgets.audi.evo.IWidgetKeyHandler;

/**
 * Spike C (C2/C3): key handler of an SDK main-menu row. SELECT (key 17) is consumed, logged, and opens the spike
 * page (SpikePage.postShowDirect posts a RunnableEvent; the page saves the current screen data, screen 46, for
 * BACK). No SMM event is fired. Other keys are left alone.
 */
final class SpikeWizardKeyHandler implements IWidgetKeyHandler {
    private final int n;

    SpikeWizardKeyHandler(int n) {
        this.n = n;
    }

    public void keyPressed(AbstractWidgetController w, KeyEvent e) {
        try {
            if (e == null || e.getKeyCode() != SpikeIds.KEY_ENTER) {
                return;
            }
            e.consume(false);
            SpikeLog.log("wizard-press n=" + n + " thread=" + Thread.currentThread().getName());
            SpikePage.postShowDirect();
        } catch (Throwable t) {
            SpikeLog.log("wizard-press FAILED: " + t);
        }
    }

    public void keyReleased(AbstractWidgetController w, KeyEvent e) {
    }

    public void keyTurned(AbstractWidgetController w, WheelButtonEvent e) {
    }

    public void keyMoved(AbstractWidgetController w, JoystickEvent e) {
    }
}
