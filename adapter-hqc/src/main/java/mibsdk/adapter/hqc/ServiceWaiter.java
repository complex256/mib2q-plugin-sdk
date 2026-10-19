package mibsdk.adapter.hqc;

import mibsdk.runtime.Clock;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

/**
 * Waits for an OSGi service on SDK threads, never on the caller's: {@link #await} starts one daemon thread
 * (NORM-1) per service that polls {@code BundleContext.getServiceReferences(iface, null)} every {@code retryMs}
 * for {@link #FAST_ATTEMPTS} attempts, then every {@link #SLOW_MS}, and hands the first non-null
 * {@code getService} result to the callback on that thread. {@link #stop()} ends every wait: no poll and no
 * callback after it (a lookup already in flight is finished but not delivered). The {@link Clock} only times
 * the wait for the log.
 */
final class ServiceWaiter {

    /** Receives the service, on the waiter thread. */
    interface Callback {
        void onAvailable(Object service) throws Exception;
    }

    static final int FAST_ATTEMPTS = 50;
    static final long SLOW_MS = 2000;
    private static final String SOURCE = "mibsdk.hqc";

    private final BundleContext ctx;
    private final Clock clock;
    private final int fastAttempts;
    private final long slowMs;
    private boolean stopped; // guarded by this

    ServiceWaiter(BundleContext ctx, Clock clock) {
        this(ctx, clock, FAST_ATTEMPTS, SLOW_MS);
    }

    /** Tests: a shorter schedule. */
    ServiceWaiter(BundleContext ctx, Clock clock, int fastAttempts, long slowMs) {
        this.ctx = ctx;
        this.clock = clock;
        this.fastAttempts = fastAttempts;
        this.slowMs = slowMs;
    }

    /**
     * Starts waiting for {@code ifaceName} and returns at once. The result is the waiter thread (a handle for
     * tests), or null if this waiter is stopped.
     */
    Object await(final String ifaceName, final long retryMs, final Callback onAvailable) {
        Thread t = new Thread("mibsdk-wait-" + ifaceName.substring(ifaceName.lastIndexOf('.') + 1)) {
            public void run() {
                waitFor(ifaceName, retryMs, onAvailable);
            }
        };
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        synchronized (this) {
            if (stopped) {
                return null;
            }
        }
        t.start();
        return t;
    }

    /**
     * Ends every wait. Does not block. Idempotent. Wakes the waiters with notifyAll, never an interrupt: a waiter
     * may be inside a stock lookup, which must not see one.
     */
    synchronized void stop() {
        stopped = true;
        notifyAll();
    }

    private void waitFor(String iface, long retryMs, Callback cb) {
        long t0 = uptime();
        boolean lookupFailureLogged = false;
        for (int attempt = 1; ; attempt++) {
            Object service = null;
            try {
                service = lookup(iface);
            } catch (Throwable t) {
                if (!lookupFailureLogged) {
                    log("WARN", "lookup of " + iface + " failed (retrying): " + t);
                    lookupFailureLogged = true;
                }
            }
            synchronized (this) {
                if (stopped) {
                    return;
                }
                if (service == null) {
                    try {
                        wait(attempt <= fastAttempts ? retryMs : Math.max(retryMs, slowMs));
                    } catch (InterruptedException e) {
                        // a stray interrupt: the loop re-checks
                    }
                    if (stopped) {
                        return;
                    }
                    continue;
                }
            }
            log("INFO", iface + " available after " + (uptime() - t0) + " ms (" + attempt + " lookups)");
            try {
                cb.onAvailable(service);
            } catch (Throwable t) {
                log("ERROR", "attaching to " + iface + " failed: " + t);
            }
            return;
        }
    }

    private Object lookup(String iface) {
        ServiceReference[] refs = ctx.getServiceReferences(iface, null);
        if (refs == null) {
            return null;
        }
        for (int i = 0; i < refs.length; i++) {
            Object s = refs[i] == null ? null : ctx.getService(refs[i]);
            if (s != null) {
                return s;
            }
        }
        return null;
    }

    private long uptime() {
        try {
            return clock == null ? 0 : clock.uptimeMs();
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void log(String level, String msg) {
        try {
            SdkGlobals.LOG.append(level, SOURCE, msg);
        } catch (Throwable ignored) {
            // logging never ends a wait
        }
    }
}
