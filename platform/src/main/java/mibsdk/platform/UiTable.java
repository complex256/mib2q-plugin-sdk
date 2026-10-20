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

    public UiTable(int inputCapacity) {
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
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (inputLock) {
            while (size == 0) {
                long remaining = deadline - System.currentTimeMillis();
                if (timeoutMs <= 0 || remaining <= 0) {
                    return null;
                }
                inputLock.wait(remaining);
            }
            UiInput in = ring[head];
            ring[head] = null;
            head = (head + 1) % ring.length;
            size--;
            return in;
        }
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
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (snapshotLock) {
            while (current.version() == version) {
                long remaining = deadline - System.currentTimeMillis();
                if (timeoutMs <= 0 || remaining <= 0) {
                    return null;
                }
                snapshotLock.wait(remaining);
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
