package mibsdk.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MonotonicClockTest {

    private static final class StepWall implements MonotonicClock.WallSource {
        long now = 0; // the unit boots with its clock at 1970

        @Override
        public long nowMs() {
            return now;
        }
    }

    @Test
    void wallClockJumpDoesNotAffectUptime() {
        StepWall wall = new StepWall();
        MonotonicClock clock = new MonotonicClock(wall, 1000);
        assertEquals(0, clock.uptimeMs());

        wall.now += 1000;
        clock.tick();
        wall.now += 1_000_000_000_000L; // GPS steps the clock by decades
        clock.tick();
        wall.now += 1000;
        clock.tick();

        assertTrue(clock.uptimeMs() <= 4000, "uptime was " + clock.uptimeMs());
        assertTrue(clock.uptimeMs() >= 1000);
    }

    @Test
    void negativeWallJumpAddsZero() {
        StepWall wall = new StepWall();
        wall.now = 100_000;
        MonotonicClock clock = new MonotonicClock(wall, 1000);
        wall.now += 1000;
        clock.tick();
        assertEquals(1000, clock.uptimeMs());
        wall.now -= 5000;
        clock.tick();
        assertEquals(1000, clock.uptimeMs());
        wall.now += 1000; // resumes from the new baseline
        clock.tick();
        assertEquals(2000, clock.uptimeMs());
    }

    @Test
    void startedTickerAdvancesUptimeAndStops() throws Exception {
        MonotonicClock clock = new MonotonicClock(new MonotonicClock.WallSource() {
            @Override
            public long nowMs() {
                return System.currentTimeMillis();
            }
        }, 10);
        clock.start();
        try {
            long deadline = System.currentTimeMillis() + 5000;
            while (clock.uptimeMs() < 50 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(clock.uptimeMs() >= 50, "uptime was " + clock.uptimeMs());
        } finally {
            clock.stop();
        }
    }

    /** HMIEvent reads the clock (HmiPoster): it must never wait for the ticker's monitor. */
    @Test
    void uptimeNeverWaitsForTheTicker() throws Exception {
        final MonotonicClock c = new MonotonicClock(new StepWall(), 10);
        final long[] read = {-1};
        Thread reader = new Thread() {
            public void run() {
                read[0] = c.uptimeMs();
            }
        };
        synchronized (c) { // the ticker (or start/stop) holding the clock's monitor
            reader.start();
            reader.join(1000);
            assertTrue(!reader.isAlive(), "uptimeMs returned while the monitor was held");
        }
        assertEquals(0, read[0]);
    }
}
