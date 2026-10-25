package mibsdk.adapter.hqc.ui;

import java.util.List;
import mibsdk.platform.UiStatusPart;

/** The one-line status display. Called only on the {@code mibsdk-ui} thread. */
interface StatusLineSink {

    /**
     * Shows the line: {@code parts} (indicators, then each item with its icon) for a display that draws icons,
     * {@code textLine} (the same line as text only) for one that does not.
     */
    void show(List<UiStatusPart> parts, String textLine);

    void hide();
}
