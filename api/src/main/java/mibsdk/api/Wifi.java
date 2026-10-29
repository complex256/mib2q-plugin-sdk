package mibsdk.api;

/**
 * The unit's Wi-Fi client uplink. Obtain it from {@link PluginContext#wifi()}; needs
 * {@link Permissions#NETWORK_WIFI}.
 *
 * <p>The car's own access point stays available while the client is connected: the stock Wi-Fi service runs the
 * access point and the client together. This API only manages the client side (the internet uplink).
 *
 * <p>{@link #connect} and {@link #disconnect} ask the car to act; they return at once. Watch
 * {@link #onChanged(Listener)} or poll {@link #status()} for the result. {@link #scan(int)} blocks until the car
 * reports its results or the timeout expires.
 *
 */
public interface Wifi {

    /** The longest value accepted by {@link #scan(int)}, in milliseconds. */
    int MAX_SCAN_MS = 15000;

    /** The current uplink state. Never null. */
    WifiStatus status();

    /**
     * Asks the car to scan for networks and waits for its results.
     *
     * @param timeoutMs how long to wait, capped at {@value #MAX_SCAN_MS}
     * @return the networks found, strongest first; empty when none
     * @throws UnsupportedOperationException when the adapter has no Wi-Fi
     * @throws PermissionDeniedException if the permission is not granted
     */
    java.util.List<WifiNetwork> scan(int timeoutMs);

    /**
     * Joins {@code ssid}. {@code password} may be null or empty for an open network. The call returns at once; a
     * failure arrives through {@link #onChanged(Listener)} and {@link #status()}.
     *
     * @throws IllegalArgumentException when {@code ssid} is null or empty
     * @throws UnsupportedOperationException when the adapter has no Wi-Fi
     * @throws PermissionDeniedException if the permission is not granted
     */
    void connect(String ssid, String password);

    /** Leaves the current network. The call returns at once. */
    void disconnect();

    /** Removes a saved network from the car's list. The call returns at once. */
    void forget(String ssid);

    /**
     * Subscribes to state changes. The listener runs on the plugin's worker thread. Cancelling the returned
     * subscription stops delivery.
     */
    Subscription onChanged(Listener<WifiChanged> listener);
}
