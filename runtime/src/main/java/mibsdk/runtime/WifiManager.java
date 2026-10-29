package mibsdk.runtime;

import java.io.File;
import java.util.Collections;
import java.util.List;
import mibsdk.api.WifiNetwork;
import mibsdk.api.WifiStatus;
import mibsdk.platform.WifiIo;

/**
 * Owns the core Wi-Fi uplink: reads and writes {@code wifi.toml}, joins and leaves the configured network through
 * the adapter, scans on demand, and writes a small status file for mibpkg.
 *
 * <p>All methods except {@link #scanAsync}'s worker run on the runtime's control thread. {@link #poll()} is called
 * from the UI refresh (about once a second): it notices an external edit of {@code wifi.toml} (mibpkg writes the same
 * file), retries a dropped connection at a slow interval, and refreshes the mibpkg status file.
 */
final class WifiManager {

    /** The adapter's Wi-Fi I/O as of now; null while the stock service is not up. */
    interface Source {
        WifiIo io();
    }

    private static final long STATUS_WRITE_MS = 2000;
    private static final long RETRY_MS = 60000;

    private final File file;
    private final File statusFile;
    private final Source source;
    private final RingLog log;
    private final Clock clock;
    private final Runnable onChanged;

    private WifiConfig config;
    /** The signature of the last applied config, so a poll does not reconnect repeatedly. */
    private String applied = "";
    private long lastAttemptMs;
    private long lastStatusWriteMs;
    private volatile List<WifiNetwork> networks = Collections.<WifiNetwork>emptyList();
    private volatile boolean scanning;
    private volatile String lastError = "";

    WifiManager(File file, File statusFile, Source source, RingLog log, Clock clock, Runnable onChanged) {
        this.file = file;
        this.statusFile = statusFile;
        this.source = source;
        this.log = log;
        this.clock = clock;
        this.onChanged = onChanged;
    }

    /** Reads the config and applies it. Control thread. */
    void start() {
        boolean defaultEnabled = true;
        WifiIo io = source.io();
        if (io != null) {
            try {
                defaultEnabled = !io.cellularLinkUp();
            } catch (Throwable t) {
                log.append("WARN", "mibsdk.wifi", "cellular state unknown, defaulting Wi-Fi uplink on: " + t);
            }
        }
        config = WifiConfig.load(file, defaultEnabled);
        secureFile();
        if (config.error.length() > 0) {
            log.append("WARN", "mibsdk.wifi", config.error + "; using defaults");
        }
        if (!file.isFile()) {
            save();
        }
        if (io == null) {
            log.append("INFO", "mibsdk.wifi", "no Wi-Fi service yet; config saved, uplink starts when it arrives");
            return;
        }
        apply("start");
    }

    /** Control thread, about once a second. */
    void poll() {
        try {
            // Compare contents, not mtime: QNX's lastModified() is not dependable for these files, and mibpkg
            // writes the same small file.
            WifiConfig fromDisk = WifiConfig.load(file, config == null || config.enabled);
            if (config == null || !config.equals(fromDisk)) {
                config = fromDisk;
                if (config.error.length() > 0) {
                    log.append("WARN", "mibsdk.wifi", config.error + "; using defaults");
                }
                lastError = "";
                log.append("INFO", "mibsdk.wifi", "wifi.toml changed; applying " + describe());
                apply("file");
            }
            WifiIo io = source.io();
            if (io != null && config != null && config.enabled) {
                io.ensureAddress(config.dhcp, config.ip, config.netmask, config.gateway, config.dns);
            }
            if (io != null && config != null && config.enabled && config.ssid.length() > 0) {
                WifiStatus st = io.status();
                boolean onWanted = st.connected() && config.ssid.equals(st.ssid());
                if (!onWanted && clock.uptimeMs() - lastAttemptMs > RETRY_MS) {
                    apply("retry");
                }
            }
            long now = clock.uptimeMs();
            if (now - lastStatusWriteMs >= STATUS_WRITE_MS) {
                lastStatusWriteMs = now;
                writeStatus();
            }
        } catch (Throwable t) {
            log.append("WARN", "mibsdk.wifi", "poll failed: " + SdkRuntime.describe(t));
        }
    }

