package mibsdk.adapter.hqc.ui;

import java.util.ArrayList;
import java.util.List;
import mibsdk.platform.UiIcon;
import mibsdk.platform.UiRow;

/**
 * Assigns the snapshot's Car rows to the SDK's pre-allocated drawer slots, and enforces the Car drawer cap (1 Plugins
 * row + {@link HqcUiIds#PLUGIN_ROW_SLOTS} plugin rows). Pure: run on the {@code mibsdk-ui} thread.
 */
final class DrawerSlots {

    /** Drawer rows use the built-ins' 72-px size and its reflection. */
    private static final int ROW_ICON_PX = 72;

    private DrawerSlots() {
    }

    /**
     * Slot 0 is always {@code carRows.get(0)} (the Plugins row). Of {@code carRows[1..]}, a key that held a plugin
     * slot in {@code prev} keeps it; new keys take the lowest free slot in row order; the rest go to
     * {@link DrawerAssignment#overflow()} in row order. Null rows, null keys and repeated keys are skipped, so no key
     * is ever in two slots. {@code pluginSlots} is clamped to [0, {@link HqcUiIds#PLUGIN_ROW_SLOTS}].
     *
     * <p>Each placed row's {@link UiRow#icon()} becomes its bitmaps ({@link #bitmaps}), giving image ids from
     * {@code ids} (so this is where they are allocated, on {@code mibsdk-ui}).
     */
    static DrawerAssignment assign(DrawerAssignment prev, List<UiRow> carRows, int pluginSlots, IconIds ids,
                                   BuiltinIcons builtins) {
        if (carRows == null || carRows.isEmpty() || carRows.get(0) == null || carRows.get(0).key() == null) {
            return DrawerAssignment.EMPTY;
        }
        if (prev == null) {
            prev = DrawerAssignment.EMPTY;
        }
        int slots = Math.max(0, Math.min(pluginSlots, HqcUiIds.PLUGIN_ROW_SLOTS));
        UiRow[] bySlot = new UiRow[HqcUiIds.ROW_SLOTS];
        bySlot[0] = carRows.get(0);

        // Unique plugin rows in row order.
        List<UiRow> rows = new ArrayList<UiRow>();
        List<String> seen = new ArrayList<String>();
        seen.add(bySlot[0].key());
        for (int i = 1; i < carRows.size(); i++) {
            UiRow r = carRows.get(i);
            if (r != null && r.key() != null && !seen.contains(r.key())) {
                seen.add(r.key());
                rows.add(r);
            }
        }

        // Pass 1: present keys keep their previous slot.
        boolean[] placed = new boolean[rows.size()];
        for (int s = 1; s <= slots; s++) {
            String k = prev.keyAt(s);
            for (int i = 0; k != null && i < rows.size(); i++) {
                if (!placed[i] && k.equals(rows.get(i).key())) {
                    bySlot[s] = rows.get(i);
                    placed[i] = true;
                    break;
                }
            }
        }
        // Pass 2: the others take the lowest free slot, in row order; the rest overflow.
        List<String> overflow = new ArrayList<String>();
        int free = 1;
        for (int i = 0; i < rows.size(); i++) {
            if (placed[i]) {
                continue;
            }
            while (free <= slots && bySlot[free] != null) {
                free++;
            }
            if (free <= slots) {
                bySlot[free] = rows.get(i);
            } else {
                overflow.add(rows.get(i).key());
            }
        }

        String[] keys = new String[HqcUiIds.ROW_SLOTS];
        String[] labels = new String[HqcUiIds.ROW_SLOTS];
        String[] pageKeys = new String[HqcUiIds.ROW_SLOTS];
        int[][] bitmaps = new int[HqcUiIds.ROW_SLOTS][];
        for (int s = 0; s < HqcUiIds.ROW_SLOTS; s++) {
            if (bySlot[s] != null) {
                keys[s] = bySlot[s].key();
                labels[s] = bySlot[s].label();
                pageKeys[s] = bySlot[s].pageKey();
                bitmaps[s] = bitmaps(bySlot[s].icon(), ids, builtins);
            }
        }
        return new DrawerAssignment(keys, labels, pageKeys, bitmaps, overflow);
    }

    /**
     * A row icon's drawer bitmaps: a built-in ({@link UiIcon#STOCK}) is {@code {id(<name>-72), id(<name>-72r)}}, an
     * installed file {@code {id, id}}. Null for no icon, an unknown built-in, a missing ids table, or a spent range.
     */
    static int[] bitmaps(UiIcon icon, IconIds ids, BuiltinIcons builtins) {
        if (icon == null || ids == null) {
            return null;
        }
        if (icon.kind() == UiIcon.STOCK) {
            if (builtins == null) {
                return null;
            }
            int a = id(builtins.get(icon.name(), ROW_ICON_PX, false), ids);
            int r = a < 0 ? -1 : id(builtins.get(icon.name(), ROW_ICON_PX, true), ids);
            return r < 0 ? null : new int[] {a, r};
        }
        int f = id(icon, ids);
        return f < 0 ? null : new int[] {f, f};
    }

    /** The id of an installed file icon (keyed by its owner and content key); -1 if none can be given. */
    static int id(UiIcon file, IconIds ids) {
        if (file == null || file.kind() != UiIcon.FILE || file.owner() == null || file.key() == null
                || file.path() == null) {
            return -1;
        }
        String rel = ImagePaths.relative(ImagePaths.imageRoot(HqcUiIds.INSTALL_ROOT), file.path());
        return rel == null ? -1 : ids.idFor(file.owner(), file.key(), file.path(), rel);
    }
}
