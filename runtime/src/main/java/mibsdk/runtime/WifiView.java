package mibsdk.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import mibsdk.api.WifiNetwork;
import mibsdk.api.WifiStatus;

/** What the SDK's Internet page shows about the Wi-Fi uplink. Immutable. */
final class WifiView {

    static final WifiView NONE = new WifiView(false, false, false, -1, "", "", "", "", null, null, "", "", "");

    final boolean available;
    final boolean enabled;
    final boolean scanning;
    final int state;
    final String ssid;
    final String ipv4;
    final String error;
    final String savedSsid;
    final List<WifiNetwork> networks;
    /** True for DHCP; false for the static settings below. */
    final boolean dhcp;
    final String ip;
    final String netmask;
    final String gateway;
    final String dns;
    /** The live lease values (DHCP or static), as reported by the stock network script. */
    final String liveNetmask;
    final String liveGateway;
    final String liveDns;

    WifiView(boolean available, boolean enabled, boolean scanning, int state, String ssid, String ipv4,
            String error, String savedSsid, List<WifiNetwork> networks, WifiConfig config, String liveNetmask,
            String liveGateway, String liveDns) {
        this.available = available;
        this.enabled = enabled;
        this.scanning = scanning;
        this.state = state;
        this.ssid = ssid == null ? "" : ssid;
        this.ipv4 = ipv4 == null ? "" : ipv4;
        this.error = error == null ? "" : error;
        this.savedSsid = savedSsid == null ? "" : savedSsid;
        this.networks = networks == null ? Collections.<WifiNetwork>emptyList()
                : Collections.unmodifiableList(new ArrayList<WifiNetwork>(networks));
        this.dhcp = config == null || config.dhcp;
        this.ip = config == null ? "" : config.ip;
        this.netmask = config == null ? "" : config.netmask;
        this.gateway = config == null ? "" : config.gateway;
        this.dns = config == null ? "" : config.dns;
        this.liveNetmask = liveNetmask == null ? "" : liveNetmask;
        this.liveGateway = liveGateway == null ? "" : liveGateway;
        this.liveDns = liveDns == null ? "" : liveDns;
    }

    /** A short status for the SDK page's Internet row. */
    String line() {
        if (!available) {
            return "not available";
        }
        if (!enabled) {
            return "off";
        }
        if (state == WifiStatus.CONNECTED) {
            return ssid.length() == 0 ? "connected" : "connected via " + ssid;
        }
        if (state == WifiStatus.CONNECTING) {
            return "connecting to " + ssid;
        }
        if (state == WifiStatus.FAILED) {
            return error.length() == 0 ? "failed" : error;
        }
        return "no uplink";
    }
}
