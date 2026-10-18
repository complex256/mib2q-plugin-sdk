package mibsdk.runtime;

/** Counts failures inside a sliding window; the threshold-th failure within the window means quarantine. */
public final class FailureTracker {

    private final int threshold;
    private final long windowMs;
    private final Clock clock;
    private final long[] times;
    private int head;
    private int size;

    public FailureTracker(int threshold, long windowMs, Clock clock) {
        if (threshold < 1) {
            throw new IllegalArgumentException("threshold must be >= 1");
        }
        this.threshold = threshold;
        this.windowMs = windowMs;
        this.clock = clock;
        this.times = new long[threshold];
    }

    /** Records a failure now. Returns true when {@code threshold} failures fall inside the window. */
    public synchronized boolean recordFailure() {
        long now = clock.uptimeMs();
        while (size > 0 && now - times[head] > windowMs) {
            head = (head + 1) % threshold;
            size--;
        }
        if (size == threshold) { // already at threshold: drop the oldest to make room
            head = (head + 1) % threshold;
            size--;
        }
        times[(head + size) % threshold] = now;
        size++;
        return size >= threshold;
    }
}
