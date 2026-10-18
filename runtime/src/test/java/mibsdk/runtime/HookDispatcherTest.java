package mibsdk.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import mibsdk.api.HookObserved;
import mibsdk.api.HookPoint;
import mibsdk.api.Listener;
import mibsdk.platform.HookTable;
import org.junit.jupiter.api.Test;

class HookDispatcherTest {

    private static SerialExecutor executor() {
        return new SerialExecutor("test", new FakeClock(), Long.MAX_VALUE, Long.MAX_VALUE,
                new SerialExecutor.Listener() {
                    public void onFailure(String label, Throwable t) {
                    }

                    public void onSlow(String label, long ms) {
                    }

                    public void onHung(String label) {
                    }
                }, false);
    }

    @Test
    void publishesObservationsAndIgnoresNullPoints() throws Exception {
        HookTable table = new HookTable(16);
        EventBus bus = new EventBus();
        SerialExecutor exec = executor();
        final List<HookObserved> got = Collections.synchronizedList(new ArrayList<HookObserved>());
        bus.subscribe("p", HookObserved.class, new Listener<HookObserved>() {
            public void on(HookObserved o) {
                got.add(o);
            }
        }, exec);
        HookDispatcher d = new HookDispatcher(table, bus);
        d.start();
        try {
            table.observed(null, "ignored", false);
            table.observed(HookPoint.PARKING_SOUND_SET, "v", true);
            for (int i = 0; i < 500 && got.isEmpty(); i++) {
                Thread.sleep(10);
            }
            Thread.sleep(50);
            assertEquals(1, got.size());
            assertEquals(HookPoint.PARKING_SOUND_SET, got.get(0).point());
            assertTrue(got.get(0).vetoed());
        } finally {
            d.stop();
            exec.shutdown();
        }
    }

    @Test
    void stopTerminatesPromptlyAndIsIdempotent() throws Exception {
        HookDispatcher d = new HookDispatcher(new HookTable(4), new EventBus());
        d.start();
        d.start();
        long t0 = System.nanoTime();
        d.stop();
        d.stop();
        assertTrue((System.nanoTime() - t0) / 1000000L < 3000, "stop must not wait for the poll timeout");
        assertEquals(0, countThreads(), "dispatcher thread is gone");
    }

    @Test
    void threadIsDaemonBelowNormPriority() throws Exception {
        HookDispatcher d = new HookDispatcher(new HookTable(4), new EventBus());
        d.start();
        try {
            Thread found = null;
            Thread[] all = new Thread[Thread.activeCount() + 8];
            int n = Thread.enumerate(all);
            for (int i = 0; i < n; i++) {
                if (all[i].getName().equals(HookDispatcher.THREAD_NAME)) {
                    found = all[i];
                }
            }
            assertTrue(found != null, "dispatcher thread exists");
            assertTrue(found.isDaemon());
            assertEquals(Thread.NORM_PRIORITY - 1, found.getPriority());
        } finally {
            d.stop();
        }
    }

    private static int countThreads() {
        Thread[] all = new Thread[Thread.activeCount() + 8];
        int n = Thread.enumerate(all);
        int c = 0;
        for (int i = 0; i < n; i++) {
            if (all[i].getName().equals(HookDispatcher.THREAD_NAME) && all[i].isAlive()) {
                c++;
            }
        }
        return c;
    }
}
