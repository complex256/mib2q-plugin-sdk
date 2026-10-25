package mibsdk.adapter.hqc.ui;

import java.util.List;
import mibsdk.platform.UiStatusPart;

/**
 * Picks where the status line goes: the SDK popup {@link HqcUiIds#POPUP_SDK_STATUS} (icons and text) while its state
 * is registered (1) or built (2), else stock popup {@link HqcUiIds#POPUP_STATUS} (text only). In production the state
 * is {@code SdkGlobals.statusPopup}, set on stock threads by {@link SdkCarScreenFactory}; a failure (-1) is sticky for
 * the boot, so once the SDK popup works popup 65 is never written again, and once it failed the line stays on popup 65.
 *
 * <p><b>Boot window</b> (no timer): while the state is 0, because the popup manager has not collected the factory's
 * stubs yet, the line goes to popup 65. When the stub is collected the state becomes 1, the next show goes to the SDK
 * popup, and the switch hides popup 65 again.
 *
 * <p>On a switch the old sink is hidden, but only if the SDK showed text on it: popup 65 belongs to stock
 * EngineeringApp until the SDK wrote it. {@code mibsdk-ui} only.
 */
final class StatusSinks implements StatusLineSink {

    /** The SDK popup's state: 0 unknown, 1 registered, 2 built, -1 failed. */
    interface State {
        int get();
    }

    private static final int POPUP65 = 0;
    private static final int SDK = 1;

    private final StatusLineSink[] sinks;
    private final State state;
    private final boolean[] shown = new boolean[2]; // the SDK showed text on the sink, and has not hidden it

    StatusSinks(StatusLineSink sdkPopup, StatusLineSink popup65, State state) {
        this.sinks = new StatusLineSink[] {popup65, sdkPopup};
        this.state = state;
    }

    /** The sink the next show goes to: 0 popup 65, 1 the SDK popup. */
    int active() {
        int s = state.get();
        return s == 1 || s == 2 ? SDK : POPUP65;
    }

    public void show(List<UiStatusPart> parts, String textLine) {
        int want = active();
        hideShown(1 - want); // a switch: the other sink, if the SDK showed text on it
        sinks[want].show(parts, textLine);
        shown[want] = true;
    }

    /** Hides every sink the SDK showed text on. */
    public void hide() {
        hideShown(POPUP65);
        hideShown(SDK);
    }

    private void hideShown(int k) {
        if (shown[k]) {
            shown[k] = false;
            sinks[k].hide();
        }
    }
}
