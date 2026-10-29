package mibsdk.platform;

import java.util.List;
import mibsdk.api.WifiNetwork;
import mibsdk.api.WifiStatus;

/**
 * The adapter's Wi-Fi client uplink. The runtime's {@code Wifi} API and its own Internet page both talk to this.
 * Implementations report state changes by publishing {@code mibsdk.api.WifiChanged} through the platform host.
 *
 * <p>The car's access point is not this interface's concern: an implementation only joins and leaves client
 * networks, leaving the stock access point alone.
 */
public interface WifiIo {

    /** The last known state. Never null. */
    WifiStatus status();

    /**
     * Scans for networks and waits for the car's results or {@code timeoutMs}.
     *
     * @return the networks found, strongest first; never null
     */
    List<WifiNetwork> scan(int timeoutMs);

    /**
     * Joins a network. Returns at once; the outcome arrives as a {@code WifiChanged}.
     *
     * @param ssid the network name
     * @param bssid the access point, or null/empty for any
     * @param password the passphrase, or null/empty for an open network
     * @param secured true when the network uses WPA/WPA2
     */
    void connect(String ssid, String bssid, String password, boolean secured);

    /** Leaves the current network. Returns at once. */
    void disconnect();

    /** Removes a saved network. Returns at once. */
    void forget(String ssid);

    /**
     * Ensures the client interface has an address: DHCP when {@code dhcp}, else the static settings. Called on
     * start and on every poll; an implementation must be cheap when the address already matches and must run the
     * actual work off the caller's thread.
     *
     * @param ip the static IPv4, or empty
     * @param netmask the static netmask, or empty
     * @param gateway the static default gateway, or empty
     * @param dns comma-separated DNS servers, or empty to keep the car's resolver
     */
    void ensureAddress(boolean dhcp, String ip, String netmask, String gateway, String dns);

    /**
     * True when a cellular uplink has an address (so Wi-Fi should not be the default internet source). The runtime
     * uses this only to choose the first-run default for "Use Wi-Fi for internet".
     */
    boolean cellularLinkUp();
}
