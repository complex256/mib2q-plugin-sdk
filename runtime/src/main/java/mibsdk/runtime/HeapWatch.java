package mibsdk.runtime;

/**
 * Low-heap guard. J9 1.4 has no per-plugin heap limit, so the runtime samples free heap every heapCheckMs
 * (on its control thread, via the shared timer) and, after {@code lowChecks} consecutive readings below
 * {@code lowBytes}, it asks for a full GC once and reads again ({@code freeMemory()} also counts garbage not yet
 * collected), and only if that reading is still low quarantines the most recently enabled plugin; either way
 * it then starts counting afresh. A reading at or above {@code lowBytes} breaks the streak.
 * {@code lowBytes = 0} disables it (readings are never negative).
 *
 * <p>With no plugin enabled there is nothing to quarantine and the heap is the HMI's own: no GC (a full GC on
 * the unit stalls the HMI) and no victim. After a GC that did not help, the check interval doubles (checks are
 * skipped), up to {@link #MAX_BACKOFF} times heapCheckMs, so a heap the plugins do not explain is not
 * GC-hammered; the first reading at or above {@code lowBytes} restores the normal interval.
 * Not thread-safe: one caller ({@link #check()} on the control thread).
 */
public final class HeapWatch {

    public interface MemorySource {
        long freeBytes();
    }

    public interface Victim {
        /** True if some plugin is enabled (else neither a GC nor a quarantine can help). */
        boolean anyEnabled();

        void quarantineMostRecentlyEnabled(String reason);
    }

    /** The unit's j9 heap limit: memory the heap has not grown into yet still counts as free. */
    static final long UNIT_HEAP_BYTES = 60L * 1024 * 1024;
    static final String REASON = "heap low";
    /** Longest check interval, in multiples of heapCheckMs. */
    static final int MAX_BACKOFF = 16;

    private static final MemorySource RUNTIME = new MemorySource() {
        public long freeBytes() {
            Runtime r = Runtime.getRuntime();
            long ungrown = UNIT_HEAP_BYTES - r.totalMemory();
            return r.freeMemory() + (ungrown > 0 ? ungrown : 0); // clamped: a host JVM may exceed 60 MB
        }
    };

    private static final Runnable SYSTEM_GC = new Runnable() {
        public void run() {
            System.gc();
        }
    };

    private final MemorySource mem;
    private final long lowBytes;
    private final int lowChecks;
    private final Victim victim;
    private final Runnable gc;
    private int lowStreak;
    private int multiplier = 1; // check every multiplier-th call
    private int skip; // calls left to skip before the next reading

    public HeapWatch(MemorySource mem, long lowBytes, int lowChecks, Victim victim) {
        this(mem, lowBytes, lowChecks, victim, SYSTEM_GC);
    }

    /** {@code gc} replaces {@code System.gc()} (tests). */
    HeapWatch(MemorySource mem, long lowBytes, int lowChecks, Victim victim, Runnable gc) {
        this.gc = gc;
        this.mem = mem;
        this.lowBytes = lowBytes;
        this.lowChecks = lowChecks < 1 ? 1 : lowChecks;
        this.victim = victim;
    }

    /** Production source: {@code Runtime.freeMemory() + (60 MB - totalMemory())}. */
    static MemorySource runtimeMemory() {
        return RUNTIME;
    }

    /**
     * {@code heap free=<bytes> total=<bytes>}: free as the watch counts it (freeMemory plus the heap not grown
     * into yet), total = the current heap size. Logged once at start for the bench baseline.
     */
    static String statusLine() {
        return "heap free=" + RUNTIME.freeBytes() + " total=" + Runtime.getRuntime().totalMemory();
    }

    /** The current check interval in multiples of heapCheckMs (tests). */
    int intervalMultiplier() {
        return multiplier;
    }

    /** One timer tick; takes a reading unless backing off. Never throws. */
    public void check() {
        if (skip > 0) {
            skip--;
            return;
        }
        skip = multiplier - 1;
        try {
            if (mem.freeBytes() >= lowBytes) {
                lowStreak = 0;
                multiplier = 1;
                skip = 0;
                return;
            }
            if (!victim.anyEnabled()) {
                lowStreak = 0;
                return;
            }
            if (++lowStreak < lowChecks) {
                return;
            }
            lowStreak = 0;
            gc.run(); // the low readings may be garbage; quarantining frees nothing visible before a GC either
            if (mem.freeBytes() < lowBytes) {
                multiplier = multiplier >= MAX_BACKOFF / 2 ? MAX_BACKOFF : multiplier * 2;
                skip = multiplier - 1;
                victim.quarantineMostRecentlyEnabled(REASON);
            }
        } catch (Throwable t) {
            // a broken source or victim must not break the timer task; the next check tries again
        }
    }
}
