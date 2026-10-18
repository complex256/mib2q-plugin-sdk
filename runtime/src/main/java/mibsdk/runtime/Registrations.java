package mibsdk.runtime;

import java.util.ArrayList;
import java.util.List;
import mibsdk.api.Subscription;

/** Collects a plugin's subscriptions so they can all be cancelled when it stops. */
public final class Registrations {

    private final List subs = new ArrayList(); // Subscription; guarded by this

    public void add(Subscription s) {
        synchronized (this) {
            subs.add(s);
        }
    }

    /** Cancels everything added so far; one failing cancel does not stop the rest. */
    public void cancelAll() {
        Object[] snapshot;
        synchronized (this) {
            snapshot = subs.toArray();
            subs.clear();
        }
        for (int i = 0; i < snapshot.length; i++) {
            try {
                ((Subscription) snapshot[i]).cancel();
            } catch (Throwable ignored) {
                // keep cancelling the others
            }
        }
    }
}
