package mibsdk.runtime;

import mibsdk.platform.UiInput;
import mibsdk.platform.UiTable;

/**
 * The thread {@code mibsdk-ui-input}: takes user input from the {@link UiTable} (queued by the adapter on
 * stock threads) and hands it to {@link UiHub#dispatch}, which only queues work on plugin executors, so this
 * thread never runs plugin code. {@link #stop()} returns once the thread is gone (bounded wait): from then on
 * no input is dispatched. Starting after a stop does nothing.
 */
final class UiDispatcher {

    private static final long POLL_MS = 100;
    private static final long JOIN_MS = 2000;

    private final UiTable table;
    private final UiHub hub;
    private Thread thread; // guarded by this
    private boolean stopped; // guarded by this

    UiDispatcher(UiTable table, UiHub hub) {
        this.table = table;
        this.hub = hub;
    }

    synchronized void start() {
        if (thread != null || stopped) {
            return;
        }
        Thread t = new Thread("mibsdk-ui-input") {
            public void run() {
                loop();
            }
        };
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        thread = t;
        t.start();
    }

    /** Stops the thread and waits (up to about 2 s) for it to exit. Idempotent. */
    void stop() {
        Thread t;
        synchronized (this) {
            stopped = true;
            t = thread;
        }
        if (t == null || t == Thread.currentThread()) {
            return;
        }
        t.interrupt();
        try {
            t.join(JOIN_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** True while the thread is alive. */
    synchronized boolean isRunning() {
        return thread != null && thread.isAlive();
    }

    private synchronized boolean isStopped() {
        return stopped;
    }

    private void loop() {
        while (!isStopped()) {
            UiInput in;
            try {
                in = table.poll(POLL_MS);
            } catch (InterruptedException e) {
                continue; // stop() interrupts; the loop re-checks
            }
            if (in != null && !isStopped()) {
                hub.dispatch(in); // never throws
            }
        }
    }
}
