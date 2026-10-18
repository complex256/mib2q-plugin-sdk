package mibsdk.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SerialExecutorTest {

    private static final class Recorder implements SerialExecutor.Listener {
        final List<String> failures = Collections.synchronizedList(new ArrayList<String>());
        final List<String> slow = Collections.synchronizedList(new ArrayList<String>());
        final List<String> hung = Collections.synchronizedList(new ArrayList<String>());

        @Override
        public void onFailure(String label, Throwable t) {
            failures.add(label + ":" + t.getMessage());
        }

        @Override
        public void onSlow(String label, long ms) {
            slow.add(label + ":" + ms);
        }

        @Override
        public void onHung(String label) {
            hung.add(label);
        }
    }

    private final FakeClock clock = new FakeClock();
    private final Recorder rec = new Recorder();
    private SerialExecutor exec;

    @AfterEach
    void tearDown() {
        if (exec != null) {
            exec.shutdown();
        }
    }

    @Test
    void runsTasksInOrderOffCallerThread() throws Exception {
        exec = new SerialExecutor("p1", clock, 5000, 30000, rec);
        final List<Integer> order = Collections.synchronizedList(new ArrayList<Integer>());
        final List<Thread> threads = Collections.synchronizedList(new ArrayList<Thread>());
        final CountDownLatch done = new CountDownLatch(3);
        for (int i = 0; i < 3; i++) {
            final int n = i;
            assertTrue(exec.submit("t" + n, new Runnable() {
                public void run() {
                    order.add(n);
                    threads.add(Thread.currentThread());
                    done.countDown();
                }
            }));
        }
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(java.util.Arrays.asList(0, 1, 2), order);
        Thread worker = threads.get(0);
        assertNotSame(Thread.currentThread(), worker);
        assertTrue(worker.isDaemon());
        assertEquals(Thread.NORM_PRIORITY - 1, worker.getPriority());
        assertEquals(threads.get(0), threads.get(2));
    }

    @Test
    void exceptionReportedNotPropagated() throws Exception {
        exec = new SerialExecutor("p1", clock, 5000, 30000, rec);
        final CountDownLatch next = new CountDownLatch(1);
        exec.submit("bad", new Runnable() {
            public void run() {
                throw new IllegalStateException("boom");
            }
        });
        exec.submit("good", new Runnable() {
            public void run() {
                next.countDown();
            }
        });
        assertTrue(next.await(5, TimeUnit.SECONDS));
        assertEquals(1, rec.failures.size());
        assertEquals("bad:boom", rec.failures.get(0));
    }

    @Test
    void errorsAreReportedToo() throws Exception {
        exec = new SerialExecutor("p1", clock, 5000, 30000, rec);
        final CountDownLatch next = new CountDownLatch(1);
        exec.submit("oom", new Runnable() {
            public void run() {
                throw new AssertionError("err");
            }
        });
        exec.submit("good", new Runnable() {
            public void run() {
                next.countDown();
            }
        });
        assertTrue(next.await(5, TimeUnit.SECONDS));
        assertEquals(1, rec.failures.size());
    }

    @Test
    void hungTaskReportedAndExecutorKeepsAccepting() throws Exception {
        exec = new SerialExecutor("p1", clock, 5000, 30000, rec);
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        exec.submit("stuck", new Runnable() {
            public void run() {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    // released by shutdown
                }
            }
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));

        exec.checkWatchdog();
        assertEquals(0, rec.hung.size());
        clock.advance(29999);
        exec.checkWatchdog();
        assertEquals(0, rec.hung.size());

        clock.advance(1);
        exec.checkWatchdog();
        exec.checkWatchdog();
        clock.advance(10000);
        exec.checkWatchdog();
        assertEquals(1, rec.hung.size());
        assertEquals("stuck", rec.hung.get(0));

        assertTrue(exec.submit("queued", new Runnable() {
            public void run() {}
        }));
        release.countDown();
    }

    @Test
    void slowTaskReportedOnCompletion() throws Exception {
        exec = new SerialExecutor("p1", clock, 5000, 30000, rec);
        final CountDownLatch done = new CountDownLatch(1);
        exec.submit("slowpoke", new Runnable() {
            public void run() {
                clock.advance(6000);
            }
        });
        exec.submit("after", new Runnable() {
            public void run() {
                done.countDown();
            }
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(1, rec.slow.size());
        assertEquals("slowpoke:6000", rec.slow.get(0));
        assertEquals(0, rec.hung.size());
    }

    @Test
    void submitAfterShutdownIsRejected() {
        exec = new SerialExecutor("p1", clock, 5000, 30000, rec);
        exec.shutdown();
        assertFalse(exec.submit("late", new Runnable() {
            public void run() {}
        }));
    }

    @Test
    void shutdownInterruptsRunningTaskWithoutWaiting() throws Exception {
        exec = new SerialExecutor("p1", clock, 5000, 30000, rec);
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch interrupted = new CountDownLatch(1);
        exec.submit("sleeper", new Runnable() {
            public void run() {
                started.countDown();
                try {
                    Thread.sleep(60000);
                } catch (InterruptedException e) {
                    interrupted.countDown();
                }
            }
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        exec.shutdown();
        assertTrue(interrupted.await(5, TimeUnit.SECONDS));
    }
}
