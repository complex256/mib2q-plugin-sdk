package mibsdk.runtime;

import mibsdk.api.HookObserved;
import mibsdk.platform.HookTable;

/**
 * Moves observations from the {@link HookTable} (filled on stock HMI threads) to the {@link EventBus}.
 * It never runs plugin code: the bus queues each observation on the subscribing plugin's own executor.
 * Veto decisions do not pass through here at all; the table answers them on the calling stock thread.
 */
public final class HookDispatcher {

    static final String THREAD_NAME = "mibsdk-hooks";
    /** Upper bound for one wait on the table, so {@link #stop()} is noticed even if the interrupt is lost. */
    private static final long POLL_MS = 1000;
    private static final long JOIN_MS = 2000;

    private final HookTable table;
    private final EventBus bus;
    private final Object lock = new Object();
    private Thread thread; // guarded by lock
    private boolean stopped; // guarded by lock

    public HookDispatcher(HookTable table, EventBus bus) {
        this.table = table;
        this.bus = bus;
    }

    /** Starts the dispatcher thread. Does nothing if already started or stopped. */
    public void start() {
        synchronized (lock) {
            if (thread != null || stopped) {
                return;
            }
            Thread t = new Thread(new Runnable() {
                public void run() {
                    loop();
                }
            }, THREAD_NAME);
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            thread = t;
            t.start();
        }
    }

    /** Stops the thread and waits briefly for it. Idempotent; the dispatcher cannot be restarted. */
    public void stop() {
        Thread t;
        synchronized (lock) {
            stopped = true;
            t = thread;
        }
        if (t == null) {
            return;
        }
        t.interrupt();
        try {
            t.join(JOIN_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean isStopped() {
        synchronized (lock) {
            return stopped;
        }
    }

    private void loop() {
        while (!isStopped()) {
            try {
                HookObserved o = table.poll(POLL_MS);
                if (o != null && o.point() != null) {
                    bus.publish(o);
                }
            } catch (InterruptedException e) {
                // re-check stopped
            } catch (Throwable t) {
                // one bad observation must not end delivery
            }
        }
    }
}
