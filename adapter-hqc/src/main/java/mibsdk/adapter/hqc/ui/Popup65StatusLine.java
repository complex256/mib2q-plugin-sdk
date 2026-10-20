package mibsdk.adapter.hqc.ui;

import de.audi.atip.base.IFrameworkAccess;
import de.audi.atip.hmi.IHMIServiceApp;
import mibsdk.platform.UiText;

/**
 * The status line on stock popup {@link HqcUiIds#POPUP_STATUS}: its text is system label model
 * {@link HqcUiIds#STATUS_LABEL_MODEL}, and system choice model {@link HqcUiIds#STATUS_VISIBLE_CHOICE} shows it (1)
 * or hides it (0). App-side model writes (thread-safe, marshalled to HMIEvent by the models), made on
 * {@code mibsdk-ui}. Holds no clock and no thread: the re-assert lives in {@link UiApplier}.
 */
final class Popup65StatusLine implements StatusLineSink {

    private final Object frameworkAccess;

    /** {@code frameworkAccess}: the {@code de.audi.atip.base.IFrameworkAccess} service. */
    Popup65StatusLine(Object frameworkAccess) {
        this.frameworkAccess = frameworkAccess;
    }

    public void show(String text) {
        IHMIServiceApp app = app();
        app.getLabelModel(HqcUiIds.STATUS_LABEL_MODEL).setText(UiText.clean(text, HqcUiIds.STATUS_MAX_CHARS));
        app.getChoiceModel(HqcUiIds.STATUS_VISIBLE_CHOICE).setValue(1);
    }

    public void hide() {
        app().getChoiceModel(HqcUiIds.STATUS_VISIBLE_CHOICE).setValue(0);
    }

    private IHMIServiceApp app() {
        return ((IFrameworkAccess) frameworkAccess).getHmiServiceApp();
    }
}
