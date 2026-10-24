package mibsdk.adapter.hqc.ui;

import de.audi.atip.hmi.event.RunnableEvent;
import de.audi.atip.hmi.model.AbstractModelBank;
import mibsdk.runtime.Clock;

/**
 * Posts SDK jobs to HMIEvent with at most one pending: {@link #post} returns false while a job is queued or running.
 * Each job runs inside {@code try/catch Throwable}; {@code pending} clears in {@code finally}, then {@code afterJob}
 * runs (also caught), so the owner can post again if its state moved on while the job ran.
 *
 * <p>A pending token expires after {@code pendingDeadlineMs} on the monotonic clock: before the first screen the stock
 * StartupFilter silently discards a {@code RunnableEvent(false, r)} (postEvent returns normally), so a job may never
 * run. After the deadline the next {@link #post} supersedes it; each post carries a generation, and a superseded job
 * that does run late is ignored (it runs nothing and frees nothing).
 */
final class HmiPoster {

    /** Where jobs go; the real one queues a {@code RunnableEvent(false, r)} on the HMI event dispatcher. */
    interface Sink {
        void post(Runnable r) throws Exception;
    }

    /** {@code AbstractModelBank.getHMIservice().getEventDispatcher().postEvent(new RunnableEvent(false, r))}. */
    static final Sink HMI_EVENT = new Sink() {
        public void post(Runnable r) {
            AbstractModelBank.getHMIservice().getEventDispatcher().postEvent(new RunnableEvent(false, r));
        }
    };

    /** The HMI's job time limit is 200 ms: a job not run within 5 s was dropped. */
    static final long PENDING_DEADLINE_MS = 5000;

    private final Sink sink;
    private final Runnable afterJob;
    private final Clock clock;
    private final long pendingDeadlineMs;
    private boolean pending; // guarded by this
    private long postedAt; // guarded by this
    private int generation; // guarded by this

    HmiPoster(Sink sink, Runnable afterJob, Clock clock, long pendingDeadlineMs) {
        this.sink = sink;
        this.afterJob = afterJob;
        this.clock = clock;
        this.pendingDeadlineMs = pendingDeadlineMs;
    }

    /**
     * Queues {@code job} unless one is pending and not yet past the deadline. False if one is pending, or if the sink
     * refused it (then nothing is pending and the caller may retry).
     */
    boolean post(final Runnable job) {
        final int g;
        synchronized (this) {
            long now = clock.uptimeMs();
            if (pending && now - postedAt < pendingDeadlineMs) {
                return false;
            }
            pending = true;
            postedAt = now;
            g = ++generation;
        }
        try {
            sink.post(new Runnable() {
                public void run() {
                    runJob(job, g);
                }
            });
            return true;
        } catch (Throwable t) {
            synchronized (this) {
                if (generation == g) {
                    pending = false;
                }
            }
            return false;
        }
    }

    synchronized boolean pending() {
        return pending;
    }

    /** On HMIEvent: never throws. A superseded job (generation moved on) does nothing. */
    private void runJob(Runnable job, int g) {
        synchronized (this) {
            if (generation != g) {
                return;
            }
        }
        try {
            job.run();
        } catch (Throwable t) {
            // a failing job never breaks HMIEvent dispatching
        } finally {
            synchronized (this) {
                if (generation == g) {
                    pending = false;
                }
            }
        }
        try {
            if (afterJob != null) {
                afterJob.run();
            }
        } catch (Throwable t) {
            // as above
        }
    }
}
