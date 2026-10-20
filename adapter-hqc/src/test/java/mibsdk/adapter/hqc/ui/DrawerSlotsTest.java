package mibsdk.adapter.hqc.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import mibsdk.platform.UiRow;
import org.junit.jupiter.api.Test;

class DrawerSlotsTest {

    private static final UiRow PLUGINS = new UiRow("mibsdk.plugins", "Plugins", "mibsdk.plugins");

    private static UiRow row(String key) {
        return new UiRow(key, "label " + key, "page." + key);
    }

    /** The Plugins row, then a row per key. */
    private static List<UiRow> rows(String... keys) {
        List<UiRow> l = new ArrayList<UiRow>();
        l.add(PLUGINS);
        for (String k : keys) {
            l.add(row(k));
        }
        return l;
    }

    private static List<String> slotKeys(DrawerAssignment a) {
        List<String> l = new ArrayList<String>();
        for (int s = 0; s < HqcUiIds.ROW_SLOTS; s++) {
            l.add(a.keyAt(s));
        }
        return l;
    }

    @Test
    void assignsLowestFreeSlotInRowOrder() {
        DrawerAssignment a = DrawerSlots.assign(DrawerAssignment.EMPTY, rows("a", "b", "c"), 8);
        assertEquals(Arrays.asList("mibsdk.plugins", "a", "b", "c", null, null, null, null, null), slotKeys(a));
        assertEquals("label b", a.labelAt(2));
        assertEquals("page.b", a.pageKeyAt(2));
        assertTrue(a.visibleAt(3));
        assertFalse(a.visibleAt(4));

        // b goes away: its slot is free; a new row d takes the lowest free slot (2), c stays in 3.
        DrawerAssignment b = DrawerSlots.assign(a, rows("a", "c", "d"), 8);
        assertEquals(Arrays.asList("mibsdk.plugins", "a", "d", "c", null, null, null, null, null), slotKeys(b));
        assertEquals(Collections.emptyList(), b.overflow());
    }

    @Test
    void pluginsRowAlwaysSlotZero() {
        DrawerAssignment a = DrawerSlots.assign(DrawerAssignment.EMPTY, rows(), 8);
        assertEquals("mibsdk.plugins", a.keyAt(0));
        assertEquals("Plugins", a.labelAt(0));
        assertTrue(a.visibleAt(0));
        for (int s = 1; s < HqcUiIds.ROW_SLOTS; s++) {
            assertFalse(a.visibleAt(s));
        }

        // A plugin row in prev's slot 0 never displaces the Plugins row, and a plugin row with the Plugins row's key
        // is not assigned twice.
        DrawerAssignment prev = new DrawerAssignment(new String[] {"a"}, new String[] {"A"}, new String[] {"pa"},
                new ArrayList<String>());
        List<UiRow> l = rows("a");
        l.add(new UiRow("mibsdk.plugins", "dup", "dup"));
        DrawerAssignment b = DrawerSlots.assign(prev, l, 8);
        assertEquals(Arrays.asList("mibsdk.plugins", "a", null, null, null, null, null, null, null), slotKeys(b));

        // No snapshot rows (UiSnapshot.EMPTY): nothing assigned.
        assertSame(DrawerAssignment.EMPTY, DrawerSlots.assign(b, new ArrayList<UiRow>(), 8));
        assertSame(DrawerAssignment.EMPTY, DrawerSlots.assign(b, null, 8));
        assertFalse(DrawerAssignment.EMPTY.visibleAt(0));
    }

