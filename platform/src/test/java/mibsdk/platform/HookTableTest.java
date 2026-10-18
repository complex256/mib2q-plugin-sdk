package mibsdk.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mibsdk.api.HookObserved;
import mibsdk.api.HookPoint;
import org.junit.jupiter.api.Test;

class HookTableTest {

    @Test
    void noVetoByDefault() {
        HookTable t = new HookTable(4);
        assertFalse(t.isVetoed(HookPoint.PARKING_SOUND_SET));
        assertFalse(t.isVetoed(HookPoint.PARKING_SOUND_UPDATE));
    }

    @Test
    void activeVetoIsSeen() {
        HookTable t = new HookTable(4);
        Object token = t.addVeto(HookPoint.PARKING_SOUND_SET);
        assertFalse(t.isVetoed(HookPoint.PARKING_SOUND_SET), "inactive until activated");
        t.setVetoActive(token, true);
        assertTrue(t.isVetoed(HookPoint.PARKING_SOUND_SET));
        assertFalse(t.isVetoed(HookPoint.PARKING_SOUND_UPDATE), "other point unaffected");
        t.setVetoActive(token, false);
        assertFalse(t.isVetoed(HookPoint.PARKING_SOUND_SET));
    }

    @Test
    void removeVetoClears() {
        HookTable t = new HookTable(4);
        Object a = t.addVeto(HookPoint.PARKING_SOUND_SET);
        Object b = t.addVeto(HookPoint.PARKING_SOUND_SET);
        t.setVetoActive(a, true);
        t.setVetoActive(b, true);
        t.removeVeto(a);
        assertTrue(t.isVetoed(HookPoint.PARKING_SOUND_SET), "second veto still holds");
        t.removeVeto(b);
        assertFalse(t.isVetoed(HookPoint.PARKING_SOUND_SET));
        t.setVetoActive(a, true); // removed token must not resurrect
        assertFalse(t.isVetoed(HookPoint.PARKING_SOUND_SET));
    }

    @Test
    void bogusArgumentsNeverThrowOnStockSide() {
        HookTable t = new HookTable(2);
        assertFalse(t.isVetoed(null));
        t.observed(null, null, false);
        t.setVetoActive("not a token", true);
        t.removeVeto(null);
    }

    @Test
    void observationQueueDropsOldest() throws Exception {
        HookTable t = new HookTable(2);
        t.observed(HookPoint.PARKING_SOUND_SET, "1", false);
        t.observed(HookPoint.PARKING_SOUND_SET, "2", true);
        t.observed(HookPoint.PARKING_SOUND_UPDATE, "3", false);
        assertEquals(1, t.droppedCount());
        HookObserved a = t.poll(0);
        HookObserved b = t.poll(0);
        assertEquals("2", a.value());
        assertTrue(a.vetoed());
        assertSame(HookPoint.PARKING_SOUND_SET, a.point());
        assertEquals("3", b.value());
        assertSame(HookPoint.PARKING_SOUND_UPDATE, b.point());
        assertNull(t.poll(0));
    }

    @Test
    void pollWaitsForAnObservation() throws Exception {
        final HookTable t = new HookTable(2);
        Thread producer = new Thread(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
                return;
            }
            t.observed(HookPoint.PARKING_SOUND_SET, "late", false);
        });
        producer.start();
        HookObserved o = t.poll(5000);
        producer.join();
        assertEquals("late", o.value());
    }

    @Test
    void pollTimesOutWithNull() throws Exception {
        assertNull(new HookTable(2).poll(20));
    }

    @Test
    void observedNeverBlocks() {
        HookTable t = new HookTable(256);
        long start = System.currentTimeMillis();
        for (int i = 0; i < 10000; i++) {
            t.observed(HookPoint.PARKING_SOUND_UPDATE, Integer.valueOf(i), false);
        }
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed < 200, "took " + elapsed + " ms");
        assertEquals(10000 - 256, t.droppedCount());
    }
}
