package mibsdk.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FailureTrackerTest {

    @Test
    void quarantinesOnThirdFailureWithinWindow() {
        FakeClock clock = new FakeClock();
        FailureTracker tracker = new FailureTracker(3, 60000, clock);
        assertFalse(tracker.recordFailure());
        clock.advance(1000);
        assertFalse(tracker.recordFailure());
        clock.advance(61000);
        assertFalse(tracker.recordFailure()); // earlier two expired
        clock.advance(1000);
        assertFalse(tracker.recordFailure());
        clock.advance(1000);
        assertTrue(tracker.recordFailure());
    }

    @Test
    void threeQuickFailuresQuarantine() {
        FailureTracker tracker = new FailureTracker(3, 60000, new FakeClock());
        assertFalse(tracker.recordFailure());
        assertFalse(tracker.recordFailure());
        assertTrue(tracker.recordFailure());
    }
}
