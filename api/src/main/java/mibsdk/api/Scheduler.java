package mibsdk.api;

/**
 * Delayed and periodic tasks, run on the plugin's single SDK worker thread: a task never overlaps another of the
 * plugin's callbacks, and never runs on a stock HMI thread, on the runtime's timer thread, or on the thread that
 * scheduled it. A task that blocks delays the rest of this plugin, so keep one short; see the package documentation
 * for the slow (5 s) and hung (30 s) thresholds.
 *
 * <p>Everything scheduled here is cancelled when the plugin is disabled, quarantined or reloaded. Cancelling a
 * subscription twice, or after that, is harmless.
 */
public interface Scheduler {

    /**
     * Runs {@code task} once, no sooner than {@code delayMs} from now (0 runs as soon as the worker is free).
     * Cancelling the returned subscription before it runs stops it.
     *
     * @param delayMs milliseconds to wait; a negative value counts as 0
     * @param task the work, run on the plugin's worker thread
     * @return a handle that withdraws the task, if it has not started
     */
    Subscription after(long delayMs, Runnable task);

    /**
     * Runs {@code task} every {@code periodMs} from now: the first run is one period away, and a run that comes due
     * while the previous one is still queued is skipped rather than piled up, so a slow task does not produce a burst
     * of catch-up runs. Cancelling the returned subscription stops further runs.
     *
     * @param periodMs period in milliseconds; must be at least 1, else {@link IllegalArgumentException}
     * @param task the work, run on the plugin's worker thread
     * @return a handle that stops the repeats
     */
    Subscription every(long periodMs, Runnable task);
}
