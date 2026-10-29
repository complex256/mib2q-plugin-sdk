package mibsdk.api;

/**
 * The Wi-Fi uplink state changed. Delivered on the plugin's worker thread through
 * {@link Wifi#onChanged(Listener)}.
 *
 */
public final class WifiChanged {

    private final WifiStatus status;

    public WifiChanged(WifiStatus status) {
        this.status = status;
    }

    /** The new state. Never null. */
    public WifiStatus status() {
        return status;
    }

    @Override
    public String toString() {
        return "WifiChanged(" + status + ")";
    }
}
