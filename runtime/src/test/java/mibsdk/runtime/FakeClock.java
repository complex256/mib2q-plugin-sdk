package mibsdk.runtime;

/** Manually advanced {@link Clock} for tests. */
final class FakeClock implements Clock {

    private volatile long now;

    @Override
    public long uptimeMs() {
        return now;
    }

    void advance(long ms) {
        now += ms;
    }
}
