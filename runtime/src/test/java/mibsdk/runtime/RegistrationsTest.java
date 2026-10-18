package mibsdk.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import mibsdk.api.Subscription;
import org.junit.jupiter.api.Test;

class RegistrationsTest {

    private static Subscription counting(final AtomicInteger cancels) {
        return new Subscription() {
            public void cancel() {
                cancels.incrementAndGet();
            }
        };
    }

    @Test
    void cancelAllCancelsEverythingOnce() {
        AtomicInteger cancels = new AtomicInteger();
        Registrations r = new Registrations();
        r.add(counting(cancels));
        r.add(counting(cancels));
        r.cancelAll();
        r.cancelAll();
        assertEquals(2, cancels.get());
    }

    @Test
    void addAfterCancelAllCancelsImmediately() {
        AtomicInteger cancels = new AtomicInteger();
        Registrations r = new Registrations();
        r.cancelAll();
        r.add(counting(cancels));
        assertEquals(1, cancels.get());
    }

    @Test
    void removedSubscriptionIsNotCancelledAgain() {
        AtomicInteger cancels = new AtomicInteger();
        Registrations r = new Registrations();
        Subscription s = counting(cancels);
        r.add(s);
        r.remove(s);
        r.cancelAll();
        assertEquals(0, cancels.get());
    }
}
