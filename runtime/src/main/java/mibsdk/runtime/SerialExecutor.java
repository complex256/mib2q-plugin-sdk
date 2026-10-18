package mibsdk.runtime;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * One daemon worker thread (priority NORM-1) running a plugin's tasks strictly in order.
 *
 * <p>Java 1.4 cannot kill a thread, so a task that blocks forever is only <em>detected</em>:
 * {@link Listener#onHung} fires exactly once for it, {@link #submit} keeps accepting (queued tasks
 * simply wait behind the stuck one), and the runtime quarantines the plugin. Detection is driven by
 * the injected {@link Clock}: a single shared watchdog daemon thread polls every live executor once a
 * second and calls {@link #checkWatchdog()} (also called directly by tests with a fake clock).
 * {@code onHung} runs on the watchdog thread, so listeners must not block.
 */
public final class SerialExecutor {

    public interface Listener {
        void onFailure(String label, Throwable t);

        void onSlow(String label, long ms);

        void onHung(String label);
    }

    private static final class Task {
        final String label;
        final Runnable run;

        Task(String label, Runnable run) {
            this.label = label;
            this.run = run;
        }
    }

    private static final long DEFAULT_WATCHDOG_PERIOD_MS = 1000;
    /** Test hooks: poll period, and a callback run at the start of every watchdog iteration. */
    static volatile long watchdogPeriodMs = DEFAULT_WATCHDOG_PERIOD_MS;
    static volatile Runnable watchdogIterationHook;
    private static final List<SerialExecutor> LIVE = new ArrayList<SerialExecutor>();
    private static Thread watchdog;

    private final Clock clock;
    private final long slowMs;
    private final long hungMs;
    private final Listener listener;
    private final LinkedList<Task> queue = new LinkedList<Task>();
    private final Thread worker;
    private boolean shutdown;
    private String currentLabel; // null when idle
    private long currentStart;
    private boolean hungReported;

    public SerialExecutor(String name, Clock clock, long slowMs, long hungMs, Listener listener) {
        this(name, clock, slowMs, hungMs, listener, true);
    }

    /** {@code useWatchdog=false} skips the shared watchdog; tests then drive {@link #checkWatchdog()} by hand. */
    SerialExecutor(String name, Clock clock, long slowMs, long hungMs, Listener listener, boolean useWatchdog) {
        this.clock = clock;
        this.slowMs = slowMs;
        this.hungMs = hungMs;
        this.listener = listener;
        this.worker = new Thread("mibsdk-" + name) {
            public void run() {
                loop();
            }
        };
        worker.setDaemon(true);
        worker.setPriority(Thread.NORM_PRIORITY - 1);
        worker.start();
        if (useWatchdog) {
            register(this);
        }
    }

    /** Queues a task; false if the executor has been shut down. Never blocks. */
    public synchronized boolean submit(String label, Runnable task) {
        if (shutdown) {
            return false;
        }
        queue.add(new Task(label, task));
        notifyAll();
        return true;
    }

    /** Drops queued tasks and interrupts the worker; does not wait for it. */
    public void shutdown() {
        synchronized (this) {
            shutdown = true;
            queue.clear();
            notifyAll();
        }
        worker.interrupt();
        unregister(this);
    }

    /** Test hook: waits for the worker thread to exit (after shutdown). */
    boolean awaitWorkerExit(long timeoutMs) throws InterruptedException {
        worker.join(timeoutMs);
        return !worker.isAlive();
    }

    /** Reports the running task as hung (once) if it has exceeded hungMs on the clock. */
    void checkWatchdog() {
        String label;
        synchronized (this) {
            if (shutdown || currentLabel == null || hungReported || clock.uptimeMs() - currentStart < hungMs) {
                return;
            }
            hungReported = true;
            label = currentLabel;
        }
        try {
            listener.onHung(label);
        } catch (Throwable ignored) {
            // a listener must never break the watchdog
        }
    }

    private void loop() {
        while (true) {
            Task t;
            long start;
            synchronized (this) {
                while (!shutdown && queue.isEmpty()) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        // loop re-checks shutdown; a stray interrupt just resumes waiting
                    }
                }
                if (shutdown) {
                    return;
                }
                t = (Task) queue.removeFirst();
                // Clear a stale interrupt (done under the lock: shutdown() sets its flag under the same
                // lock before interrupting, so a real shutdown interrupt can never be swallowed here).
                Thread.interrupted();
                currentLabel = t.label;
                start = clock.uptimeMs();
                currentStart = start;
                hungReported = false;
            }
            try {
                t.run.run();
            } catch (Throwable e) {
                if (!isShutdown()) { // a task interrupted by shutdown is not a plugin failure
                    notifyFailure(t.label, e);
                }
            }
            boolean hung;
            synchronized (this) {
                hung = hungReported;
                currentLabel = null;
            }
            long elapsed = clock.uptimeMs() - start;
            if (!hung && elapsed >= slowMs && !isShutdown()) {
                try {
                    listener.onSlow(t.label, elapsed);
                } catch (Throwable ignored) {
                    // see checkWatchdog
                }
            }
        }
    }

    private synchronized boolean isShutdown() {
        return shutdown;
    }

    private void notifyFailure(String label, Throwable e) {
        try {
            listener.onFailure(label, e);
        } catch (Throwable ignored) {
            // see checkWatchdog
        }
    }

    private static synchronized void register(SerialExecutor e) {
        LIVE.add(e);
        if (watchdog == null) {
            Thread t = new Thread("mibsdk-watchdog") {
                public void run() {
                    watchLoop();
                }
            };
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            watchdog = t;
            t.start();
        }
    }

    private static synchronized void unregister(SerialExecutor e) {
        LIVE.remove(e);
    }

    /**
     * Polls every live executor. Each iteration is guarded so one failure (e.g. OutOfMemoryError) cannot
     * end hang detection; if the thread ever does end, it clears {@code watchdog} so register() restarts it.
     */
    private static void watchLoop() {
        try {
            while (true) {
                try {
                    Runnable hook = watchdogIterationHook;
                    if (hook != null) {
                        hook.run();
                    }
                    SerialExecutor[] snapshot;
                    synchronized (SerialExecutor.class) {
                        if (LIVE.isEmpty()) {
                            watchdog = null; // restarted by the next register()
                            return;
                        }
                        snapshot = (SerialExecutor[]) LIVE.toArray(new SerialExecutor[LIVE.size()]);
                    }
                    for (int i = 0; i < snapshot.length; i++) {
                        snapshot[i].checkWatchdog();
                    }
                } catch (Throwable ignored) {
                    // keep polling
                }
                try {
                    Thread.sleep(watchdogPeriodMs);
                } catch (InterruptedException ignored) {
                    // keep polling
                }
            }
        } finally {
            synchronized (SerialExecutor.class) {
                if (watchdog == Thread.currentThread()) {
                    watchdog = null;
                }
            }
        }
    }
}
