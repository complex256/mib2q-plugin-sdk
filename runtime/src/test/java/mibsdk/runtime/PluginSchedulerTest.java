package mibsdk.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import mibsdk.api.Subscription;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PluginSchedulerTest {

    private static final Clock REAL = new Clock() {
        public long uptimeMs() {
            return System.nanoTime() / 1000000L;
        }
    };

    private final FakeClock clock = new FakeClock();
    private final SerialExecutor exec = new SerialExecutor("sched", clock, 1000, 5000, new SerialExecutor.Listener() {
        public void onFailure(String label, Throwable t) {
        }

        public void onSlow(String label, long ms) {
        }

        public void onHung(String label) {
        }
    }, false);
    private final PluginScheduler scheduler = new PluginScheduler(exec, clock);

    @AfterEach
    void tearDown() {
        exec.shutdown();
    }

    private void drain() throws InterruptedException {
        final CountDownLatch done = new CountDownLatch(1);
        exec.submit("drain", new Runnable() {
            public void run() {
                done.countDown();
            }
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
    }

    private static Runnable counter(final AtomicInteger n) {
        return new Runnable() {
            public void run() {
                n.incrementAndGet();
            }
        };
    }

    @Test
    void afterRunsOnceWhenDueOnExecutorThread() throws Exception {
        final AtomicInteger n = new AtomicInteger();
        final String[] thread = new String[1];
        scheduler.after(100, new Runnable() {
            public void run() {
                thread[0] = Thread.currentThread().getName();
                n.incrementAndGet();
            }
        });

        clock.advance(99);
        scheduler.runDue();
        drain();
        assertEquals(0, n.get());

        clock.advance(1);
        scheduler.runDue();
        scheduler.runDue();
        drain();
        assertEquals(1, n.get());
        assertTrue(thread[0].endsWith("sched"));
    }

    @Test
    void everyRepeatsUntilCancelled() throws Exception {
        AtomicInteger n = new AtomicInteger();
        Subscription s = scheduler.every(100, counter(n));

        for (int i = 0; i < 3; i++) {
            clock.advance(100);
            scheduler.runDue();
            drain();
        }
        assertEquals(3, n.get());

        s.cancel();
        clock.advance(500);
        scheduler.runDue();
        drain();
        assertEquals(3, n.get());
    }

    @Test
    void cancelledAfterStopsFutureRun() throws Exception {
        AtomicInteger n = new AtomicInteger();
        Subscription s = scheduler.after(100, counter(n));
        s.cancel();

        clock.advance(200);
        scheduler.runDue();
        drain();

        assertEquals(0, n.get());
    }

    @Test
    void realTimerThreadFiresWithRealClock() throws Exception {
        SerialExecutor e = new SerialExecutor("real", REAL, 1000, 5000,
                new SerialExecutor.Listener() {
                    public void onFailure(String label, Throwable t) {
                    }

                    public void onSlow(String label, long ms) {
                    }

                    public void onHung(String label) {
                    }
                }, false);
        try {
            PluginScheduler s = new PluginScheduler(e, REAL);
            final CountDownLatch fired = new CountDownLatch(1);
            s.after(30, new Runnable() {
                public void run() {
                    fired.countDown();
                }
            });
            assertTrue(fired.await(5, TimeUnit.SECONDS));
        } finally {
            e.shutdown();
        }
    }
}
