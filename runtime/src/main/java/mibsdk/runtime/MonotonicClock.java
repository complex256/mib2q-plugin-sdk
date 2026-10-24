package mibsdk.runtime;

/**
 * Uptime derived from a wall-clock source that may jump (the unit boots at 1970 and GPS later steps
 * it by decades). Java 1.4 has no {@code System.nanoTime}, so a ticker thread samples the wall clock
 * every {@code tickMs} and adds {@code min(observed delta, 2 * tickMs)} to the uptime; negative deltas
 * add 0. A wall jump therefore costs at most {@code 2 * tickMs} of error. Resolution is one tick.
 */
public final class MonotonicClock implements Clock {

    public interface WallSource {
        long nowMs();
    }

    private final WallSource wall;
    private final long tickMs;
    private volatile long uptime; // one writer (tick, under the monitor); read lock-free
    private long lastWall;
    private Thread thread;

    public MonotonicClock(WallSource wall, long tickMs) {
        if (tickMs <= 0) {
            throw new IllegalArgumentException("tickMs must be positive");
        }
        this.wall = wall;
        this.tickMs = tickMs;
        this.lastWall = wall.nowMs();
    }

    /** Lock-free (a volatile read): HMIEvent reads it and must never wait for the ticker. */
    public long uptimeMs() {
        return uptime;
    }

    /** Starts the daemon ticker thread; no-op if already running. */
    public synchronized void start() {
        if (thread != null) {
            return;
        }
        lastWall = wall.nowMs();
        final Thread t = new Thread("mibsdk-clock") {
            public void run() {
                while (!isInterrupted()) {
                    try {
                        Thread.sleep(tickMs);
                    } catch (InterruptedException e) {
                        return;
                    }
                    tick();
                }
            }
        };
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        thread = t;
        t.start();
    }

    public synchronized void stop() {
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    /** One sampling step; package-private so tests drive it without sleeping. */
    synchronized void tick() {
        long now = wall.nowMs();
        long delta = now - lastWall;
        lastWall = now;
        if (delta > 0) {
            uptime += Math.min(delta, 2 * tickMs);
        }
    }
}
