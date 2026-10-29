package mibsdk.api;

/**
 * The unit's Wi-Fi uplink state, as last reported by the car. Obtain it from {@link Wifi#status()}.
 *
 */
public final class WifiStatus {

    /** Wi-Fi is switched off for the internet uplink. */
    public static final int OFF = 0;
    /** Association or DHCP is in progress. */
    public static final int CONNECTING = 1;
    /** Associated and holding an address; the uplink is usable. */
    public static final int CONNECTED = 2;
    /** Not associated, and no connection is in progress. */
    public static final int DISCONNECTED = 3;
    /** The last attempt failed; {@link #error()} says why. */
    public static final int FAILED = 4;

    private final int state;
    private final String ssid;
    private final String bssid;
    private final String ipv4;
    private final int signal;
    private final String error;
    private final String netmask;
    private final String gateway;
    private final String dns;

    public WifiStatus(int state, String ssid, String bssid, String ipv4, int signal, String error) {
        this(state, ssid, bssid, ipv4, signal, error, "", "", "");
    }

    /**
     * @param netmask the live IPv4 netmask, or empty
     * @param gateway the live default gateway, or empty
     * @param dns comma-separated live DNS servers, or empty
     */
    public WifiStatus(int state, String ssid, String bssid, String ipv4, int signal, String error, String netmask,
            String gateway, String dns) {
        this.state = state;
        this.ssid = ssid == null ? "" : ssid;
        this.bssid = bssid == null ? "" : bssid;
        this.ipv4 = ipv4 == null ? "" : ipv4;
        this.signal = signal < 0 ? 0 : (signal > 100 ? 100 : signal);
        this.error = error == null ? "" : error;
        this.netmask = netmask == null ? "" : netmask;
        this.gateway = gateway == null ? "" : gateway;
        this.dns = dns == null ? "" : dns;
    }

    /** {@link #OFF}, {@link #CONNECTING}, {@link #CONNECTED}, {@link #DISCONNECTED} or {@link #FAILED}. */
    public int state() {
        return state;
    }

    /** The SSID the unit is associated with (or tried last); empty when none. */
    public String ssid() {
        return ssid;
    }

    /** The access point's BSSID; empty when unknown. */
    public String bssid() {
        return bssid;
    }

    /** The IPv4 address on the Wi-Fi client interface; empty until DHCP completes. */
    public String ipv4() {
        return ipv4;
    }

    /** Signal strength, 0..100; 0 when unknown. */
    public int signal() {
        return signal;
    }

    /** A short reason for {@link #FAILED}; empty otherwise. */
    public String error() {
        return error;
    }

    /** The live IPv4 netmask; empty until an address is set. */
    public String netmask() {
        return netmask;
    }

    /** The live default gateway; empty until an address is set. */
    public String gateway() {
        return gateway;
    }

    /** Comma-separated live DNS servers; empty when unknown. */
    public String dns() {
        return dns;
    }

    /** True when {@link #state()} is {@link #CONNECTED}. */
    public boolean connected() {
        return state == CONNECTED;
    }

    @Override
    public String toString() {
        return "WifiStatus(" + state + "," + ssid + "," + ipv4 + ")";
    }
}
