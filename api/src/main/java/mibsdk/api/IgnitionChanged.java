package mibsdk.api;

/**
 * Terminal 15 changed.
 *
 * <p>Delivered asynchronously, after the fact, on the plugin's worker thread.
 *
 * @since api 1
 */
public final class IgnitionChanged {

    private final boolean on;

    /**
     * @param on true when the ignition is on
     */
    public IgnitionChanged(boolean on) {
        this.on = on;
    }

    /** Whether the ignition is on. */
    public boolean isOn() {
        return on;
    }
}
