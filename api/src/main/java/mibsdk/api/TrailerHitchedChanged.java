package mibsdk.api;

public final class TrailerHitchedChanged {

    private final boolean hitched;

    public TrailerHitchedChanged(boolean hitched) {
        this.hitched = hitched;
    }

    public boolean isHitched() {
        return hitched;
    }
}
