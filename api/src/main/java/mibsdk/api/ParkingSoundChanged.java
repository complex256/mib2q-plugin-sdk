package mibsdk.api;

public final class ParkingSoundChanged {

    private final SoundDirections value;

    public ParkingSoundChanged(SoundDirections value) {
        this.value = value;
    }

    public SoundDirections value() {
        return value;
    }
}
