package mibsdk.adapter.hqc.ui;

import java.util.List;
import mibsdk.platform.UiStatusPart;

/**
 * The status line on the SDK's own popup {@link HqcUiIds#POPUP_SDK_STATUS}, with icons. It writes the popup's Car
 * models: the indicator and cell labels (from {@link HqcUiIds#STATUS_LABEL_MODEL_0}), each cell's file-icon locator
 * (from {@link HqcUiIds#STATUS_FILE_ICON_MODEL_1}), then the visibility model
 * {@link HqcUiIds#STATUS_POPUP_VISIBLE_MODEL}. Built-in icons and the cells' bounds are widget state, applied by the
 * HMI job ({@link HmiWidgets}); this writes models only. App-side model writes, on {@code mibsdk-ui} only; a label or
 * locator is written only when its value changed (the first show writes them all).
 */
final class SdkPopupStatusLine implements StatusLineSink {

    /** The popup's models (the Car bank's, {@link SdkCarModelBank#statusModels}); out-of-range indices are ignored. */
    interface Models {
        /** Label {@code i}: 0 the indicators, 1 .. {@link HqcUiIds#STATUS_CELLS} the cells. */
        void label(int i, String text);

        /** Cell {@code i}'s file icon (0 .. {@link HqcUiIds#STATUS_CELLS} - 1): an absolute path, or null for none. */
        void fileIcon(int i, String path);

        void visible(boolean on);
    }

    /** Where the models come from; null while they cannot be had (no SDK bank). */
    interface Source {
        Models get();
    }

    private final Source source;
    // mibsdk-ui only: what the models hold, as this sink wrote them (for the models it wrote them to).
    private Models written;
    private final String[] labels = new String[HqcUiIds.STATUS_CELLS + 1];
    private final String[] files = new String[HqcUiIds.STATUS_CELLS];

    SdkPopupStatusLine(Source source) {
        this.source = source;
    }

    /** Writes the cells' texts and file paths ({@code textLine} is not used: the parts are the same line). */
    public void show(List<UiStatusPart> parts, String textLine) {
        Models m = models();
        StatusLayout l = StatusLayout.of(parts, null, null); // texts and paths only: ids are the job's
        boolean all = m != written;
        written = m;
        for (int i = 0; i < labels.length; i++) {
            String t = i == 0 ? l.indicators().text : l.cell(i - 1).text;
            if (all || !t.equals(labels[i])) {
                m.label(i, t);
                labels[i] = t;
            }
        }
        for (int i = 0; i < files.length; i++) {
            String p = l.cell(i).filePath;
            if (all || !eq(p, files[i])) {
                m.fileIcon(i, p);
                files[i] = p;
            }
        }
        m.visible(true);
    }

    public void hide() {
        models().visible(false);
    }

    private Models models() {
        Models m = source.get();
        if (m == null) {
            throw new IllegalStateException("status popup models unavailable (no SDK Car bank)");
        }
        return m;
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
