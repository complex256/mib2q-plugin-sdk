package mibsdk.runtime;

/** Monotonic millisecond source. Every duration in the SDK is measured with a Clock, never wall time. */
public interface Clock {

    /** Milliseconds since this clock started; never decreases, never jumps with the wall clock. */
    long uptimeMs();
}
