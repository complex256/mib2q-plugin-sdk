package mibsdk.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class HeapWatchTest {

    private static final long MB = 1024 * 1024;

    /** Replays the given readings (MB), one per check; the last one repeats. */
    private static final class Readings implements HeapWatch.MemorySource {
        private final long[] mb;
        private int i;

        Readings(long... mb) {
            this.mb = mb;
        }

        public long freeBytes() {
            return mb[Math.min(i++, mb.length - 1)] * MB;
        }
    }

    private static final class Victims implements HeapWatch.Victim {
        final List<String> reasons = new ArrayList<String>();
        boolean enabled = true;

        public boolean anyEnabled() {
            return enabled;
        }

        public void quarantineMostRecentlyEnabled(String reason) {
            reasons.add(reason);
        }
    }

    /** Counts GC requests; never really collects. */
    private static final class Gcs implements Runnable {
        int n;

        public void run() {
            n++;
        }
    }

    /** {@code checks} calls of check() over the readings (MB); a full streak consumes one more (after the GC). */
    private static Victims run(int checks, Gcs gcs, long... readingsMb) {
        Victims v = new Victims();
        HeapWatch w = new HeapWatch(new Readings(readingsMb), 8 * MB, 3, v, gcs);
        for (int i = 0; i < checks; i++) {
            w.check();
        }
        return v;
    }

    @Test
    void quarantinesMostRecentAfterConsecutiveLowReadings() {
        Gcs gcs = new Gcs();
        Victims v = run(4, gcs, 9, 7, 7, 7, 7); // the last 7 is the reading after the GC
        assertEquals(1, v.reasons.size());
        assertEquals("heap low", v.reasons.get(0));
        assertEquals(1, gcs.n);

        Gcs none = new Gcs();
        assertEquals(0, run(4, none, 7, 7, 9, 7).reasons.size(), "a good reading breaks the streak");
        assertEquals(0, none.n);
    }

    @Test
    void garbageOnlyLowStreakQuarantinesNothing() {
        Gcs gcs = new Gcs();
        Victims v = run(6, gcs, 7, 7, 7, 20, 7, 7, 7, 20); // each streak recovers after the GC
        assertEquals(0, v.reasons.size());
        assertEquals(2, gcs.n, "one GC per full streak; the streak restarts after it");
    }

    @Test
    void resetsAfterVictimSoTheNextNeedsAnotherFullStreak() {
        assertEquals(1, run(5, new Gcs(), 7, 7, 7, 7, 7, 7).reasons.size());
        // The GC did not help, so the interval doubled: the next full streak is due on checks 5, 7 and 9.
        assertEquals(1, run(8, new Gcs(), 7).reasons.size());
        assertEquals(2, run(9, new Gcs(), 7).reasons.size());
    }

    @Test
    void exactlyLowBytesIsNotLow() {
        assertEquals(0, run(4, new Gcs(), 8, 8, 8, 8).reasons.size());
    }

    @Test
    void failingSourceOrVictimNeverThrows() {
        HeapWatch w = new HeapWatch(new HeapWatch.MemorySource() {
            public long freeBytes() {
                throw new IllegalStateException("bug");
            }
        }, 8 * MB, 1, new Victims(), new Gcs());
        w.check(); // must not throw

        HeapWatch w2 = new HeapWatch(new Readings(1, 1, 1, 1), 8 * MB, 1, new HeapWatch.Victim() {
            public boolean anyEnabled() {
                return true;
            }

            public void quarantineMostRecentlyEnabled(String reason) {
                throw new IllegalStateException("bug");
            }
        }, new Gcs());
        w2.check();
        w2.check();
    }

    @Test
    void noEnabledPluginMeansNoGcAndNoVictim() {
        Gcs gcs = new Gcs();
        Victims v = new Victims();
        v.enabled = false;
        Readings r = new Readings(1);
        HeapWatch w = new HeapWatch(r, 8 * MB, 3, v, gcs);
        for (int i = 0; i < 10; i++) {
            w.check();
        }
        assertEquals(0, gcs.n, "a GC cannot help a plugin that does not exist; it only stalls the HMI");
        assertEquals(0, v.reasons.size());

        v.enabled = true; // a plugin got enabled: a fresh full streak is needed
        w.check();
        w.check();
        assertEquals(0, gcs.n);
        w.check();
        assertEquals(1, gcs.n);
        assertEquals(1, v.reasons.size());
    }

    @Test
    void backsOffAfterAGcThatDidNotHelpAndResetsOnRecovery() {
        Gcs gcs = new Gcs();
        Victims v = new Victims();
        Readings r = new Readings(1); // always low
        HeapWatch w = new HeapWatch(r, 8 * MB, 1, v, gcs);
        w.check(); // reading, GC, reading: still low, so the victim goes and the interval doubles
        assertEquals(1, gcs.n);
        assertEquals(2, r.i);
        assertEquals(2, w.intervalMultiplier());
        w.check(); // skipped
        assertEquals(2, r.i);
        assertEquals(1, gcs.n);
        w.check(); // the next due check
        assertEquals(2, gcs.n);
        assertEquals(4, w.intervalMultiplier());
        for (int i = 0; i < 3; i++) {
            w.check();
        }
        assertEquals(2, gcs.n, "three skipped");
        for (int i = 0; i < 200; i++) {
            w.check();
        }
        assertEquals(HeapWatch.MAX_BACKOFF, w.intervalMultiplier(), "capped");
        int before = gcs.n;
        for (int i = 0; i < HeapWatch.MAX_BACKOFF; i++) {
            w.check();
        }
        assertEquals(before + 1, gcs.n, "one check per capped interval");

        HeapWatch rec = new HeapWatch(new Readings(1, 1, 20), 8 * MB, 1, new Victims(), new Gcs());
        rec.check(); // low, GC, low: backs off
        assertEquals(2, rec.intervalMultiplier());
        rec.check(); // skipped
        rec.check(); // 20 MB: recovered
        assertEquals(1, rec.intervalMultiplier());
    }

    @Test
    void gcThatHelpsDoesNotBackOff() {
        HeapWatch w = new HeapWatch(new Readings(1, 20), 8 * MB, 1, new Victims(), new Gcs());
        w.check();
        assertEquals(1, w.intervalMultiplier());
    }

    @Test
    void statusLineReportsFreeAndTotal() {
        String line = HeapWatch.statusLine();
        assertTrue(line.matches("heap free=\\d+ total=\\d+"), line);
    }

    @Test
    void runtimeMemoryCountsUngrownHeapAsFree() {
        long free = HeapWatch.runtimeMemory().freeBytes();
        assertTrue(free >= 0, "never negative on a host JVM larger than the unit's 60 MB: " + free);
    }
}
