package mibsdk.adapter.hqc.ui;

import java.util.ArrayList;
import java.util.List;
import mibsdk.platform.UiRow;

/**
 * Assigns the snapshot's Car rows to the SDK's pre-allocated drawer slots, and enforces the Car drawer cap (1 Plugins
 * row + {@link HqcUiIds#PLUGIN_ROW_SLOTS} plugin rows). Pure: run on the {@code mibsdk-ui} thread.
 */
final class DrawerSlots {

    private DrawerSlots() {
    }

    /**
     * Slot 0 is always {@code carRows.get(0)} (the Plugins row). Of {@code carRows[1..]}, a key that held a plugin
     * slot in {@code prev} keeps it; new keys take the lowest free slot in row order; the rest go to
     * {@link DrawerAssignment#overflow()} in row order. Null rows, null keys and repeated keys are skipped, so no key
     * is ever in two slots. {@code pluginSlots} is clamped to [0, {@link HqcUiIds#PLUGIN_ROW_SLOTS}].
     */
    static DrawerAssignment assign(DrawerAssignment prev, List<UiRow> carRows, int pluginSlots) {
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
        for (int s = 0; s < HqcUiIds.ROW_SLOTS; s++) {
            if (bySlot[s] != null) {
                keys[s] = bySlot[s].key();
                labels[s] = bySlot[s].label();
                pageKeys[s] = bySlot[s].pageKey();
            }
        }
        return new DrawerAssignment(keys, labels, pageKeys, overflow);
    }
}