    WifiView view() {
        WifiIo io = source.io();
        if (io == null) {
            return new WifiView(false, config != null && config.enabled, scanning, WifiStatus.DISCONNECTED, "",
                    "", "", config == null ? "" : config.ssid, networks, config, "", "", "");
        }
        WifiStatus st = io.status();
        if (st.connected()) {
            lastError = "";
        } else if (st.state() == WifiStatus.FAILED && st.error().length() > 0) {
            lastError = st.error();
        }
        return new WifiView(true, config != null && config.enabled, scanning, st.state(), st.ssid(), st.ipv4(),
                st.error().length() == 0 ? lastError : st.error(), config == null ? "" : config.ssid, networks,
                config, st.netmask(), st.gateway(), st.dns());
    }

    boolean enabled() {
        return config != null && config.enabled;
    }

    String savedSsid() {
        return config == null ? "" : config.ssid;
    }

    /** The saved password when it belongs to {@code ssid}; empty otherwise. */
    String passwordFor(String ssid) {
        return config != null && ssid != null && ssid.equals(config.ssid) ? config.password : "";
    }

    List<WifiNetwork> networks() {
        return networks;
    }

    /** Turns the uplink on or off. Control thread. */
    void setEnabled(boolean on) {
        if (config == null) {
            return;
        }
        config = config.withEnabled(on);
        save();
        if (!on) {
            WifiIo io = source.io();
            if (io != null) {
                io.disconnect();
            }
            applied = "";
        } else {
            apply("enabled");
        }
        onChanged.run();
    }

    /** Saves and joins a network. Control thread. */
    void connect(String ssid, String password, boolean secured) {
        if (ssid == null || ssid.length() == 0) {
            return;
        }
        config = config == null ? new WifiConfig(true, ssid, password)
                : config.withEnabled(true).withCredentials(ssid, password);
        save();
        applied = "";
        apply("connect");
        onChanged.run();
    }

    /** Removes the saved network and leaves it. Control thread. */
    void forget() {
        String ssid = config == null ? "" : config.ssid;
        WifiIo io = source.io();
        if (io != null && ssid.length() > 0) {
            io.forget(ssid);
            io.disconnect();
        }
        config = config == null ? new WifiConfig(false, "", "") : config.withCredentials("", "");
        save();
        applied = "";
        networks = Collections.<WifiNetwork>emptyList();
        onChanged.run();
    }

    /** Switches the address mode. Control thread. */
    void setDhcp(boolean on) {
        if (config == null) {
            return;
        }
        config = config.withDhcp(on);
        save();
        applied = "";
        apply("dhcp");
        onChanged.run();
    }

    /** Sets one static field (`ip`, `netmask`, `gateway`, `dns`). Returns false for an invalid IPv4. */
    boolean setAddress(String field, String value) {
        if (config == null) {
            return false;
        }
        String v = value == null ? "" : value.trim();
        if (("ip".equals(field) || "netmask".equals(field) || "gateway".equals(field)) && v.length() > 0
                && !isIpv4(v)) {
            return false;
        }
        if ("dns".equals(field) && v.length() > 0) {
            int at = 0;
            while (at <= v.length()) {
                int comma = v.indexOf(',', at);
                String p = (comma < 0 ? v.substring(at) : v.substring(at, comma)).trim();
                if (p.length() > 0 && !isIpv4(p)) {
                    return false;
                }
                if (comma < 0) {
                    break;
                }
                at = comma + 1;
            }
        }
        config = config.withAddress(field, v);
        save();
        applied = "";
        apply("address");
        onChanged.run();
        return true;
    }

    static boolean isIpv4(String s) {
        if (s == null) {
            return false;
        }
        int part = 0;
        int at = 0;
        while (at <= s.length()) {
            int dot = s.indexOf('.', at);
            String p = dot < 0 ? s.substring(at) : s.substring(at, dot);
            if (p.length() == 0 || p.length() > 3) {
                return false;
            }
            for (int k = 0; k < p.length(); k++) {
                if (p.charAt(k) < '0' || p.charAt(k) > '9') {
                    return false;
                }
            }
            if (Integer.parseInt(p) > 255) {
                return false;
            }
            part++;
            if (dot < 0) {
                break;
            }
            at = dot + 1;
        }
        return part == 4;
    }

