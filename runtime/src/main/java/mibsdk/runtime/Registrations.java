package mibsdk.runtime;

import java.util.ArrayList;
import java.util.List;
import mibsdk.api.Subscription;

/**
 * Collects a plugin's subscriptions so they can all be cancelled when it stops. Once
 * {@link #cancelAll()} has run the collection is closed: a later {@link #add} cancels immediately,
 * so a callback still running during disable cannot leak a registration.
 */
public final class Registrations {

    private final List subs = new ArrayList(); // Subscription; guarded by this
    private boolean closed; // guarded by this

    public void add(Subscription s) {
        synchronized (this) {
            if (!closed) {
                subs.add(s);
                return;
            }
        }
        cancelQuietly(s);
    }

    /** Forgets {@code s} (the plugin cancelled it itself, or a one-shot task finished). */
    public synchronized void remove(Subscription s) {
        subs.remove(s);
    }

    /** Cancels everything added so far and closes; one failing cancel does not stop the rest. */
    public void cancelAll() {
        Object[] snapshot;
        synchronized (this) {
            closed = true;
            snapshot = subs.toArray();
            subs.clear();
        }
        for (int i = 0; i < snapshot.length; i++) {
            cancelQuietly((Subscription) snapshot[i]);
        }
    }

    private static void cancelQuietly(Subscription s) {
        try {
            s.cancel();
        } catch (Throwable ignored) {
            // keep cancelling the others
        }
    }
}
