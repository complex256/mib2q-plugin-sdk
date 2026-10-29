package mibsdk.adapter.hqc.ui;

import java.util.List;
import mibsdk.platform.UiStatusPart;
import mibsdk.runtime.RingLog;

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
 * <p><b>A throwing SDK popup</b> (its models missing, or a model write failing) does not work either: the first throw
 * marks the SDK sink failed for the boot (sticky, like a -1 state), is logged once, and the same call shows the line
 * on popup 65, so SAFE and PDC R OFF are never lost.
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
    private static final int BAR = 2;

    private final BarStatusLine bar; // nullable: the built-in bar when it is attached
    private final StatusLineSink[] sinks;
    private final State state;
    private final RingLog log; // null in tests that do not check it
    /** Guards the flags and the sink calls: {@link #show}/{@link #hide} on {@code mibsdk-ui}, the attach callback on HMIEvent. */
    private final Object lock = new Object();
    private final boolean[] shown = new boolean[2]; // the SDK showed text on the sink, and has not hidden it
    private boolean sdkFailed; // the SDK sink threw: popup 65 for the rest of the boot
    private boolean barShown; // the bar got this show

    StatusSinks(StatusLineSink sdkPopup, StatusLineSink popup65, State state) {
        this(null, sdkPopup, popup65, state, null);
    }

    StatusSinks(StatusLineSink sdkPopup, StatusLineSink popup65, State state, RingLog log) {
        this(null, sdkPopup, popup65, state, log);
    }

    StatusSinks(BarStatusLine bar, StatusLineSink sdkPopup, StatusLineSink popup65, State state, RingLog log) {
        this.bar = bar;
        this.sinks = new StatusLineSink[] {popup65, sdkPopup};
        this.state = state;
        this.log = log;
        if (bar != null) {
            bar.setOnAttached(new Runnable() {
                public void run() {
                    attached(); // HMIEvent: the bar now carries the line, so the popup must go
                }
            });
        }
    }

    /** The sink the next show goes to: 0 popup 65, 1 the SDK popup, 2 the built-in bar. */
    int active() {
        if (bar != null && bar.available() && bar.enabled()) {
            return BAR;
        }
        if (sdkFailed) {
            return POPUP65;
        }
        int s = state.get();
        return s == 1 || s == 2 ? SDK : POPUP65;
    }

    public void show(List<UiStatusPart> parts, String textLine) {
        synchronized (lock) {
            showLocked(parts, textLine);
        }
    }

    private void showLocked(List<UiStatusPart> parts, String textLine) {
        if (bar != null && bar.enabled()) {
            // Always try: while unattached the bar posts its attach job and throws, so this call falls back to the popup
            // and the next one retries. Gating on available() would never post the job at all.
            try {
                bar.show(parts, textLine);
                hideShown(POPUP65);
                hideShown(SDK);
                barShown = true;
                return;
            } catch (Throwable t) {
                barShown = false; // not attached yet: this show goes to the popup, the next one retries
            }
        } else if (bar != null && barShown) {
            barShown = false; // the user switched the bar off: hide its row, the popup carries the line
            bar.hide();
        }
        if (active() == SDK) {
            hideShown(POPUP65); // a switch: popup 65, if the SDK showed text on it
            try {
                sinks[SDK].show(parts, textLine);
                shown[SDK] = true;
                return;
            } catch (Throwable t) {
                sdkFailed = true; // sticky for the boot; logged once, as it happens only once
                if (log != null) {
                    log.append("ERROR", "mibsdk", "ui status popup " + HqcUiIds.POPUP_SDK_STATUS + " failed, the status"
                            + " line goes to popup " + HqcUiIds.POPUP_STATUS + " for this boot: " + t);
                }
            }
        }
        hideShown(SDK); // a switch: the SDK popup, if it showed text (quietly, if it fails)
        sinks[POPUP65].show(parts, textLine);
        shown[POPUP65] = true;
    }

    /** Hides every sink the SDK showed text on. */
    public void hide() {
        synchronized (lock) {
            if (barShown && bar != null) {
                barShown = false;
                bar.hide();
            }
            hideShown(POPUP65);
            hideShown(SDK);
        }
    }

    /**
     * HMIEvent, from the bar's attach job: the bar now carries the line, so hide the popups it took over from. This
     * closes the window where the attach update and the popup were both visible (the first show had to fall back).
     */
    void attached() {
        synchronized (lock) {
            hideShown(POPUP65);
            hideShown(SDK);
        }
    }

    private void hideShown(int k) {
        if (shown[k]) {
            shown[k] = false;
            if (k == SDK && sdkFailed) {
                try {
                    sinks[k].hide(); // best effort: the failed popup may still show old text
                } catch (Throwable ignored) {
                    // already logged as failed; popup 65 carries the line
                }
            } else {
                sinks[k].hide();
            }
        }
    }
}
