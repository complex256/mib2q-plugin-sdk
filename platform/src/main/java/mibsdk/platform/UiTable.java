package mibsdk.platform;

/**
 * Meeting point between the SDK runtime and the HMI adapter for the UI.
 *
 * <p>The runtime {@link #publish}es whole immutable {@link UiSnapshot}s; stock threads read the latest
 * through {@link #snapshot} (a volatile read: never null, never blocks), and adapter SDK threads wait for
 * a newer one with {@link #awaitNewer}. User actions flow back through {@link #input}, which stores into a
 * bounded ring (oldest dropped) under a monitor held only for the array store, and never throws. Only the
 * runtime ({@link #poll}) and adapter SDK threads ({@link #awaitNewer}) ever wait, each on its own monitor.
 */
public final class UiTable {

    private volatile UiSnapshot current = UiSnapshot.EMPTY;
    private final Object snapshotLock = new Object();

    // Input ring; guarded by inputLock.
    private final Object inputLock = new Object();
    private final UiInput[] ring;
    private int head;
    private int size;
    private volatile long dropped;

    /**
     * Longest single wait. Java 1.4 has no monotonic clock, so a wait is timed in slices of at most this long, each
     * counted as its wall-clock length clamped to {@code [1, slice]}: a wall-clock step can stretch a wait by at most
     * one slice and can never end it early or stall it.
     */
    static final long SLICE_MS = 100;

    /** A wall-clock source; the unit's may step by decades (it boots at 1970, GPS sets it later). */
    interface Wall {
        long nowMs();
    }

    private static final Wall SYSTEM = new Wall() {
        public long nowMs() {
            return System.currentTimeMillis();
        }
    };

    private final Wall wall;

    public UiTable(int inputCapacity) {
        this(inputCapacity, SYSTEM);
    }

    UiTable(int inputCapacity, Wall wall) {
        this.wall = wall;
        if (inputCapacity < 1) {
            throw new IllegalArgumentException("inputCapacity must be >= 1");
        }
        ring = new UiInput[inputCapacity];
    }

    // ---- runtime side ----

    /** Replaces the current snapshot (null is ignored) and wakes {@link #awaitNewer} callers. */
    public void publish(UiSnapshot s) {
        if (s == null) {
            return;
        }
        synchronized (snapshotLock) {
            current = s;
            snapshotLock.notifyAll();
        }
    }

    /**
     * Next input, oldest first. Waits up to {@code timeoutMs}; a value {@code <= 0} does not wait.
     * Returns null on timeout.
     */
    public UiInput poll(long timeoutMs) throws InterruptedException {
        long remaining = timeoutMs;
        synchronized (inputLock) {
            while (size == 0) {
                if (remaining <= 0) {
                    return null;
                }
                remaining -= waitSlice(inputLock, remaining);
            }
            UiInput in = ring[head];
            ring[head] = null;
            head = (head + 1) % ring.length;
            size--;
            return in;
        }
    }

    /**
     * Waits on {@code lock} (held by the caller) for at most {@code min(remaining, SLICE_MS)} and returns the time
     * to count against the timeout: the wall-clock time that passed, clamped to {@code [1, slice]}.
     */
    private long waitSlice(Object lock, long remaining) throws InterruptedException {
        long slice = remaining < SLICE_MS ? remaining : SLICE_MS;
        long before = wall.nowMs();
        lock.wait(slice);
        long passed = wall.nowMs() - before;
        return passed < 1 ? 1 : passed > slice ? slice : passed;
    }

    /** Inputs discarded because the ring was full. */
    public long droppedInputs() {
        return dropped;
    }

    // ---- adapter SDK threads ----

    /**
     * The current snapshot once its version differs from {@code version}. Waits up to {@code timeoutMs};
     * a value {@code <= 0} does not wait. Returns null on timeout.
     */
    public UiSnapshot awaitNewer(long version, long timeoutMs) throws InterruptedException {
        long remaining = timeoutMs;
        synchronized (snapshotLock) {
            while (current.version() == version) {
                if (remaining <= 0) {
                    return null;
                }
                remaining -= waitSlice(snapshotLock, remaining);
            }
            return current;
        }
    }

    // ---- stock-thread side ----

    /** The latest snapshot; never null, never blocks. */
    public UiSnapshot snapshot() {
        return current;
    }

    /** Queues a user action, dropping the oldest when full. Null is ignored. Never throws. */
    public void input(UiInput in) {
        if (in == null) {
            return;
        }
        try {
            synchronized (inputLock) {
                if (size == ring.length) {
                    ring[head] = in;
                    head = (head + 1) % ring.length;
                    dropped++;
                } else {
                    ring[(head + size) % ring.length] = in;
                    size++;
                }
                inputLock.notifyAll();
            }
        } catch (Throwable t) {
            // never propagate into the HMI
        }
    }
}