    /** Starts a scan on a daemon thread; the UI refreshes when it finishes. */
    void scanAsync() {
        final WifiIo io = source.io();
        if (io == null || scanning) {
            return;
        }
        scanning = true;
        onChanged.run();
        Thread t = new Thread("mibsdk-wifi-scan") {
            public void run() {
                List<WifiNetwork> found = Collections.<WifiNetwork>emptyList();
                try {
                    found = io.scan(8000);
                    lastError = "";
                } catch (Throwable e) {
                    lastError = "scan failed";
                    log.append("WARN", "mibsdk.wifi", "scan failed: " + SdkRuntime.describe(e));
                } finally {
                    networks = found == null ? Collections.<WifiNetwork>emptyList() : found;
                    scanning = false;
                    onChanged.run();
                }
            }
        };
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.start();
    }

    // ---- internals ----

    private void apply(String why) {
        if (config == null) {
            return;
        }
        WifiIo io = source.io();
        if (io == null) {
            return;
        }
        String sig = config.enabled + "\n" + config.ssid + "\n" + config.password;
        lastAttemptMs = clock.uptimeMs();
        try {
            if (!config.enabled) {
                io.disconnect();
            } else {
                io.ensureAddress(config.dhcp, config.ip, config.netmask, config.gateway, config.dns);
            }
            if (config.enabled && config.ssid.length() > 0) {
                WifiStatus st = io.status();
                if (st.connected() && config.ssid.equals(st.ssid())) {
                    applied = sig;
                    return;
                }
                io.connect(config.ssid, null, config.password, true);
                lastError = "";
                log.append("INFO", "mibsdk.wifi", "connecting to " + config.ssid + " (" + why + ")");
            }
            applied = sig;
        } catch (Throwable t) {
            lastError = "connect failed";
            log.append("ERROR", "mibsdk.wifi", "apply (" + why + ") failed: " + SdkRuntime.describe(t));
        }
    }

    private void save() {
        try {
            DurableFiles.replace(file, config.toText().getBytes("UTF-8"));
            secureFile();
        } catch (Throwable t) {
            log.append("WARN", "mibsdk.wifi", "cannot write " + file + ": " + SdkRuntime.describe(t));
        }
    }

    /** The file holds a Wi-Fi passphrase; daemon umask makes it 0666 otherwise. */
    private void secureFile() {
        if (file == null || !file.isFile()) {
            return;
        }
        try {
            Process p = Runtime.getRuntime().exec(new String[] {"/bin/chmod", "600", file.getPath()});
            p.waitFor();
        } catch (Throwable t) {
            log.append("WARN", "mibsdk.wifi", "cannot chmod 600 " + file + ": " + t);
        }
    }

    private void writeStatus() {
        try {
            WifiIo io = source.io();
            WifiStatus st = io == null ? new WifiStatus(WifiStatus.DISCONNECTED, "", "", "", 0, "") : io.status();
            StringBuffer b = new StringBuffer();
            b.append("enabled=").append(config != null && config.enabled).append('\n');
            b.append("state=").append(stateWord(st.state())).append('\n');
            b.append("ssid=").append(oneLine(st.ssid())).append('\n');
            b.append("ipv4=").append(oneLine(st.ipv4())).append('\n');
            b.append("netmask=").append(oneLine(st.netmask())).append('\n');
            b.append("gateway=").append(oneLine(st.gateway())).append('\n');
            b.append("dns=").append(oneLine(st.dns())).append('\n');
            b.append("error=").append(oneLine(st.error().length() == 0 ? lastError : st.error())).append('\n');
            b.append("password_set=").append(config != null && config.password.length() > 0).append('\n');
            // /tmp is QNX shmem: rename is unreliable there, and the file is volatile, so write it directly.
            java.io.FileOutputStream out = new java.io.FileOutputStream(statusFile);
            try {
                out.write(b.toString().getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (Throwable t) {
            // a status file is best effort: mibpkg shows "unknown" until it can be written
        }
    }

    private static String stateWord(int state) {
        if (state == WifiStatus.CONNECTED) {
            return "connected";
        }
        if (state == WifiStatus.CONNECTING) {
            return "connecting";
        }
        if (state == WifiStatus.FAILED) {
            return "failed";
        }
        if (state == WifiStatus.OFF) {
            return "off";
        }
        return "disconnected";
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replace('\n', ' ').replace('\r', ' ');
    }


    private String describe() {
        return "enabled=" + config.enabled + " ssid=" + (config.ssid.length() == 0 ? "(none)" : config.ssid)
                + (config.dhcp ? " dhcp" : " static=" + config.ip + "/" + config.netmask + " via " + config.gateway);
    }
}
