package mibsdk.adapter.hqc;

import static mibsdk.adapter.hqc.Fakes.eventually;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import mibsdk.runtime.Clock;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.junit.jupiter.api.Test;

class ServiceWaiterTest {

    private static final String IFACE = "org.dsi.ifc.carparkingsystem.DSICarParkingSystem";
    private static final Clock CLOCK = () -> 0L;

    /** A BundleContext whose getServiceReferences answers null until {@code availableAfter} polls. */
    static final class FakeContext {
        final AtomicInteger polls = new AtomicInteger();
        final List<Long> pollTimes = Collections.synchronizedList(new ArrayList<>());
        final List<String> pollThreads = Collections.synchronizedList(new ArrayList<>());
        final Object service = new Object();
        volatile int availableAfter;
        volatile long blockMs;
        volatile RuntimeException toThrow;

        BundleContext context() {
            ServiceReference ref = (ServiceReference) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {ServiceReference.class}, (p, m, a) -> m.getName().equals("hashCode") ? 1 : null);
            return (BundleContext) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {BundleContext.class}, (p, m, a) -> {
                        switch (m.getName()) {
                            case "getServiceReferences":
                                pollThreads.add(Thread.currentThread().getName());
                                pollTimes.add(System.nanoTime() / 1000000);
                                int n = polls.incrementAndGet();
                                if (blockMs > 0) {
                                    Thread.sleep(blockMs);
                                }
                                if (toThrow != null) {
                                    throw toThrow;
                                }
                                return n > availableAfter ? new ServiceReference[] {ref} : null;
                            case "getService":
                                return a[0] == ref ? service : null;
                            case "hashCode":
                                return System.identityHashCode(p);
                            case "equals":
                                return p == a[0];
                            case "toString":
                                return "fake-ctx";
                            default:
                                return null;
                        }
                    });
        }
    }

    @Test
    void scheduleConstants() {
        assertEquals(50, ServiceWaiter.FAST_ATTEMPTS);
        assertEquals(2000, ServiceWaiter.SLOW_MS);
        assertEquals(200, HqcPlatform.RETRY_MS);
    }

    @Test
    void neverPollsOnTheCallerThread() throws Exception {
        FakeContext f = new FakeContext();
        f.blockMs = 5000;
        ServiceWaiter w = new ServiceWaiter(f.context(), CLOCK);
        long t0 = System.nanoTime();
        w.await(IFACE, 200, s -> { });
        assertTrue((System.nanoTime() - t0) / 1000000 < 100, "await returns at once");
        assertTrue(eventually(2000, () -> f.pollThreads.size() == 1));
        assertNotEquals(Thread.currentThread().getName(), f.pollThreads.get(0));
        assertTrue(f.pollThreads.get(0).startsWith("mibsdk-wait"), f.pollThreads.get(0));
        w.stop();
    }

    @Test
    void retriesUntilAvailableThenCallsBackOnceOnTheWaiterThread() throws Exception {
        FakeContext f = new FakeContext();
        f.availableAfter = 3;
        List<Object> got = Collections.synchronizedList(new ArrayList<>());
        List<String> cbThreads = Collections.synchronizedList(new ArrayList<>());
        ServiceWaiter w = new ServiceWaiter(f.context(), CLOCK);
        Thread handle = (Thread) w.await(IFACE, 10, s -> {
            got.add(s);
            cbThreads.add(Thread.currentThread().getName());
        });
        handle.join(3000);
        assertFalse(handle.isAlive(), "the waiter thread ends once the service is delivered");
        assertEquals(1, got.size());
        assertSame(f.service, got.get(0));
        assertEquals(4, f.polls.get());
        assertTrue(cbThreads.get(0).startsWith("mibsdk-wait"));
        assertTrue(handle.isDaemon());
        assertEquals(Thread.NORM_PRIORITY - 1, handle.getPriority());
    }

    @Test
    void fastThenSlowSchedule() throws Exception {
        FakeContext f = new FakeContext();
        f.availableAfter = Integer.MAX_VALUE;
        ServiceWaiter w = new ServiceWaiter(f.context(), CLOCK, 3, 300);
        w.await(IFACE, 20, s -> { });
        assertTrue(eventually(3000, () -> f.polls.get() >= 6));
        w.stop();
        List<Long> t = new ArrayList<>(f.pollTimes);
        for (int i = 1; i <= 3; i++) {
            long gap = t.get(i) - t.get(i - 1);
            assertTrue(gap < 200, "fast retry " + i + ": " + gap);
        }
        for (int i = 4; i < 6; i++) {
            long gap = t.get(i) - t.get(i - 1);
            assertTrue(gap >= 250, "slow retry " + i + ": " + gap);
        }
    }

    @Test
    void stopEndsPolling() throws Exception {
        FakeContext f = new FakeContext();
        f.availableAfter = Integer.MAX_VALUE;
        ServiceWaiter w = new ServiceWaiter(f.context(), CLOCK);
        Thread handle = (Thread) w.await(IFACE, 10, s -> { });
        assertTrue(eventually(2000, () -> f.polls.get() >= 2));
        w.stop();
        handle.join(2000);
        assertFalse(handle.isAlive());
        int n = f.polls.get();
        Thread.sleep(100);
        assertEquals(n, f.polls.get());
        // await after stop does nothing
        w.await(IFACE, 10, s -> { });
        Thread.sleep(50);
        assertEquals(n, f.polls.get());
    }

    @Test
    void noCallbackAfterStop() throws Exception {
        FakeContext f = new FakeContext();
        f.blockMs = 200;
        List<Object> got = Collections.synchronizedList(new ArrayList<>());
        ServiceWaiter w = new ServiceWaiter(f.context(), CLOCK);
        Thread handle = (Thread) w.await(IFACE, 10, got::add);
        assertTrue(eventually(2000, () -> f.polls.get() == 1));
        w.stop(); // while the poll is in flight; it then finds the service
        handle.join(2000);
        assertEquals(0, got.size());
    }

    @Test
    void lookupFailuresAreRetried() throws Exception {
        FakeContext f = new FakeContext();
        f.toThrow = new IllegalStateException("bundle context gone");
        List<Object> got = Collections.synchronizedList(new ArrayList<>());
        ServiceWaiter w = new ServiceWaiter(f.context(), CLOCK);
        w.await(IFACE, 10, got::add);
        assertTrue(eventually(2000, () -> f.polls.get() >= 3));
        f.toThrow = null;
        assertTrue(eventually(2000, () -> got.size() == 1));
        w.stop();
    }
}
