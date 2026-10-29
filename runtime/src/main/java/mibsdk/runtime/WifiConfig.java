package mibsdk.runtime;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.BufferedReader;

/**
 * {@code wifi.toml} in the persist root: the core Wi-Fi uplink setting, shared with mibpkg. The file is a small
 * TOML subset (one {@code key = value} per line, basic strings and booleans), so both the car and the web UI can
 * edit it without a TOML library.
 *
 * <pre>
 * enabled  = true
 * ssid     = "beepboop"
 * password = "test-password"
 * dhcp     = true
 * # when dhcp = false:
 * ip       = "192.168.1.50"
 * netmask  = "255.255.255.0"
 * gateway  = "192.168.1.1"
 * dns      = "1.1.1.1"
 * </pre>
 */
final class WifiConfig {

    final boolean enabled;
    final String ssid;
    final String password;
    /** True: the stock DHCP client. False: the static address below. */
    final boolean dhcp;
    final String ip;
    final String netmask;
    final String gateway;
    /** Comma-separated DNS servers; empty keeps the car's own resolver. */
    final String dns;
    /** A parse error from {@link #load}; empty for a valid or missing file. */
    String error = "";

    WifiConfig(boolean enabled, String ssid, String password) {
        this(enabled, ssid, password, true, "", "", "", "");
    }

    WifiConfig(boolean enabled, String ssid, String password, boolean dhcp, String ip, String netmask,
            String gateway, String dns) {
        this.enabled = enabled;
        this.ssid = ssid == null ? "" : ssid;
        this.password = password == null ? "" : password;
        this.dhcp = dhcp;
        this.ip = ip == null ? "" : ip;
        this.netmask = netmask == null ? "" : netmask;
        this.gateway = gateway == null ? "" : gateway;
        this.dns = dns == null ? "" : dns;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof WifiConfig)) {
            return false;
        }
        WifiConfig c = (WifiConfig) o;
        return enabled == c.enabled && dhcp == c.dhcp && ssid.equals(c.ssid) && password.equals(c.password)
                && ip.equals(c.ip) && netmask.equals(c.netmask) && gateway.equals(c.gateway) && dns.equals(c.dns)
                && error.equals(c.error);
    }

    @Override
    public int hashCode() {
        int h = enabled ? 1 : 0;
        h = 31 * h + ssid.hashCode();
        h = 31 * h + password.hashCode();
        h = 31 * h + (dhcp ? 1 : 0);
        h = 31 * h + ip.hashCode();
        h = 31 * h + netmask.hashCode();
        h = 31 * h + gateway.hashCode();
        h = 31 * h + dns.hashCode();
        return 31 * h + error.hashCode();
    }

    WifiConfig withEnabled(boolean on) {
        return new WifiConfig(on, ssid, password, dhcp, ip, netmask, gateway, dns);
    }

    WifiConfig withCredentials(String newSsid, String newPassword) {
        return new WifiConfig(enabled, newSsid == null ? "" : newSsid, newPassword == null ? "" : newPassword, dhcp,
                ip, netmask, gateway, dns);
    }

    WifiConfig withDhcp(boolean on) {
        return new WifiConfig(enabled, ssid, password, on, ip, netmask, gateway, dns);
    }

    /** One of {@code ip}, {@code netmask}, {@code gateway}, {@code dns}; anything else is ignored. */
    WifiConfig withAddress(String field, String value) {
        String v = value == null ? "" : value.trim();
        if ("ip".equals(field)) {
            return new WifiConfig(enabled, ssid, password, dhcp, v, netmask, gateway, dns);
        }
        if ("netmask".equals(field)) {
            return new WifiConfig(enabled, ssid, password, dhcp, ip, v, gateway, dns);
        }
        if ("gateway".equals(field)) {
            return new WifiConfig(enabled, ssid, password, dhcp, ip, netmask, v, dns);
        }
        if ("dns".equals(field)) {
            return new WifiConfig(enabled, ssid, password, dhcp, ip, netmask, gateway, v);
        }
        return this;
    }

    /** Reads {@code file}; a missing, unreadable or malformed file gives the defaults. */
    static WifiConfig load(File file, boolean defaultEnabled) {
        if (file == null || !file.isFile()) {
            return new WifiConfig(defaultEnabled, "", "");
        }
        String text;
        try {
            text = read(file);
        } catch (IOException e) {
            return new WifiConfig(defaultEnabled, "", "");
        }
        try {
            Toml.Table t = Toml.parse(text);
            t.rejectUnknown(new String[] {"enabled", "ssid", "password", "dhcp", "ip", "netmask", "gateway", "dns"});
            return new WifiConfig(
                    t.bool("enabled", defaultEnabled),
                    t.string("ssid", ""),
                    t.string("password", ""),
                    t.bool("dhcp", true),
                    t.string("ip", ""),
                    t.string("netmask", ""),
                    t.string("gateway", ""),
                    t.string("dns", ""));
        } catch (Toml.TomlException e) {
            WifiConfig c = new WifiConfig(defaultEnabled, "", "");
            c.error = "wifi.toml: " + e.getMessage();
            return c;
        }
    }

    private static String read(File file) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
        try {
            StringBuffer b = new StringBuffer();
            String line;
            while ((line = in.readLine()) != null) {
                b.append(line).append('\n');
            }
            return b.toString();
        } finally {
            in.close();
        }
    }

    /** The file's text; the caller writes it atomically. */
    String toText() {
        return new Toml.Writer()
                .comment("mibsdk Wi-Fi uplink. Edited here or by mibpkg; the SDK applies changes within a few seconds.")
                .bool("enabled", enabled)
                .string("ssid", ssid)
                .string("password", password)
                .bool("dhcp", dhcp)
                .string("ip", ip)
                .string("netmask", netmask)
                .string("gateway", gateway)
                .string("dns", dns)
                .toString();
    }
}
