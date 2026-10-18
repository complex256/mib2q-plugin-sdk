package mibsdk.platform;

import java.util.ArrayList;
import java.util.List;
import mibsdk.api.HookObserved;
import mibsdk.api.HookPoint;

/**
 * Meeting point between the stock HMI threads and the SDK runtime.
 *
 * <p>{@link #isVetoed} and {@link #observed} are called on stock threads: they never block, never throw
 * and never take a lock the runtime side can hold for long. Veto state is a volatile copy-on-write
 * snapshot; observations go into a bounded ring (oldest dropped) guarded by a monitor that the producer
 * holds only for an array store. Only the consumer ({@link #poll}) ever waits.
 */
public final class HookTable {

    private static final Object[] NONE = new Object[0];

    private static final class Veto {
        final int point;
        boolean active; // guarded by vetoLock

        Veto(int point) {
            this.point = point;
        }
    }

    // Runtime side.
    private final Object vetoLock = new Object();
    private final List vetoes = new ArrayList(); // all registered Veto; guarded by vetoLock
    /** Per point: the currently active tokens. Replaced, never mutated. */
    private volatile Object[][] active;

    // Observation ring; guarded by queueLock.
    private final Object queueLock = new Object();
    private final HookObserved[] ring;
    private int head;
    private int size;
    private volatile long dropped;

    public HookTable(int observationCapacity) {
        if (observationCapacity < 1) {
            throw new IllegalArgumentException("observationCapacity must be >= 1");
        }
        ring = new HookObserved[observationCapacity];
        Object[][] initial = new Object[HookPoint.values().length][];
        for (int i = 0; i < initial.length; i++) {
            initial[i] = NONE;
        }
        active = initial;
    }

    // ---- stock-thread side ----

    public boolean isVetoed(HookPoint p) {
        try {
            return active[p.ordinal()].length > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    public void observed(HookPoint p, Object value, boolean vetoed) {
        try {
            HookObserved o = new HookObserved(p, value, vetoed);
            synchronized (queueLock) {
                if (size == ring.length) {
                    ring[head] = o;
                    head = (head + 1) % ring.length;
                    dropped++;
                } else {
                    ring[(head + size) % ring.length] = o;
                    size++;
                }
                queueLock.notifyAll();
            }
        } catch (Throwable t) {
            // never propagate into the HMI
        }
    }

    // ---- runtime side ----

    /** Registers an inactive veto for {@code p} and returns its token. */
    public Object addVeto(HookPoint p) {
        if (p == null) {
            throw new IllegalArgumentException("point");
        }
        Veto v = new Veto(p.ordinal());
        synchronized (vetoLock) {
            vetoes.add(v);
        }
        return v;
    }

    public void setVetoActive(Object token, boolean isActive) {
        if (!(token instanceof Veto)) {
            return;
        }
        Veto v = (Veto) token;
        synchronized (vetoLock) {
            if (v.active == isActive || !vetoes.contains(v)) {
                return;
            }
            v.active = isActive;
            publishActive();
        }
    }

    public void removeVeto(Object token) {
        if (!(token instanceof Veto)) {
            return;
        }
        synchronized (vetoLock) {
            if (vetoes.remove(token)) {
                ((Veto) token).active = false;
                publishActive();
            }
        }
    }

    /** Rebuilds the snapshot. Caller holds {@code vetoLock}. */
    private void publishActive() {
        List[] byPoint = new List[HookPoint.values().length];
        for (int i = 0; i < vetoes.size(); i++) {
            Veto v = (Veto) vetoes.get(i);
            if (v.active) {
                if (byPoint[v.point] == null) {
                    byPoint[v.point] = new ArrayList();
                }
                byPoint[v.point].add(v);
            }
        }
        Object[][] next = new Object[byPoint.length][];
        for (int i = 0; i < next.length; i++) {
            next[i] = byPoint[i] == null ? NONE : byPoint[i].toArray();
        }
        active = next;
    }

    /**
     * Next observation, oldest first. Waits up to {@code timeoutMs}; a value {@code <= 0} does not wait.
     * Returns null on timeout.
     */
    public HookObserved poll(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (queueLock) {
            while (size == 0) {
                long remaining = deadline - System.currentTimeMillis();
                if (timeoutMs <= 0 || remaining <= 0) {
                    return null;
                }
                queueLock.wait(remaining);
            }
            HookObserved o = ring[head];
            ring[head] = null;
            head = (head + 1) % ring.length;
            size--;
            return o;
        }
    }

    /** Observations discarded because the ring was full. */
    public long droppedCount() {
        return dropped;
    }
}
