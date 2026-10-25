package mibsdk.adapter.hqc.ui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import mibsdk.platform.UiInput;
import mibsdk.platform.UiRow;
import mibsdk.platform.UiTable;
import org.junit.jupiter.api.Test;

class UiInputsTest {

    @Test
    void rowPressQueuesTheSlotsRowKeyEveryTime() throws InterruptedException {
        List<UiRow> rows = new ArrayList<UiRow>();
        rows.add(new UiRow("mibsdk.plugins", "Plugins", "mibsdk.plugins"));
        rows.add(new UiRow("hello.row", "Hello", "hello.page"));
        DrawerAssignment a = DrawerSlots.assign(DrawerAssignment.EMPTY, rows, HqcUiIds.PLUGIN_ROW_SLOTS, null, null);
        UiTable t = new UiTable(4);

        UiInputs.rowPressed(t, a, 1);
        UiInputs.rowPressed(t, a, 1); // re-press fires again (spike B): no reset, no dedup
        UiInputs.rowPressed(t, a, 0);
        for (String expected : new String[] {"hello.row", "hello.row", "mibsdk.plugins"}) {
            UiInput in = t.poll(0);
            assertEquals(UiInput.ROW_SELECTED, in.kind());
            assertEquals(expected, in.pageKey());
        }
        assertNull(t.poll(0));
    }

    @Test
    void emptySlotsAndBadArgumentsAreIgnoredWithoutThrowing() throws InterruptedException {
        UiTable t = new UiTable(4);
        DrawerAssignment a = DrawerAssignment.EMPTY;
        assertDoesNotThrow(() -> UiInputs.rowPressed(t, a, 0));
        assertDoesNotThrow(() -> UiInputs.rowPressed(t, a, -1));
        assertDoesNotThrow(() -> UiInputs.rowPressed(t, a, 99));
        assertDoesNotThrow(() -> UiInputs.rowPressed(t, null, 1));
        assertDoesNotThrow(() -> UiInputs.rowPressed(null, a, 1));
        assertNull(t.poll(0));
    }
}
