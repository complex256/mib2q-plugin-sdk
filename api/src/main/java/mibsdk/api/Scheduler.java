package mibsdk.api;

/** Runs tasks on SDK-owned daemon threads, never on a stock thread. */
public interface Scheduler {

    Subscription after(long delayMs, Runnable task);

    Subscription every(long periodMs, Runnable task);
}