    /** Review Focus 1: a reloaded row comes back in a free slot, the others never move, no key is in two slots. */
    @Test
    void reloadKeepsSlotAndNoDuplicates() {
        DrawerAssignment ab = DrawerSlots.assign(DrawerAssignment.EMPTY, rows("A", "B"), 8);
        assertEquals("A", ab.keyAt(1));
        assertEquals("B", ab.keyAt(2));

        DrawerAssignment bOnly = DrawerSlots.assign(ab, rows("B"), 8);
        assertNull(bOnly.keyAt(1));
        assertEquals("B", bOnly.keyAt(2));

        DrawerAssignment again = DrawerSlots.assign(bOnly, rows("A", "B"), 8);
        assertEquals("A", again.keyAt(1), "A takes the free slot");
        assertEquals("B", again.keyAt(2), "B unchanged");
        assertNoDuplicates(again);

        // Repeated keys in the rows themselves never fill two slots.
        DrawerAssignment dup = DrawerSlots.assign(again, rows("A", "B", "A", "C", "B"), 8);
        assertEquals(Arrays.asList("mibsdk.plugins", "A", "B", "C", null, null, null, null, null), slotKeys(dup));
        assertNoDuplicates(dup);

        // A row reordered in the snapshot keeps its slot.
        DrawerAssignment reordered = DrawerSlots.assign(dup, rows("C", "B", "A"), 8);
        assertEquals(slotKeys(dup), slotKeys(reordered));
        // Labels follow the current rows.
        List<UiRow> relabelled = rows("A", "B", "C");
        relabelled.set(2, new UiRow("B", "new B", "page.B2"));
        DrawerAssignment r = DrawerSlots.assign(reordered, relabelled, 8);
        assertEquals("new B", r.labelAt(2));
        assertEquals("page.B2", r.pageKeyAt(2));
    }

    /** Review Focus 5: the overflow is the same rows, in row order, on every run. */
    @Test
    void overflowIsDeterministic() {
        String[] nine = {"row1", "row2", "row3", "row4", "row5", "row6", "row7", "row8", "row9"};
        for (int run = 0; run < 5; run++) {
            DrawerAssignment a = DrawerSlots.assign(DrawerAssignment.EMPTY, rows(nine), 8);
            assertEquals(Collections.singletonList("row9"), a.overflow());
            for (int s = 1; s <= 8; s++) {
                assertEquals("row" + s, a.keyAt(s));
            }
            // Recomputed from itself: stable.
            assertEquals(slotKeys(a), slotKeys(DrawerSlots.assign(a, rows(nine), 8)));
            assertEquals(Collections.singletonList("row9"), DrawerSlots.assign(a, rows(nine), 8).overflow());
        }
        // Two extra rows: both overflow, in row order.
        DrawerAssignment ten = DrawerSlots.assign(DrawerAssignment.EMPTY,
                rows("r1", "r2", "r3", "r4", "r5", "r6", "r7", "r8", "r9", "r10"), 8);
        assertEquals(Arrays.asList("r9", "r10"), ten.overflow());
        // A free slot later takes the first overflowed row.
        DrawerAssignment freed = DrawerSlots.assign(ten, rows("r1", "r2", "r4", "r5", "r6", "r7", "r8", "r9", "r10"),
                8);
        assertEquals("r9", freed.keyAt(3));
        assertEquals(Collections.singletonList("r10"), freed.overflow());
        try {
            freed.overflow().add("x");
            throw new AssertionError("overflow() must be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // immutable
        }
    }

    @Test
    void pluginSlotsIsClampedToTheDrawerCap() {
        String[] ten = {"r1", "r2", "r3", "r4", "r5", "r6", "r7", "r8", "r9", "r10"};
        DrawerAssignment a = DrawerSlots.assign(DrawerAssignment.EMPTY, rows(ten), 50);
        assertEquals("r8", a.keyAt(8));
        assertEquals(Arrays.asList("r9", "r10"), a.overflow());
        assertNull(a.keyAt(9));
        assertNull(a.keyAt(-1));

        DrawerAssignment two = DrawerSlots.assign(DrawerAssignment.EMPTY, rows("a", "b", "c"), 2);
        assertEquals(Arrays.asList("mibsdk.plugins", "a", "b", null, null, null, null, null, null), slotKeys(two));
        assertEquals(Collections.singletonList("c"), two.overflow());
        assertEquals(Arrays.asList("a", "b", "c"),
                DrawerSlots.assign(DrawerAssignment.EMPTY, rows("a", "b", "c"), -1).overflow());
    }

    private static void assertNoDuplicates(DrawerAssignment a) {
        List<String> seen = new ArrayList<String>();
        for (int s = 0; s < HqcUiIds.ROW_SLOTS; s++) {
            String k = a.keyAt(s);
            if (k != null) {
                assertFalse(seen.contains(k), "key in two slots: " + k);
                seen.add(k);
            }
        }
        for (String k : a.overflow()) {
            assertFalse(seen.contains(k), "assigned key also in overflow: " + k);
        }
    }
}
