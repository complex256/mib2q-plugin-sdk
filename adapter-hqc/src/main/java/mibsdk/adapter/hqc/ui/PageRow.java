package mibsdk.adapter.hqc.ui;

import mibsdk.platform.UiEntry;
import mibsdk.platform.UiPageText;

/**
 * One row of the page's list model ({@link HqcUiIds#PAGE_LIST_MODEL}): the label (column 0), the value (1: a toggle's
 * or radio's 1/0, a progress percent, an icon index), the row kind (2, the record-set column: it picks the item
 * factory's row), enabled (3) and the value text (4). Immutable.
 */
final class PageRow {

    final String label;
    final int value;
    final int kind;
    final boolean enabled;
    /** Null for none (written as ""). */
    final String valueText;

    PageRow(String label, int value, int kind, boolean enabled, String valueText) {
        this.label = label;
        this.value = value;
        this.kind = kind;
        this.enabled = enabled;
        this.valueText = valueText;
    }

    /** The row of list entry {@code e}; {@code iconIndex}: its icon's index in the shown layout, -1 for none. */
    static PageRow of(UiEntry e, int iconIndex) {
        int kind = PageScreens.rowKind(e, iconIndex, HqcUiIds.PAGE_ICON_VALUE_ROWS);
        int value;
        String text = null;
        switch (kind) {
            case PageScreens.ROW_TOGGLE:
            case PageScreens.ROW_RADIO:
                value = e.on() ? 1 : 0;
                break;
            case PageScreens.ROW_PROGRESS:
                value = e.number();
                break;
            case PageScreens.ROW_ICON:
                value = iconIndex;
                break;
            case PageScreens.ROW_ICON_VALUE:
                value = iconIndex;
                text = UiPageText.valueText(e);
                break;
            case PageScreens.ROW_VALUE:
                value = 0;
                text = UiPageText.valueText(e);
                break;
            default:
                value = 0;
                break;
        }
        return new PageRow(UiPageText.label(e), value, kind, e.enabled(), text);
    }

    public boolean equals(Object o) {
        if (!(o instanceof PageRow)) {
            return false;
        }
        PageRow r = (PageRow) o;
        return value == r.value && kind == r.kind && enabled == r.enabled && eq(label, r.label)
                && eq(valueText, r.valueText);
    }

    public int hashCode() {
        return ((kind * 31 + value) * 31 + (enabled ? 1 : 0)) * 31 + (label == null ? 0 : label.hashCode());
    }

    public String toString() {
        return label + "{kind=" + kind + " value=" + value + (enabled ? "" : " disabled")
                + (valueText == null ? "" : " text=" + valueText) + "}";
    }

    private static boolean eq(Object x, Object y) {
        return x == null ? y == null : x.equals(y);
    }
}
