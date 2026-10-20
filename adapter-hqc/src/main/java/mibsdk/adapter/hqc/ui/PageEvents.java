package mibsdk.adapter.hqc.ui;

import mibsdk.platform.PageStack;
import mibsdk.platform.UiInput;
import mibsdk.platform.UiTable;

/**
 * The SDK page stack's events, sent to the runtime: {@code PAGE_CLOSED} for the old top, then {@code PAGE_SHOWN} for
 * the new one, on every top change (exactly one SDK page is open at a time; leaving SDK pages closes the last).
 * Runs on HMIEvent: {@link UiTable#input} only, which never throws or blocks.
 */
final class PageEvents implements PageStack.Listener {

    private final UiTable table;

    PageEvents(UiTable table) {
        this.table = table;
    }

    public void shown(String pageKey) {
        table.input(new UiInput(UiInput.PAGE_SHOWN, pageKey, null, false));
    }

    public void closed(String pageKey) {
        table.input(new UiInput(UiInput.PAGE_CLOSED, pageKey, null, false));
    }
}
