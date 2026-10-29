package mibsdk.api;

/**
 * One Wi-Fi network found by {@link Wifi#scan(int)}. Immutable.
 *
 */
public final class WifiNetwork {

    private final String ssid;
    private final String bssid;
    private final int signal;
    private final String encryption;

    public WifiNetwork(String ssid, String bssid, int signal, String encryption) {
        this.ssid = ssid == null ? "" : ssid;
        this.bssid = bssid == null ? "" : bssid;
        this.signal = signal < 0 ? 0 : (signal > 100 ? 100 : signal);
        this.encryption = encryption == null ? "" : encryption;
    }

    /** The network name (empty for a hidden network). */
    public String ssid() {
        return ssid;
    }

    /** The access point's BSSID; empty when unknown. */
    public String bssid() {
        return bssid;
    }

    /** Signal strength, 0..100. */
    public int signal() {
        return signal;
    }

    /** A short security label such as {@code WPA2} or {@code open}; empty when unknown. */
    public String encryption() {
        return encryption;
    }

    /** True when a password is needed to join. */
    public boolean secured() {
        return encryption.length() > 0 && !"open".equalsIgnoreCase(encryption);
    }

    @Override
    public String toString() {
        return "WifiNetwork(" + ssid + "," + signal + "," + encryption + ")";
    }
}
