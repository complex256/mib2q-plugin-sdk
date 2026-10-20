package mibsdk.adapter.hqc.ui;

import de.audi.atip.hmi.event.RunnableEvent;
import de.audi.atip.hmi.model.AbstractModelBank;

/**
 * Posts SDK jobs to HMIEvent with at most one pending: {@link #post} returns false while a job is queued or running.
 * Each job runs inside {@code try/catch Throwable}; {@code pending} clears in {@code finally}, then {@code afterJob}
 * runs (also caught), so the owner can post again if its state moved on while the job ran.
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

    private final Sink sink;
    private final Runnable afterJob;
    private boolean pending; // guarded by this

    HmiPoster(Sink sink, Runnable afterJob) {
        this.sink = sink;
        this.afterJob = afterJob;
    }

    /**
     * Queues {@code job} unless one is already pending. False if one is pending, or if the sink refused it (then
     * nothing is pending and the caller may retry).
     */
    boolean post(final Runnable job) {
        synchronized (this) {
            if (pending) {
                return false;
            }
            pending = true;
        }
        try {
            sink.post(new Runnable() {
                public void run() {
                    runJob(job);
                }
            });
            return true;
        } catch (Throwable t) {
            synchronized (this) {
                pending = false;
            }
            return false;
        }
    }

    synchronized boolean pending() {
        return pending;
    }

    /** On HMIEvent: never throws. */
    private void runJob(Runnable job) {
        try {
            job.run();
        } catch (Throwable t) {
            // a failing job never breaks HMIEvent dispatching
        } finally {
            synchronized (this) {
                pending = false;
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
