package mibsdk.adapter.hqc.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HmiPosterTest {

    /** Holds posted jobs until the test runs them (HMIEvent stand-in). */
    static final class HeldSink implements HmiPoster.Sink {
        final List<Runnable> held = new ArrayList<Runnable>();
        boolean fail;

        public synchronized void post(Runnable r) {
            if (fail) {
                throw new IllegalStateException("dispatcher gone");
            }
            held.add(r);
        }

        synchronized int size() {
            return held.size();
        }

        /** Runs the jobs held now (not ones they post); returns how many ran. */
        int runHeld() {
            List<Runnable> now;
            synchronized (this) {
                now = new ArrayList<Runnable>(held);
                held.clear();
            }
            for (Runnable r : now) {
                r.run();
            }
            return now.size();
        }
    }

    /** Review Focus 3: never more than one SDK job pending on HMIEvent. */
    @Test
    void coalescesToOnePendingPost() {
        HeldSink sink = new HeldSink();
        AtomicInteger after = new AtomicInteger();
        AtomicInteger ran = new AtomicInteger();
        HmiPoster p = new HmiPoster(sink, after::incrementAndGet);
        int accepted = 0;
        for (int i = 0; i < 1000; i++) {
            if (p.post(ran::incrementAndGet)) {
                accepted++;
            }
        }
        assertEquals(1, accepted);
        assertEquals(1, sink.size());
        assertTrue(p.pending());
        assertEquals(1, sink.runHeld());
        assertEquals(1, ran.get());
        assertEquals(1, after.get());
        assertFalse(p.pending());
        assertTrue(p.post(ran::incrementAndGet), "free again once the job ran");
    }

    @Test
    void throwingJobClearsPendingAndRunsAfterJob() {
        HeldSink sink = new HeldSink();
        List<Boolean> pendingInAfterJob = new ArrayList<Boolean>();
        HmiPoster[] self = new HmiPoster[1];
        self[0] = new HmiPoster(sink, () -> pendingInAfterJob.add(self[0].pending()));
        assertTrue(self[0].post(() -> {
            throw new IllegalStateException("job failed");
        }));
        sink.runHeld(); // the wrapper itself never throws onto HMIEvent
        assertEquals(java.util.Collections.singletonList(false), pendingInAfterJob, "pending clears before afterJob");
        assertFalse(self[0].pending());

        HmiPoster throwingAfter = new HmiPoster(sink, () -> {
            throw new IllegalStateException("after failed");
        });
        assertTrue(throwingAfter.post(() -> { }));
        sink.runHeld();
        assertFalse(throwingAfter.pending());
    }

    @Test
    void refusedPostLeavesNothingPending() {
        HeldSink sink = new HeldSink();
        sink.fail = true;
        HmiPoster p = new HmiPoster(sink, null);
        assertFalse(p.post(() -> { }));
        assertFalse(p.pending(), "the caller may retry");
        sink.fail = false;
        assertTrue(p.post(() -> { }));
        sink.runHeld(); // null afterJob is fine
        assertFalse(p.pending());
    }
}
