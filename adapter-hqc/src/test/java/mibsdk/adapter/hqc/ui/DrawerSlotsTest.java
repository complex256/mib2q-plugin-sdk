package mibsdk.adapter.hqc.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import mibsdk.platform.UiIcon;
import mibsdk.platform.UiRow;
import mibsdk.platform.UiStatusPart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
        DrawerAssignment a = DrawerSlots.assign(DrawerAssignment.EMPTY, rows("a", "b", "c"), 8, null, null);
        assertEquals(Arrays.asList("mibsdk.plugins", "a", "b", "c", null, null, null, null, null), slotKeys(a));
        assertEquals("label b", a.labelAt(2));
        assertEquals("page.b", a.pageKeyAt(2));
        assertTrue(a.visibleAt(3));
        assertFalse(a.visibleAt(4));

        // b goes away: its slot is free; a new row d takes the lowest free slot (2), c stays in 3.
        DrawerAssignment b = DrawerSlots.assign(a, rows("a", "c", "d"), 8, null, null);
        assertEquals(Arrays.asList("mibsdk.plugins", "a", "d", "c", null, null, null, null, null), slotKeys(b));
        assertEquals(Collections.emptyList(), b.overflow());
    }

    @Test
    void pluginsRowAlwaysSlotZero() {
        DrawerAssignment a = DrawerSlots.assign(DrawerAssignment.EMPTY, rows(), 8, null, null);
        assertEquals("mibsdk.plugins", a.keyAt(0));
        assertEquals("Plugins", a.labelAt(0));
        assertTrue(a.visibleAt(0));
        for (int s = 1; s < HqcUiIds.ROW_SLOTS; s++) {
            assertFalse(a.visibleAt(s));
        }

        // A plugin row in prev's slot 0 never displaces the Plugins row, and a plugin row with the Plugins row's key
        // is not assigned twice.
        DrawerAssignment prev = new DrawerAssignment(new String[] {"a"}, new String[] {"A"}, new String[] {"pa"},
                new int[0][], new ArrayList<String>());
        List<UiRow> l = rows("a");
        l.add(new UiRow("mibsdk.plugins", "dup", "dup"));
        DrawerAssignment b = DrawerSlots.assign(prev, l, 8, null, null);
        assertEquals(Arrays.asList("mibsdk.plugins", "a", null, null, null, null, null, null, null), slotKeys(b));

        // No snapshot rows (UiSnapshot.EMPTY): nothing assigned.
        assertSame(DrawerAssignment.EMPTY, DrawerSlots.assign(b, new ArrayList<UiRow>(), 8, null, null));
        assertSame(DrawerAssignment.EMPTY, DrawerSlots.assign(b, null, 8, null, null));
        assertFalse(DrawerAssignment.EMPTY.visibleAt(0));
    }

    /** Review Focus 1: a reloaded row comes back in a free slot, the others never move, no key is in two slots. */
    @Test
    void reloadKeepsSlotAndNoDuplicates() {
        DrawerAssignment ab = DrawerSlots.assign(DrawerAssignment.EMPTY, rows("A", "B"), 8, null, null);
        assertEquals("A", ab.keyAt(1));
        assertEquals("B", ab.keyAt(2));

        DrawerAssignment bOnly = DrawerSlots.assign(ab, rows("B"), 8, null, null);
        assertNull(bOnly.keyAt(1));
        assertEquals("B", bOnly.keyAt(2));

        DrawerAssignment again = DrawerSlots.assign(bOnly, rows("A", "B"), 8, null, null);
        assertEquals("A", again.keyAt(1), "A takes the free slot");
        assertEquals("B", again.keyAt(2), "B unchanged");
        assertNoDuplicates(again);

        // Repeated keys in the rows themselves never fill two slots.
        DrawerAssignment dup = DrawerSlots.assign(again, rows("A", "B", "A", "C", "B"), 8, null, null);
        assertEquals(Arrays.asList("mibsdk.plugins", "A", "B", "C", null, null, null, null, null), slotKeys(dup));
        assertNoDuplicates(dup);

        // A row reordered in the snapshot keeps its slot.
        DrawerAssignment reordered = DrawerSlots.assign(dup, rows("C", "B", "A"), 8, null, null);
        assertEquals(slotKeys(dup), slotKeys(reordered));
        // Labels follow the current rows.
        List<UiRow> relabelled = rows("A", "B", "C");
        relabelled.set(2, new UiRow("B", "new B", "page.B2"));
        DrawerAssignment r = DrawerSlots.assign(reordered, relabelled, 8, null, null);
        assertEquals("new B", r.labelAt(2));
        assertEquals("page.B2", r.pageKeyAt(2));
    }

    /** Review Focus 5: the overflow is the same rows, in row order, on every run. */
    @Test
    void overflowIsDeterministic() {
        String[] nine = {"row1", "row2", "row3", "row4", "row5", "row6", "row7", "row8", "row9"};
        for (int run = 0; run < 5; run++) {
            DrawerAssignment a = DrawerSlots.assign(DrawerAssignment.EMPTY, rows(nine), 8, null, null);
            assertEquals(Collections.singletonList("row9"), a.overflow());
            for (int s = 1; s <= 8; s++) {
                assertEquals("row" + s, a.keyAt(s));
            }
            // Recomputed from itself: stable.
            assertEquals(slotKeys(a), slotKeys(DrawerSlots.assign(a, rows(nine), 8, null, null)));
            assertEquals(Collections.singletonList("row9"), DrawerSlots.assign(a, rows(nine), 8, null, null).overflow());
        }
        // Two extra rows: both overflow, in row order.
        DrawerAssignment ten = DrawerSlots.assign(DrawerAssignment.EMPTY,
                rows("r1", "r2", "r3", "r4", "r5", "r6", "r7", "r8", "r9", "r10"), 8, null, null);
        assertEquals(Arrays.asList("r9", "r10"), ten.overflow());
        // A free slot later takes the first overflowed row.
        DrawerAssignment freed = DrawerSlots.assign(ten, rows("r1", "r2", "r4", "r5", "r6", "r7", "r8", "r9", "r10"),
                8, null, null);
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
        DrawerAssignment a = DrawerSlots.assign(DrawerAssignment.EMPTY, rows(ten), 50, null, null);
        assertEquals("r8", a.keyAt(8));
        assertEquals(Arrays.asList("r9", "r10"), a.overflow());
        assertNull(a.keyAt(9));
        assertNull(a.keyAt(-1));

        DrawerAssignment two = DrawerSlots.assign(DrawerAssignment.EMPTY, rows("a", "b", "c"), 2, null, null);
        assertEquals(Arrays.asList("mibsdk.plugins", "a", "b", null, null, null, null, null, null), slotKeys(two));
        assertEquals(Collections.singletonList("c"), two.overflow());
        assertEquals(Arrays.asList("a", "b", "c"),
                DrawerSlots.assign(DrawerAssignment.EMPTY, rows("a", "b", "c"), -1, null, null).overflow());
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

    @TempDir
    File dir;

    /** Task 4: built-ins resolve to {icon, reflection} ids, files to {id, id}; unknown, inert or spent to null. */
    @Test
    void iconsResolveToBitmapPairs() throws IOException {
        Files.write(new File(dir, "aaaa000000000000.png").toPath(), new byte[] {1});
        Files.write(new File(dir, "bbbb000000000000.png").toPath(), new byte[] {1});
        Files.write(new File(dir, "index.properties").toPath(),
                "check-72=aaaa000000000000,72,72\ncheck-72r=bbbb000000000000,72,68\n"
                        .getBytes(StandardCharsets.ISO_8859_1));
        BuiltinIcons builtins = BuiltinIcons.read(dir);
        IconIds ids = new IconIds(609100, 609103);
        String file = HqcUiIds.INSTALL_ROOT + "/icons/hello/0123456789abcdef.png";
        List<UiRow> l = new ArrayList<UiRow>();
        l.add(new UiRow("mibsdk.plugins", "Plugins", "mibsdk.plugins", UiIcon.stock("check")));
        l.add(new UiRow("a", "A", "pa", UiIcon.file("hello", file, "0123456789abcdef", 72, 72)));
        l.add(new UiRow("b", "B", "pb", UiIcon.stock("nope")));
        l.add(new UiRow("c", "C", "pc", null)); // no icon, or an inert one (declared but not installed)
        l.add(new UiRow("d", "D", "pd", UiIcon.file("other", file, "0123456789abcdef", 72, 72)));
        l.add(new UiRow("e", "E", "pe", UiIcon.file("third", file, "0123456789abcdef", 72, 72)));

        DrawerAssignment a = DrawerSlots.assign(DrawerAssignment.EMPTY, l, 8, ids, builtins);
        assertArrayEquals(new int[] {609100, 609101}, a.iconAt(0));
        assertArrayEquals(new int[] {609102, 609102}, a.iconAt(1));
        assertEquals("../mibsdk/icons/hello/0123456789abcdef.png", ids.relPathFor(609102));
        assertNull(a.iconAt(2), "unknown built-in");
        assertNull(a.iconAt(3), "no icon");
        assertArrayEquals(new int[] {609103, 609103}, a.iconAt(4), "same bytes, other owner: its own id");
        assertNull(a.iconAt(5), "range spent");
        assertNull(a.iconAt(6));

        DrawerAssignment again = DrawerSlots.assign(a, l, 8, ids, builtins);
        assertArrayEquals(new int[] {609102, 609102}, again.iconAt(1), "a key keeps its id");
        assertNull(DrawerSlots.assign(a, l, 8, null, null).iconAt(1), "no id table: no icons");
        int[] copy = again.iconAt(1);
        copy[0] = 1;
        assertArrayEquals(new int[] {609102, 609102}, again.iconAt(1), "immutable");
    }

    /** Regression guard (spec 2.2): the drawer draws every icon its own way, so the colour flag changes nothing. */
    @Test
    void ownColoursDoesNotChangeBitmaps() {
        IconIds ids = new IconIds(HqcUiIds.ICON_ID_FIRST, HqcUiIds.ICON_ID_LAST);
        UiIcon f = UiIcon.file("hello", HqcUiIds.INSTALL_ROOT + "/icons/hello/0123456789abcdef.png",
                "0123456789abcdef", 72, 72);
        int[] tinted = DrawerSlots.bitmaps(f, ids, BuiltinIcons.NONE);
        assertArrayEquals(new int[] {HqcUiIds.ICON_ID_FIRST, HqcUiIds.ICON_ID_FIRST}, tinted);
        assertArrayEquals(tinted, DrawerSlots.bitmaps(f.withOwnColours(true), ids, BuiltinIcons.NONE));
    }

    /**
     * Regression guard: the flag is not part of the id key, so both forms share one id (one allocation) and one
     * file.
     */
    @Test
    void colourDoesNotChangeTheId() {
        IconIds ids = new IconIds(HqcUiIds.ICON_ID_FIRST, HqcUiIds.ICON_ID_LAST);
        String path = HqcUiIds.INSTALL_ROOT + "/icons/hello/0123456789abcdef.png";
        UiIcon f = UiIcon.file("hello", path, "0123456789abcdef", 20, 20);
        int id = DrawerSlots.id(f, ids);
        assertEquals(HqcUiIds.ICON_ID_FIRST, id);
        assertEquals(id, DrawerSlots.id(f.withOwnColours(true), ids));
        UiIcon other = UiIcon.file("hello", HqcUiIds.INSTALL_ROOT + "/icons/hello/fedcba9876543210.png",
                "fedcba9876543210", 20, 20);
        assertEquals(HqcUiIds.ICON_ID_FIRST + 1, DrawerSlots.id(other, ids), "a single allocation for both");

        List<UiStatusPart> tinted = new ArrayList<UiStatusPart>();
        tinted.add(new UiStatusPart("x", f, false));
        List<UiStatusPart> own = new ArrayList<UiStatusPart>();
        own.add(new UiStatusPart("x", f.withOwnColours(true), false));
        assertEquals(path, StatusLayout.of(tinted, ids, BuiltinIcons.NONE).cell(0).filePath);
        assertEquals(path, StatusLayout.of(own, ids, BuiltinIcons.NONE).cell(0).filePath);
    }

    private static final String HELLO = HqcUiIds.INSTALL_ROOT + "/icons/hello/";

    private static UiIcon withReflection() {
        return UiIcon.file("hello", HELLO + "0123456789abcdef.png", "0123456789abcdef", 72, 72)
                .withReflection(UiIcon.file("hello", HELLO + "fedcba9876543210.png", "fedcba9876543210", 72, 68));
    }

    @Test
    void fileIconWithReflectionUsesItsOwnId() {
        IconIds ids = new IconIds(HqcUiIds.ICON_ID_FIRST, HqcUiIds.ICON_ID_LAST);
        int[] b = DrawerSlots.bitmaps(withReflection(), ids, BuiltinIcons.NONE);
        assertArrayEquals(new int[] {HqcUiIds.ICON_ID_FIRST, HqcUiIds.ICON_ID_FIRST + 1}, b);
        assertEquals("../mibsdk/icons/hello/0123456789abcdef.png", ids.relPathFor(b[0]));
        assertEquals("../mibsdk/icons/hello/fedcba9876543210.png", ids.relPathFor(b[1]));
        assertArrayEquals(b, DrawerSlots.bitmaps(withReflection(), ids, BuiltinIcons.NONE), "ids are kept");
        assertArrayEquals(b, DrawerSlots.bitmaps(withReflection().withOwnColours(true), ids, BuiltinIcons.NONE));
    }

    @Test
    void fileIconWithoutReflectionFallsBackToIdId() {
        IconIds ids = new IconIds(HqcUiIds.ICON_ID_FIRST, HqcUiIds.ICON_ID_LAST);
        UiIcon f = UiIcon.file("hello", HELLO + "0123456789abcdef.png", "0123456789abcdef", 72, 72);
        assertArrayEquals(new int[] {HqcUiIds.ICON_ID_FIRST, HqcUiIds.ICON_ID_FIRST},
                DrawerSlots.bitmaps(f, ids, BuiltinIcons.NONE));

        // Rows of an old package ({id, id}) and a new one ({id, rid}) side by side in one drawer.
        List<UiRow> l = new ArrayList<UiRow>();
        l.add(PLUGINS);
        l.add(new UiRow("old", "Old", "po", f));
        l.add(new UiRow("new", "New", "pn", withReflection()));
        DrawerAssignment a = DrawerSlots.assign(DrawerAssignment.EMPTY, l, 8, ids, BuiltinIcons.NONE);
        assertArrayEquals(new int[] {HqcUiIds.ICON_ID_FIRST, HqcUiIds.ICON_ID_FIRST}, a.iconAt(1));
        assertArrayEquals(new int[] {HqcUiIds.ICON_ID_FIRST, HqcUiIds.ICON_ID_FIRST + 1}, a.iconAt(2),
                "same base bytes and owner: the base id is shared, the reflection has its own");
    }

    @Test
    void spentRangeForTheReflectionFallsBackToIdId() {
        assertArrayEquals(new int[] {HqcUiIds.ICON_ID_FIRST, HqcUiIds.ICON_ID_FIRST + 1},
                DrawerSlots.bitmaps(withReflection(), new IconIds(HqcUiIds.ICON_ID_FIRST, HqcUiIds.ICON_ID_FIRST + 1),
                        BuiltinIcons.NONE), "the control: room for both");
        IconIds ids = new IconIds(HqcUiIds.ICON_ID_FIRST, HqcUiIds.ICON_ID_FIRST);
        assertArrayEquals(new int[] {HqcUiIds.ICON_ID_FIRST, HqcUiIds.ICON_ID_FIRST},
                DrawerSlots.bitmaps(withReflection(), ids, BuiltinIcons.NONE));
        assertNull(DrawerSlots.bitmaps(withReflection(), new IconIds(HqcUiIds.ICON_ID_FIRST,
                HqcUiIds.ICON_ID_FIRST - 1), BuiltinIcons.NONE), "no id for the icon itself: no bitmaps");
    }
}
