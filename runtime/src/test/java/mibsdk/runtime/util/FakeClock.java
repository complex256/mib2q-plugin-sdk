package mibsdk.runtime.util;

/** Manually advanced {@link Clock} for tests. */
public final class FakeClock implements Clock {

    private volatile long now;

    @Override
    public long uptimeMs() {
        return now;
    }

    public void advance(long ms) {
        now += ms;
    }
}
