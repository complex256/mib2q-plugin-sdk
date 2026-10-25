package mibsdk.adapter.hqc.ui;

import de.audi.atip.base.IFrameworkAccess;
import de.audi.atip.hmi.IHMIServiceApp;
import java.util.List;
import mibsdk.platform.UiStatusPart;
import mibsdk.platform.UiText;

/**
 * The status line's fallback, text only ({@link StatusSinks}): used only while the SDK popup
 * {@link HqcUiIds#POPUP_SDK_STATUS} is not registered yet (the boot window) or could not be registered or built. On
 * stock popup {@link HqcUiIds#POPUP_STATUS}: its text is system label model
 * {@link HqcUiIds#STATUS_LABEL_MODEL}, and system choice model {@link HqcUiIds#STATUS_VISIBLE_CHOICE} shows it (1)
 * or hides it (0). App-side model writes (thread-safe, marshalled to HMIEvent by the models), made on
 * {@code mibsdk-ui}. Holds no clock and no thread: the re-assert lives in {@link UiApplier}.
 */
final class Popup65StatusLine implements StatusLineSink {

    /**
     * The shortest length kept (one char plus "..."): only a guard for a direct caller. The runtime never passes less
     * than its own floor, {@code StatusLine.MIN_LINE_CHARS} (14, the length of {@code "SAFE PDC R OFF"}).
     */
    static final int MIN_CHARS = 4;

    private final Object frameworkAccess;
    private final int maxChars;

    /**
     * {@code frameworkAccess}: the {@code de.audi.atip.base.IFrameworkAccess} service. {@code maxChars}:
     * {@code ui.status.max.chars}, clamped to {@link #MIN_CHARS}..{@link HqcUiIds#STATUS_MAX_CHARS} (what the popup
     * fits).
     */
    Popup65StatusLine(Object frameworkAccess, int maxChars) {
        this.frameworkAccess = frameworkAccess;
        this.maxChars = Math.min(HqcUiIds.STATUS_MAX_CHARS, Math.max(MIN_CHARS, maxChars));
    }

    /** Shows {@code textLine}; popup 65 draws no icons, so {@code parts} are not used. */
    public void show(List<UiStatusPart> parts, String textLine) {
        IHMIServiceApp app = app();
        app.getLabelModel(HqcUiIds.STATUS_LABEL_MODEL).setText(UiText.clean(textLine, maxChars));
        app.getChoiceModel(HqcUiIds.STATUS_VISIBLE_CHOICE).setValue(1);
    }

    public void hide() {
        app().getChoiceModel(HqcUiIds.STATUS_VISIBLE_CHOICE).setValue(0);
    }

    private IHMIServiceApp app() {
        return ((IFrameworkAccess) frameworkAccess).getHmiServiceApp();
    }
}
