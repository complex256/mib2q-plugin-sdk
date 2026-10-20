package mibsdk.adapter.hqc;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import mibsdk.platform.HookTable;
import mibsdk.platform.PlatformHost;

/** java.lang.reflect.Proxy fakes for stock interfaces (only the host-repaired stock jar is on the test classpath). */
final class Fakes {

    private Fakes() {
    }

    /** One recorded call. */
    static final class Call {
        final String method;
        final Object[] args;

        Call(String method, Object[] args) {
            this.method = method;
            this.args = args == null ? new Object[0] : args;
        }
    }

    /** Records every non-Object call; optionally throws or answers. Object methods behave like Object's. */
    static class Recorder implements InvocationHandler {
        final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
        volatile Throwable toThrow;
        volatile Object answer;

        @Override
        public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
            if (m.getDeclaringClass() == Object.class) {
                switch (m.getName()) {
                    case "equals":
                        return proxy == args[0];
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    default:
                        return "fake@" + Integer.toHexString(System.identityHashCode(proxy));
                }
            }
            calls.add(new Call(m.getName(), args));
            if (toThrow != null) {
                throw toThrow;
            }
            return answer;
        }

        List<Call> named(String method) {
            List<Call> out = new ArrayList<>();
            synchronized (calls) {
                for (Call c : calls) {
                    if (c.method.equals(method)) {
                        out.add(c);
                    }
                }
            }
            return out;
        }
    }

    /** A PlatformHost recording each published event and the thread that published it. */
    static final class RecordingHost implements PlatformHost {
        final List<Object> events = Collections.synchronizedList(new ArrayList<>());
        final List<String> threads = Collections.synchronizedList(new ArrayList<>());
        final List<String> logs = Collections.synchronizedList(new ArrayList<>());
        final HookTable hooks = new HookTable(16);
        /** If set, publish blocks until it is opened (simulates a slow subscriber). */
        volatile CountDownLatch gate;
        final AtomicInteger featuresChanged = new AtomicInteger();

        @Override
        public void publish(Object apiEvent) {
            threads.add(Thread.currentThread().getName());
            CountDownLatch g = gate;
            if (g != null) {
                try {
                    g.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            events.add(apiEvent);
        }

        @Override
        public void featuresChanged() {
            featuresChanged.incrementAndGet();
        }

        @Override
        public HookTable hooks() {
            return hooks;
        }

        @Override
        public void log(String msg, Throwable t) {
            logs.add(t == null ? msg : msg + ": " + t);
        }

        List<Object> snapshot() {
            synchronized (events) {
                return new ArrayList<>(events);
            }
        }
    }

    /** Polls {@code cond} every 5 ms for up to {@code ms}; true once it holds. */
    static boolean eventually(long ms, BooleanSupplier cond) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (cond.getAsBoolean()) {
                return true;
            }
            Thread.sleep(5);
        }
        return cond.getAsBoolean();
    }

    @SuppressWarnings("unchecked")
    static <T> T fake(Class<T> iface, Recorder r) {
        return (T) Proxy.newProxyInstance(Fakes.class.getClassLoader(), new Class<?>[] {iface}, r);
    }
}
