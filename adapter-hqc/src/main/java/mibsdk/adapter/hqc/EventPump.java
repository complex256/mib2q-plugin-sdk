package mibsdk.adapter.hqc;

import java.util.LinkedList;
import mibsdk.platform.PlatformHost;

/**
 * The hand-off from stock DSI callback threads to the SDK: bridges {@link #post} small tasks, one SDK-owned daemon
 * thread (NORM-1) runs them in order, and only that thread calls {@link PlatformHost#publish} (which locks,
 * allocates, and downstream may write files). {@link #post} takes only this object's monitor, for a list add, and
 * never blocks or throws. The queue is bounded: when full, the oldest task is dropped and counted; the pump thread
 * logs the count.
 */
final class EventPump {

    private static final String THREAD_PREFIX = "mibsdk-";

    private final String name;
    private final PlatformHost host;
    private final int capacity;
    private final LinkedList queue = new LinkedList(); // guarded by this
    private boolean stopped; // guarded by this
    private boolean busy; // guarded by this: a task is running
    private long dropped; // guarded by this
    private long droppedLogged; // pump thread only
    private Thread thread; // guarded by this

    EventPump(String name, PlatformHost host, int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
        this.name = name;
        this.host = host;
        this.capacity = capacity;
    }

    /** Starts the pump thread; no-op if started or stopped. */
    synchronized void start() {
        if (thread != null || stopped) {
            return;
        }
        Thread t = new Thread(THREAD_PREFIX + name) {
            public void run() {
                loop();
            }
        };
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        thread = t;
        t.start();
    }

    /** Stops after the running task (if any); queued tasks are dropped. Does not wait. Idempotent. */
    synchronized void stop() {
        stopped = true;
        queue.clear();
        notifyAll();
    }

    /** Queues {@code task}; false if stopped. Callable from stock threads: never blocks, never throws. */
    boolean post(Runnable task) {
        try {
            synchronized (this) {
                if (stopped || task == null) {
                    return false;
                }
                if (queue.size() >= capacity) {
                    queue.removeFirst();
                    dropped++;
                }
                queue.addLast(task);
                notifyAll();
                return true;
            }
        } catch (Throwable t) {
            return false;
        }
    }

    /** Queues a {@code host.publish(event)}. Same contract as {@link #post}. */
    boolean publish(final Object event) {
        return post(new Runnable() {
            public void run() {
                host.publish(event);
            }
        });
    }

    /** Publishes at once. Only for tasks running on the pump thread (keeps the order of queued events). */
    void publishFromPump(Object event) {
        host.publish(event);
    }

    synchronized long droppedCount() {
        return dropped;
    }

    /** Test hook: waits until nothing is queued or running. */
    synchronized boolean awaitIdle(long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (busy || !queue.isEmpty()) {
            long left = end - System.currentTimeMillis();
            if (left <= 0) {
                return false;
            }
            wait(left);
        }
        return true;
    }

    private void loop() {
        while (true) {
            Runnable task;
            long d;
            synchronized (this) {
                busy = false;
                notifyAll(); // awaitIdle
                while (!stopped && queue.isEmpty()) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        // only stop() ends the pump
                    }
                }
                if (stopped) {
                    return;
                }
                task = (Runnable) queue.removeFirst();
                busy = true;
                d = dropped;
            }
            if (d != droppedLogged) {
                log("event queue full: dropped " + (d - droppedLogged) + " oldest (total " + d + ")", null);
                droppedLogged = d;
            }
            try {
                task.run();
            } catch (Throwable t) {
                log("platform event task failed", t);
            }
        }
    }

    private void log(String msg, Throwable t) {
        try {
            host.log(msg, t);
        } catch (Throwable ignored) {
            // logging never ends the pump
        }
    }
}
