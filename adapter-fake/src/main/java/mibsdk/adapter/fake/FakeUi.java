package mibsdk.adapter.fake;

import java.util.ArrayList;
import java.util.List;
import mibsdk.platform.PageStack;
import mibsdk.platform.UiEntry;
import mibsdk.platform.UiIcon;
import mibsdk.platform.UiInput;
import mibsdk.platform.UiPage;
import mibsdk.platform.UiPageText;
import mibsdk.platform.UiRow;
import mibsdk.platform.UiSnapshot;
import mibsdk.platform.UiStatusPart;
import mibsdk.platform.UiTable;

/** Host-side renderer and driver for the UI snapshot: lets plugins and the runtime be tested without the car. */
public final class FakeUi {

    private final UiTable table;
    private final PageStack stack = new PageStack(new PageStack.Listener() {
        public void shown(String pageKey) {
            table.input(new UiInput(UiInput.PAGE_SHOWN, pageKey, null, false));
        }

        public void closed(String pageKey) {
            table.input(new UiInput(UiInput.PAGE_CLOSED, pageKey, null, false));
        }
    });
    private long seenOpenSeq;

    public FakeUi(UiTable table) {
        this.table = table;
    }

    /** Car drawer labels in order, Plugins row first; a row with an icon shows its {@linkplain #tag tag} first. */
    public List<String> carRows() {
        List<String> out = new ArrayList<String>();
        for (UiRow r : table.snapshot().carRows()) {
            out.add(tag(r.icon()) + r.label());
        }
        return out;
    }

    /** The text-only status line (no icons). */
    public String statusLine() {
        return table.snapshot().statusLine();
    }

    /** The status line with icons: one string per part, an item's icon {@linkplain #tag tag} before its text. */
    public List<String> statusParts() {
        List<String> out = new ArrayList<String>();
        for (UiStatusPart p : table.snapshot().statusParts()) {
            out.add(tag(p.icon()) + p.text());
        }
        return out;
    }

    /** {@code "[<name>] "} for a stock icon, {@code "[png:<key>] "} for an installed one, {@code ""} for none. */
    private static String tag(UiIcon i) {
        if (i == null) {
            return "";
        }
        return "[" + (i.kind() == UiIcon.FILE ? "png:" + i.key() : i.name()) + "] ";
    }

    /** {@link UiPageText#line} per entry; empty for an unknown page. */
    public List<String> render(String pageKey) {
        List<String> out = new ArrayList<String>();
        UiPage p = table.snapshot().pages().get(pageKey);
        if (p != null) {
            for (UiEntry e : p.entries()) {
                out.add(UiPageText.line(e));
            }
        }
        return out;
    }

    /** Records ROW_SELECTED; the page opens from the next snapshot, like the adapter. */
    public void selectRow(int index) {
        List<UiRow> rows = table.snapshot().carRows();
        if (index >= 0 && index < rows.size()) {
            table.input(new UiInput(UiInput.ROW_SELECTED, rows.get(index).key(), null, false));
        }
    }

    /** LINK: navigates locally; ITEM: ENTRY_SELECTED. */
    public void select(String pageKey, String entryKey) {
        UiEntry e = entry(pageKey, entryKey);
        if (e == null) {
            return;
        }
        if (e.kind() == UiEntry.LINK) {
            sync();
            stack.push(e.targetPageKey(), null, table.snapshot());
        } else if (e.kind() == UiEntry.ITEM) {
            table.input(new UiInput(UiInput.ENTRY_SELECTED, pageKey, entryKey, false));
        }
    }

    /** TOGGLE_REQUESTED with the opposite of the current state. */
    public void toggle(String pageKey, String entryKey) {
        UiEntry e = entry(pageKey, entryKey);
        if (e != null && e.kind() == UiEntry.TOGGLE) {
            table.input(new UiInput(UiInput.TOGGLE_REQUESTED, pageKey, entryKey, !e.on()));
        }
    }

    public void back() {
        sync();
        stack.back();
    }

    /** The visible page key after following the latest openSeq; null when none is open. */
    public String openPageKey() {
        sync();
        return stack.top();
    }

    private void sync() {
        UiSnapshot s = table.snapshot();
        if (s.openSeq() > seenOpenSeq) {
            seenOpenSeq = s.openSeq();
            if (s.openPageKey() != null) {
                stack.open(s.openPageKey(), null);
            }
        }
        stack.reconcile(s);
    }

    private UiEntry entry(String pageKey, String entryKey) {
        UiPage p = table.snapshot().pages().get(pageKey);
        if (p != null) {
            for (UiEntry e : p.entries()) {
                if (entryKey.equals(e.key())) {
                    return e;
                }
            }
        }
        return null;
    }
}
