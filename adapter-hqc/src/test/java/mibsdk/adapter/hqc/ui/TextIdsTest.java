package mibsdk.adapter.hqc.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import mibsdk.platform.UiRow;
import org.junit.jupiter.api.Test;

class TextIdsTest {

    @Test
    void servesOnlyOwnRange() {
        TextIds ids = new TextIds(HqcUiIds.TEXT_BASE);
        List<UiRow> rows = new ArrayList<UiRow>();
        rows.add(new UiRow("mibsdk.plugins", "Plugins", "mibsdk.plugins"));
        rows.add(new UiRow("hello.row", "Hello", "hello.page"));
        DrawerAssignment a = DrawerSlots.assign(DrawerAssignment.EMPTY, rows, HqcUiIds.PLUGIN_ROW_SLOTS);

        assertEquals(609000, ids.idFor(0));
        assertEquals(609008, ids.idFor(8));
        assertEquals("Plugins", ids.textFor(609000, a));
        assertEquals("Hello", ids.textFor(609001, a));
        for (int id = 609002; id <= 609008; id++) {
            assertEquals("", ids.textFor(id, a), "empty slot " + id + " is ours, blank");
        }
        assertNull(ids.textFor(608999, a));
        assertNull(ids.textFor(609009, a));
        assertNull(ids.textFor(601352, a), "stock text");
        assertEquals("", ids.textFor(609000, DrawerAssignment.EMPTY));
        assertEquals("", ids.textFor(609000, null));
        assertNull(ids.textFor(Integer.MIN_VALUE, a));
        assertNull(ids.textFor(Integer.MAX_VALUE, a));
    }
}
