package mibsdk.api;

/**
 * The parking-sound directions changed.
 *
 * <p>Delivered asynchronously, after the fact, on the plugin's worker thread.
 *
 * @since api 1
 */
public final class ParkingSoundChanged {

    private final SoundDirections value;

    /**
     * @param value the new directions
     */
    public ParkingSoundChanged(SoundDirections value) {
        this.value = value;
    }

    /** The new directions. */
    public SoundDirections value() {
        return value;
    }
}
