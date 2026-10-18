package mibsdk.runtime;

import java.util.ArrayList;
import java.util.List;
import mibsdk.api.Scheduler;
import mibsdk.api.Subscription;

/**
 * A plugin's {@link Scheduler}. Due times are measured on the injected {@link Clock}. One shared daemon
 * timer thread (priority NORM-1) finds due entries and posts them to the owning plugin's
 * {@link SerialExecutor}; plugin code never runs on the timer thread.
 */
public final class PluginScheduler implements Scheduler {

    private static final long MAX_WAIT_MS = 100;

    private static final List ACTIVE = new ArrayList(); // PluginScheduler with entries
    private static Thread timer;
    private static boolean wake; // an entry was added since the timer last scanned; guarded by ACTIVE

    private final class Entry implements Subscription {
        final Runnable task;
        final long period; // 0 for one-shot
        long due; // guarded by PluginScheduler.this
        volatile boolean cancelled;
        volatile boolean queued; // posted to the executor and not yet started

        Entry(Runnable task, long due, long period) {
            this.task = task;
            this.due = due;
            this.period = period;
        }

        @Override
        public void cancel() {
            cancelled = true;
            remove(this);
        }
    }

    private final SerialExecutor exec;
    private final Clock clock;
    private final List entries = new ArrayList(); // Entry; guarded by this

    public PluginScheduler(SerialExecutor exec, Clock clock) {
        this.exec = exec;
        this.clock = clock;
    }

    @Override
    public Subscription after(long delayMs, Runnable task) {
        return add(new Entry(task, clock.uptimeMs() + Math.max(0, delayMs), 0));
    }

    @Override
    public Subscription every(long periodMs, Runnable task) {
        if (periodMs < 1) {
            throw new IllegalArgumentException("periodMs must be >= 1");
        }
        return add(new Entry(task, clock.uptimeMs() + periodMs, periodMs));
    }

    private Subscription add(Entry e) {
        synchronized (this) {
            entries.add(e);
        }
        activate(this);
        return e;
    }

    private synchronized void remove(Entry e) {
        entries.remove(e);
    }

    /** Posts every entry that is due now to the executor. Called by the timer thread; also the test hook. */
    void runDue() {
        long now = clock.uptimeMs();
        List fire = new ArrayList();
        synchronized (this) {
            for (int i = entries.size() - 1; i >= 0; i--) {
                Entry e = (Entry) entries.get(i);
                if (e.cancelled) {
                    entries.remove(i);
                } else if (e.due <= now) {
                    fire.add(e);
                    if (e.period == 0) {
                        entries.remove(i);
                    } else {
                        long next = e.due + e.period;
                        e.due = next > now ? next : now + e.period; // no catch-up bursts
                    }
                }
            }
        }
        for (int i = fire.size() - 1; i >= 0; i--) {
            post((Entry) fire.get(i));
        }
    }

    private void post(final Entry e) {
        if (e.queued) {
            return; // a periodic run is still waiting behind a slow task; skip rather than pile up
        }
        e.queued = true;
        boolean accepted = exec.submit("scheduled", new Runnable() {
            public void run() {
                e.queued = false;
                if (!e.cancelled) {
                    e.task.run();
                }
            }
        });
        if (!accepted) {
            e.queued = false;
            e.cancelled = true;
            remove(e);
        }
    }

    /** Ms until the earliest entry is due (0 if overdue), or -1 if there are no entries. */
    private synchronized long nextDelay() {
        long now = clock.uptimeMs();
        long best = -1;
        for (int i = 0; i < entries.size(); i++) {
            Entry e = (Entry) entries.get(i);
            long d = Math.max(0, e.due - now);
            if (best < 0 || d < best) {
                best = d;
            }
        }
        return best;
    }

    private static void activate(PluginScheduler s) {
        synchronized (ACTIVE) {
            if (!ACTIVE.contains(s)) {
                ACTIVE.add(s);
            }
            if (timer == null) {
                Thread t = new Thread("mibsdk-timer") {
                    public void run() {
                        timerLoop();
                    }
                };
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                timer = t;
                t.start();
            }
            wake = true;
            ACTIVE.notifyAll();
        }
    }

    private static void timerLoop() {
        try {
            while (true) {
                PluginScheduler[] snapshot;
                synchronized (ACTIVE) {
                    wake = false;
                    snapshot = (PluginScheduler[]) ACTIVE.toArray(new PluginScheduler[ACTIVE.size()]);
                }
                long wait = MAX_WAIT_MS;
                for (int i = 0; i < snapshot.length; i++) {
                    PluginScheduler s = snapshot[i];
                    try {
                        s.runDue();
                        long d = s.nextDelay();
                        if (d < 0) {
                            synchronized (ACTIVE) {
                                if (s.nextDelay() < 0) {
                                    ACTIVE.remove(s);
                                }
                            }
                        } else if (d < wait) {
                            wait = d;
                        }
                    } catch (Throwable ignored) {
                        // one broken scheduler must not stop the others
                    }
                }
                synchronized (ACTIVE) {
                    if (ACTIVE.isEmpty()) {
                        timer = null; // restarted by the next activate()
                        return;
                    }
                    try {
                        if (wake) {
                            continue;
                        }
                        ACTIVE.wait(Math.max(1, wait));
                    } catch (InterruptedException ignored) {
                        // keep going
                    }
                }
            }
        } finally {
            synchronized (ACTIVE) {
                if (timer == Thread.currentThread()) {
                    timer = null;
                }
            }
        }
    }
}
