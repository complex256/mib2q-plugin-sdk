package mibsdk.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class HeapWatchTest {

    private static final long MB = 1024 * 1024;

    /** Replays the given readings (MB), one per check. */
    private static final class Readings implements HeapWatch.MemorySource {
        private final long[] mb;
        private int i;

        Readings(long... mb) {
            this.mb = mb;
        }

        public long freeBytes() {
            return mb[i++] * MB;
        }
    }

    private static final class Victims implements HeapWatch.Victim {
        final List<String> reasons = new ArrayList<String>();

        public void quarantineMostRecentlyEnabled(String reason) {
            reasons.add(reason);
        }
    }

    private static Victims run(long... readingsMb) {
        Victims v = new Victims();
        HeapWatch w = new HeapWatch(new Readings(readingsMb), 8 * MB, 3, v);
        for (int i = 0; i < readingsMb.length; i++) {
            w.check();
        }
        return v;
    }

    @Test
    void quarantinesMostRecentAfterConsecutiveLowReadings() {
        Victims v = run(9, 7, 7, 7);
        assertEquals(1, v.reasons.size());
        assertEquals("heap low", v.reasons.get(0));

        assertEquals(0, run(7, 7, 9, 7).reasons.size(), "a good reading breaks the streak");
    }

    @Test
    void resetsAfterVictimSoTheNextNeedsAnotherFullStreak() {
        assertEquals(1, run(7, 7, 7, 7, 7).reasons.size());
        assertEquals(2, run(7, 7, 7, 7, 7, 7).reasons.size());
    }

    @Test
    void exactlyLowBytesIsNotLow() {
        assertEquals(0, run(8, 8, 8, 8).reasons.size());
    }

    @Test
    void failingSourceOrVictimNeverThrows() {
        HeapWatch w = new HeapWatch(new HeapWatch.MemorySource() {
            public long freeBytes() {
                throw new IllegalStateException("bug");
            }
        }, 8 * MB, 1, new Victims());
        w.check(); // must not throw

        HeapWatch w2 = new HeapWatch(new Readings(1, 1), 8 * MB, 1, new HeapWatch.Victim() {
            public void quarantineMostRecentlyEnabled(String reason) {
                throw new IllegalStateException("bug");
            }
        });
        w2.check();
        w2.check();
    }

    @Test
    void runtimeMemoryCountsUngrownHeapAsFree() {
        long free = HeapWatch.runtimeMemory().freeBytes();
        assertTrue(free >= 0, "never negative on a host JVM larger than the unit's 60 MB: " + free);
    }
}
