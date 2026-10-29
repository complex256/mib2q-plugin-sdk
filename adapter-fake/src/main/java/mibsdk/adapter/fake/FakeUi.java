package mibsdk.adapter.fake;

import java.util.ArrayList;
import java.util.List;
import mibsdk.platform.PageStack;
import mibsdk.platform.UiDialog;
import mibsdk.platform.UiEntry;
import mibsdk.platform.UiIcon;
import mibsdk.platform.UiInput;
import mibsdk.platform.UiPage;
import mibsdk.platform.UiPageText;
import mibsdk.platform.UiRow;
import mibsdk.platform.UiSnapshot;
import mibsdk.platform.UiStatusPart;
import mibsdk.platform.UiTable;
import mibsdk.platform.UiToast;

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
    private long answeredSeq; // the latest dialog answered here: off screen until a newer one
    private long typedSeq; // the speller that typed holds
    private String typed;

    public FakeUi(UiTable table) {
        this.table = table;
    }

    /** Car drawer labels in order, Plugins row first; a row with an icon shows its {@linkplain #tag tag} first. */
    public List<String> carRows() {
        List<String> out = new ArrayList<String>();
        for (UiRow r : table.snapshot().carRows()) {
            out.add(tag(r.icon(), false) + r.label());
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
            out.add(tag(p.icon(), true) + p.text());
        }
        return out;
    }

    /** The text colour of each status part, in the same order as {@link #statusParts()}; null for the bar's own. */
    public List<mibsdk.api.UiColor> statusColours() {
        List<mibsdk.api.UiColor> out = new ArrayList<mibsdk.api.UiColor>();
        for (UiStatusPart p : table.snapshot().statusParts()) {
            out.add(p.colour());
        }
        return out;
    }

    /**
     * {@code "[<name>] "} for a stock icon, {@code "[png:<key>] "} for an installed one, {@code ""} for none. In a status
     * part an own-colour file icon reads {@code "[png:<key>+own] "}; a Car row never shows {@code +own}, because rows
     * ignore the flag (the drawer draws every row icon in its own colours).
     */
    private static String tag(UiIcon i, boolean status) {
        if (i == null) {
            return "";
        }
        if (i.kind() != UiIcon.FILE) {
            return "[" + i.name() + "] ";
        }
        return "[png:" + i.key() + (status && i.ownColours() ? "+own" : "") + "] ";
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

    /** Whether the page is registered (an empty page is registered; {@link #render} is empty for both). */
    public boolean hasPage(String pageKey) {
        return table.snapshot().pages().containsKey(pageKey);
    }

    /** Records ROW_SELECTED; the page opens from the next snapshot, like the adapter. */
    public void selectRow(int index) {
        List<UiRow> rows = table.snapshot().carRows();
        if (index >= 0 && index < rows.size()) {
            table.input(new UiInput(UiInput.ROW_SELECTED, rows.get(index).key(), null, false));
        }
    }

    /**
     * LINK and CHOICE: navigate locally (to the target, or the choice's derived page); ITEM: ENTRY_SELECTED; RADIO:
     * ENTRY_SELECTED, then back to the page the choice is on. A disabled entry sends nothing.
     */
    public void select(String pageKey, String entryKey) {
        UiEntry e = entry(pageKey, entryKey);
        if (e == null || !e.enabled()) {
            return;
        }
        if (e.kind() == UiEntry.LINK || e.kind() == UiEntry.CHOICE) {
            sync();
            stack.push(e.targetPageKey(), null, table.snapshot());
        } else if (e.kind() == UiEntry.ITEM) {
            table.input(new UiInput(UiInput.ENTRY_SELECTED, pageKey, entryKey, false));
        } else if (e.kind() == UiEntry.RADIO) {
            table.input(new UiInput(UiInput.ENTRY_SELECTED, pageKey, entryKey, false));
            back();
        }
    }

    /**
     * CHOICE: opens the choice's sub-page and selects option {@code index} on it, as a user does (open the row, pick
     * the radio, land back on the page). The sub-page key is {@code <pageKey>#<itemId>} and its radio ids are
     * {@code String.valueOf(index)}, so the plugin sees an action for {@code itemId} with value {@code index}.
     * Nothing happens for an unknown or disabled choice.
     */
    public void choose(String pageKey, String itemId, int index) {
        UiEntry e = entry(pageKey, itemId);
        if (e == null || !e.enabled() || e.kind() != UiEntry.CHOICE) {
            return;
        }
        select(pageKey, itemId);
        select(pageKey + "#" + itemId, String.valueOf(index));
    }

    /** TOGGLE_REQUESTED with the opposite of the current state; nothing for a disabled toggle. */
    public void toggle(String pageKey, String entryKey) {
        UiEntry e = entry(pageKey, entryKey);
        if (e != null && e.enabled() && e.kind() == UiEntry.TOGGLE) {
            table.input(new UiInput(UiInput.TOGGLE_REQUESTED, pageKey, entryKey, !e.on()));
        }
    }

    /** DROPDOWN: VALUE_REQUESTED with option {@code index}, unchecked (the runtime drops one out of range). */
    public void pick(String pageKey, String entryKey, int index) {
        value(pageKey, entryKey, UiEntry.DROPDOWN, index);
    }

    /** SLIDER: VALUE_REQUESTED with the raw {@code value} (the runtime clamps and snaps it). */
    public void turn(String pageKey, String entryKey, int value) {
        value(pageKey, entryKey, UiEntry.SLIDER, value);
    }

    private void value(String pageKey, String entryKey, int kind, int value) {
        UiEntry e = entry(pageKey, entryKey);
        if (e != null && e.enabled() && e.kind() == kind) {
            table.input(new UiInput(UiInput.VALUE_REQUESTED, pageKey, entryKey, false, value, null));
        }
    }

    /**
     * The dialog up: {@code confirm:<title>|<text>|<yes>|<no>} or {@code speller:<title>|<text>} (the typed text, or
     * the initial one); null for none, or once answered here.
     */
    public String dialog() {
        UiDialog d = openDialog();
        if (d == null) {
            return null;
        }
        if (d.kind() == UiDialog.SPELLER) {
            return "speller:" + d.title() + "|" + spellerText(d);
        }
        return "confirm:" + d.title() + "|" + d.text() + "|" + d.yes() + "|" + d.no();
    }

    /** A confirm's row: DIALOG_DONE with {@code value} (1 is Yes), as the adapter sends it. Nothing for a speller. */
    public void answer(int value) {
        UiDialog d = openDialog();
        if (d != null && d.kind() == UiDialog.CONFIRM) {
            done(d, value, null);
        }
    }

    /** Sets the speller's text (unchecked: the runtime cleans it to maxLen). Nothing for a confirm. */
    public void type(String text) {
        UiDialog d = openDialog();
        if (d != null && d.kind() == UiDialog.SPELLER) {
            typedSeq = d.seq();
            typed = text;
        }
    }

    /** BACK on the dialog: a speller is done with its text, a confirm is No. */
    public void leave() {
        UiDialog d = openDialog();
        if (d != null) {
            done(d, 0, d.kind() == UiDialog.SPELLER ? spellerText(d) : null);
        }
    }

    /** {@code [tag] text} of the latest toast (the {@linkplain #tag status tag}); null for none. */
    public String toast() {
        UiToast t = table.snapshot().toast();
        return t == null ? null : tag(t.icon(), true) + t.text();
    }

    /** The sequence number of the latest toast, 0 for none: it rises with each toast, even one with the same text. */
    public long toastSeq() {
        UiToast t = table.snapshot().toast();
        return t == null ? 0 : t.seq();
    }

    private UiDialog openDialog() {
        UiDialog d = table.snapshot().dialog();
        return d == null || d.seq() <= answeredSeq ? null : d;
    }

    private String spellerText(UiDialog d) {
        return typedSeq == d.seq() ? typed : d.text();
    }

    private void done(UiDialog d, int value, String text) {
        answeredSeq = d.seq();
        table.input(new UiInput(UiInput.DIALOG_DONE, d.ownerPageKey(), String.valueOf(d.seq()), false, value, text));
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
