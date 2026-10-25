package mibsdk.adapter.hqc.ui;

import java.util.List;
import mibsdk.platform.UiIcon;
import mibsdk.platform.UiStatusPart;

/**
 * Where each piece of the status line goes in popup {@link HqcUiIds#POPUP_SDK_STATUS}: the indicator label, then up to
 * {@link HqcUiIds#STATUS_CELLS} cells (an optional icon, then a label), on the same character grid the runtime cut the
 * line to ({@link HqcUiIds#STATUS_CHAR_PX} per character, an icon {@link HqcUiIds#STATUS_ICON_ADVANCE_PX}).
 * Immutable: built on {@code mibsdk-ui} ({@link #of} gives built-in icons their image ids), read on HMIEvent by
 * {@link HmiWidgets} and {@link SdkStatusPopup}.
 */
final class StatusLayout {

    /** One label, with the icon before it. Unused cells are all zero, with no icon and no text. */
    static final class Cell {
        /** The icon's x; the label's x when the cell has no icon. */
        final int iconX;
        final int labelX;
        /** The label's width: to the popup's right padding, never negative. */
        final int labelW;
        /** A built-in icon's image id (its {@code <name>-20}, drawn by id and tinted); -1 for none. */
        final int stockIconId;
        /** A package icon's absolute installed path; null for none. Drawn as {@link #ownColours} says. */
        final String filePath;
        /**
         * The cell's icon in its own colours ({@link UiIcon#ownColours()}); false: tinted with the app colour (bench
         * B10). Applies to the id icon and the file icon alike.
         */
        final boolean ownColours;
        final String text;

        Cell(int iconX, int labelX, int labelW, int stockIconId, String filePath, boolean ownColours, String text) {
            this.iconX = iconX;
            this.labelX = labelX;
            this.labelW = labelW;
            this.stockIconId = stockIconId;
            this.filePath = filePath;
            this.ownColours = ownColours;
            this.text = text;
        }

        boolean sameBounds(Cell o) {
            return iconX == o.iconX && labelX == o.labelX && labelW == o.labelW;
        }
    }

    private static final Cell NONE = new Cell(0, 0, 0, -1, null, false, "");

    /** No line: every cell unused. */
    static final StatusLayout EMPTY = new StatusLayout(NONE, new Cell[0]);

    private final Cell indicators;
    private final Cell[] cells = new Cell[HqcUiIds.STATUS_CELLS];

    private StatusLayout(Cell indicators, Cell[] items) {
        this.indicators = indicators;
        for (int i = 0; i < cells.length; i++) {
            cells[i] = i < items.length && items[i] != null ? items[i] : NONE;
        }
    }

    /**
     * Places {@code parts} (the runtime's indicators first, if any, then the items) from
     * {@link HqcUiIds#STATUS_PAD_PX}: one character's space before each part after the first, then the part's icon, then its text. A part beyond
     * {@link HqcUiIds#STATUS_CELLS} items is dropped. A built-in icon gets its image id from {@code ids} (null
     * {@code ids} or {@code builtins}: no id, as for a built-in that is not installed); its space is kept either way,
     * because the runtime cut the text to fit it.
     */
    static StatusLayout of(List<UiStatusPart> parts, IconIds ids, BuiltinIcons builtins) {
        Cell indicators = NONE;
        Cell[] items = new Cell[HqcUiIds.STATUS_CELLS];
        int n = 0;
        int x = HqcUiIds.STATUS_PAD_PX;
        boolean first = true;
        for (int i = 0; parts != null && i < parts.size(); i++) {
            UiStatusPart p = parts.get(i);
            boolean ind = p != null && p.indicator() && first;
            if (p == null || (!ind && n >= items.length)) {
                continue;
            }
            if (!first) {
                x += HqcUiIds.STATUS_CHAR_PX;
            }
            first = false;
            UiIcon icon = ind ? null : p.icon();
            int iconX = x;
            if (icon != null) {
                x += HqcUiIds.STATUS_ICON_ADVANCE_PX;
            }
            int labelX = x;
            int labelW = Math.max(0, HqcUiIds.POPUP_SDK_W - HqcUiIds.STATUS_PAD_PX - x);
            x += p.text().length() * HqcUiIds.STATUS_CHAR_PX;
            Cell c = new Cell(iconX, labelX, labelW, stockId(icon, ids, builtins),
                    icon != null && icon.kind() == UiIcon.FILE ? icon.path() : null,
                    icon != null && icon.ownColours(), p.text());
            if (ind) {
                indicators = c;
            } else {
                items[n++] = c;
            }
        }
        return new StatusLayout(indicators, items);
    }

    /** The image id of a built-in icon's {@code <name>-20}; -1 for a file icon, none, or no id. */
    private static int stockId(UiIcon icon, IconIds ids, BuiltinIcons builtins) {
        if (icon == null || icon.kind() != UiIcon.STOCK || ids == null || builtins == null) {
            return -1;
        }
        return DrawerSlots.id(builtins.get(icon.name(), HqcUiIds.STATUS_ICON_PX, false), ids);
    }

    Cell indicators() {
        return indicators;
    }

    /** Cell {@code i} (0 .. {@link HqcUiIds#STATUS_CELLS} - 1). */
    Cell cell(int i) {
        return cells[i];
    }

    /** True if every label and icon has the same bounds in both (texts, paths and icon ids aside). */
    boolean sameGeometry(StatusLayout o) {
        if (!indicators.sameBounds(o.indicators)) {
            return false;
        }
        for (int i = 0; i < cells.length; i++) {
            if (!cells[i].sameBounds(o.cells[i])) {
                return false;
            }
        }
        return true;
    }

    /** True if every cell has the same built-in icon id and the same colour flag in both. */
    boolean sameIcons(StatusLayout o) {
        for (int i = 0; i < cells.length; i++) {
            if (cells[i].stockIconId != o.cells[i].stockIconId || cells[i].ownColours != o.cells[i].ownColours) {
                return false;
            }
        }
        return true;
    }
}
