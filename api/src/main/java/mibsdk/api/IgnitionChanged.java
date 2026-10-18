package mibsdk.api;

public final class IgnitionChanged {

    private final boolean on;

    public IgnitionChanged(boolean on) {
        this.on = on;
    }

    public boolean isOn() {
        return on;
    }
}
