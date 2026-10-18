package mibsdk.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import mibsdk.api.IgnitionChanged;
import mibsdk.api.Listener;
import mibsdk.api.Subscription;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class EventBusTest {

    private static final class Failures implements SerialExecutor.Listener {
        final List<String> failures = Collections.synchronizedList(new ArrayList<String>());

        @Override
        public void onFailure(String label, Throwable t) {
            failures.add(t.getMessage());
        }

        @Override
        public void onSlow(String label, long ms) {
        }

        @Override
        public void onHung(String label) {
        }
    }

    private final FakeClock clock = new FakeClock();
    private final Failures failures = new Failures();
    private final List<SerialExecutor> executors = new ArrayList<SerialExecutor>();
    private final EventBus bus = new EventBus();

    private SerialExecutor executor(String name) {
        SerialExecutor e = new SerialExecutor(name, clock, 1000, 5000, failures, false);
        executors.add(e);
        return e;
    }

    /** Waits until everything queued on the executor so far has run. */
    private static void drain(SerialExecutor e) throws InterruptedException {
        final CountDownLatch done = new CountDownLatch(1);
        e.submit("drain", new Runnable() {
            public void run() {
                done.countDown();
            }
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
    }

    @AfterEach
    void tearDown() {
        for (SerialExecutor e : executors) {
            e.shutdown();
        }
    }

    @Test
    void deliversOnPluginExecutorNotPublisherThread() throws Exception {
        SerialExecutor exec = executor("plugin-a");
        final List<String> threads = Collections.synchronizedList(new ArrayList<String>());
        final CountDownLatch got = new CountDownLatch(1);
        bus.subscribe("a", IgnitionChanged.class, new Listener<IgnitionChanged>() {
            public void on(IgnitionChanged e) {
                threads.add(Thread.currentThread().getName());
                got.countDown();
            }
        }, exec);

        bus.publish(new IgnitionChanged(true));

        assertTrue(got.await(5, TimeUnit.SECONDS));
        assertEquals(1, threads.size());
        assertTrue(threads.get(0).endsWith("plugin-a"), threads.get(0));
        assertNotEquals(Thread.currentThread().getName(), threads.get(0));
    }

    @Test
    void subtypeAndExactTypeMatch() throws Exception {
        SerialExecutor exec = executor("plugin-a");
        final List<Object> exact = Collections.synchronizedList(new ArrayList<Object>());
        final List<Object> any = Collections.synchronizedList(new ArrayList<Object>());
        final List<Object> other = Collections.synchronizedList(new ArrayList<Object>());
        bus.subscribe("a", IgnitionChanged.class, new Listener<IgnitionChanged>() {
            public void on(IgnitionChanged e) {
                exact.add(e);
            }
        }, exec);
        bus.subscribe("a", Object.class, new Listener<Object>() {
            public void on(Object e) {
                any.add(e);
            }
        }, exec);
        bus.subscribe("a", String.class, new Listener<String>() {
            public void on(String e) {
                other.add(e);
            }
        }, exec);

        bus.publish(new IgnitionChanged(true));
        drain(exec);

        assertEquals(1, exact.size());
        assertEquals(1, any.size());
        assertTrue(other.isEmpty());
    }

    @Test
    void cancelledSubscriptionStopsDelivery() throws Exception {
        SerialExecutor exec = executor("plugin-a");
        final List<Object> seen = Collections.synchronizedList(new ArrayList<Object>());
        Subscription s = bus.subscribe("a", IgnitionChanged.class, new Listener<IgnitionChanged>() {
            public void on(IgnitionChanged e) {
                seen.add(e);
            }
        }, exec);

        bus.publish(new IgnitionChanged(true));
        drain(exec);
        s.cancel();
        bus.publish(new IgnitionChanged(false));
        drain(exec);

        assertEquals(1, seen.size());
    }

    @Test
    void cancelAfterQueuedButBeforeRunSuppressesDelivery() throws Exception {
        SerialExecutor exec = executor("plugin-a");
        final CountDownLatch blocked = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        exec.submit("block", new Runnable() {
            public void run() {
                blocked.countDown();
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                }
            }
        });
        assertTrue(blocked.await(5, TimeUnit.SECONDS));
        final List<Object> seen = Collections.synchronizedList(new ArrayList<Object>());
        Subscription s = bus.subscribe("a", IgnitionChanged.class, new Listener<IgnitionChanged>() {
            public void on(IgnitionChanged e) {
                seen.add(e);
            }
        }, exec);
        bus.publish(new IgnitionChanged(true));
        s.cancel();
        release.countDown();
        drain(exec);

        assertTrue(seen.isEmpty());
    }

    @Test
    void listenerExceptionGoesToExecutorFailurePathAndOthersStillRun() throws Exception {
        SerialExecutor exec = executor("plugin-a");
        final List<Object> seen = Collections.synchronizedList(new ArrayList<Object>());
        bus.subscribe("a", IgnitionChanged.class, new Listener<IgnitionChanged>() {
            public void on(IgnitionChanged e) throws Exception {
                throw new Exception("boom");
            }
        }, exec);
        bus.subscribe("a", IgnitionChanged.class, new Listener<IgnitionChanged>() {
            public void on(IgnitionChanged e) {
                seen.add(e);
            }
        }, exec);

        bus.publish(new IgnitionChanged(true));
        drain(exec);

        assertEquals(1, failures.failures.size());
        assertEquals("boom", failures.failures.get(0));
        assertEquals(1, seen.size());
        assertFalse(failures.failures.contains(null));
    }

    @Test
    void subscriptionOnShutDownExecutorIsPruned() {
        SerialExecutor exec = executor("plugin-a");
        bus.subscribe("a", IgnitionChanged.class, new Listener<IgnitionChanged>() {
            public void on(IgnitionChanged e) {
            }
        }, exec);
        assertEquals(1, bus.subscriberCount());
        exec.shutdown();

        bus.publish(new IgnitionChanged(true));

        assertEquals(0, bus.subscriberCount());
    }
}
