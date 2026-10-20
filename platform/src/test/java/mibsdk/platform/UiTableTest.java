package mibsdk.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Collections;
import java.util.HashMap;
import org.junit.jupiter.api.Test;

class UiTableTest {

    private static UiSnapshot snap(long version) {
        return new UiSnapshot(version, Collections.<UiRow>emptyList(), "",
                new HashMap<String, UiPage>(), 0L, null);
    }

    @Test
    void snapshotNeverNull() {
        UiTable t = new UiTable(4);
        assertSame(UiSnapshot.EMPTY, t.snapshot());
        t.publish(null);
        assertNotNull(t.snapshot());
        UiSnapshot s = snap(3);
        t.publish(s);
        assertSame(s, t.snapshot());
    }

    @Test
    void publishWakesAwaitNewer() throws Exception {
        final UiTable t = new UiTable(4);
        final UiSnapshot s = snap(1);
        final UiSnapshot[] got = new UiSnapshot[1];
        Thread waiter = new Thread(new Runnable() {
            public void run() {
                try {
                    got[0] = t.awaitNewer(0L, 30000L);
                } catch (InterruptedException e) {
                    // leave null
                }
            }
        });
        waiter.start();
        t.publish(s);
        waiter.join(30000L);
        assertSame(s, got[0]);
        assertSame(s, t.awaitNewer(0L, 0L), "already newer: returns at once");
        assertNull(t.awaitNewer(1L, 0L), "no newer version: null on timeout");
    }

    @Test
    void inputDropsOldest() throws Exception {
        UiTable t = new UiTable(2);
        t.input(new UiInput(UiInput.ROW_SELECTED, "a/1", null, false));
        t.input(new UiInput(UiInput.ROW_SELECTED, "a/2", null, false));
        t.input(new UiInput(UiInput.ROW_SELECTED, "a/3", null, false));
        assertEquals(1L, t.droppedInputs());
        assertEquals("a/2", t.poll(0L).pageKey());
        assertEquals("a/3", t.poll(0L).pageKey());
        assertNull(t.poll(0L));
    }

    @Test
    void inputNeverBlocks() {
        UiTable t = new UiTable(2);
        for (int i = 0; i < 10000; i++) {
            t.input(new UiInput(UiInput.ENTRY_SELECTED, "a/p", "e" + i, false));
        }
        t.input(null);
        assertEquals(9998L, t.droppedInputs());
    }
}
