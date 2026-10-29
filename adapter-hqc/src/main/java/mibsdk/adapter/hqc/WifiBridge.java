package mibsdk.adapter.hqc;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import mibsdk.api.WifiChanged;
import mibsdk.api.WifiNetwork;
import mibsdk.api.WifiStatus;
import mibsdk.platform.WifiIo;
import org.dsi.ifc.networking.DSIWLAN;
import org.dsi.ifc.networking.DSIWLANListener;
import org.dsi.ifc.networking.DiscoveredNetwork;

/**
 * The Wi-Fi client uplink over the stock {@code DSIWLAN}.
 *
 * <p>The car's access point and the client run together ({@code ROLES_ROLE_AP_CLIENT_COMBINED}); the bridge never
 * changes the role, so joining a network does not take the car's hotspot down. It registers its own listener with
 * {@code setNotification}, caches the connected network and the last scan, and publishes
 * {@code WifiChanged} on the SDK {@link EventPump} thread.
 *
 * <p>Scan results keep the stock encryption type: {@code requestConnectNetwork}'s fourth argument is exactly the
 * {@code DiscoveredNetwork.getEncryptionType()} value, so a network chosen from a scan connects with its own type.
 * A connection made without a scan uses WPA2/AES for a secured network and plaintext otherwise.
 */
final class WifiBridge implements WifiIo {

    private static final String SOURCE = "mibsdk.hqc.wifi";
    private static final String CLIENT_IF = "mlan0";
    /** {@code ROLES_ROLE_AP_CLIENT_COMBINED}; the stock inquiry passes this literal (iconst_4). */
    private static final int ROLE_COMBINED = 4;
    private static final String[] CELLULAR_IFS = {"ecm0", "ppp0"};

    private final EventPump pump;
    private final Object listener;
    private final int[] attrs;
    private final Object scanLock = new Object();

    private volatile DSIWLAN service;
    private volatile boolean closed;
    private volatile int state = WifiStatus.DISCONNECTED;
    private volatile String ssid = "";
    /** The SSID the stock service last reported as connected; empty when unknown. */
    private volatile String connectedSsid = "";
    /** Cached result of wpa_cli status, refreshed briefly: the stock DSI reports bigdata as empty. */
    private volatile String cachedSsid = "";
    private volatile long cachedSsidAt;
    private volatile String wpaCliPath;
    private volatile boolean wpaCliProbed;
    /** Live DHCP details from /tmp/net.external, refreshed briefly. */
    private volatile String leasedNetmask = "";
    private volatile String leasedGateway = "";
    private volatile String leasedDns = "";
    private volatile long leaseAt;
    private volatile String bssid = "";
    private volatile String error = "";
    private volatile int signal;
    private volatile int role = -1;
    private volatile boolean rfActive = true;
    /** When the address watcher last ran dhcp.client; 0 for never. */
    private volatile long lastDhcpMs;
    /** dhcp.client is missing on this unit; stop retrying. */
    private volatile boolean dhcpMissing;
    /** The last SSID a fallback was started for, so two connects do not race. */
    private volatile long connectToken;
    /** DSI has been shown not to drive mlan0; use wpa_cli directly from now on. */
    private volatile boolean fallbackMode;
    /** A wpa_cli select is waiting for association; DHCP runs once wpa_state is COMPLETED. */
    private volatile boolean forceDhcp;
    private volatile long lastSelectMs;
    /** The address mode the config asks for; the watcher applies it. */
    private volatile boolean wantDhcp = true;
    private volatile String wantIp = "";
    private volatile String wantMask = "";
    private volatile String wantGw = "";
    private volatile String wantDns = "";
    /** The last static signature applied, so the watcher does not reconfigure every tick. */
    private volatile String staticApplied = "";
    /** The mode last applied; a switch clears the old address first. */
    private volatile boolean lastModeDhcp = true;

    /** The last scan, guarded by {@link #scanLock}; also the encryption lookup for {@link #connect}. */
    private final List<DiscoveredNetwork> scanned = new ArrayList<DiscoveredNetwork>();
    private boolean scanning;
    private boolean scanDone;

    WifiBridge(EventPump pump) {
        this.pump = pump;
        this.attrs = new int[] {
            attr("ATTR_ROLE"),
            attr("ATTR_RFACTIVE"),
            attr("ATTR_WLANENABLED"),
            attr("ATTR_DISCOVEREDNETWORK"),
            attr("ATTR_CONNECTEDNETWORK"),
            attr("ATTR_PROFILE")
        };
        this.listener = Proxy.newProxyInstance(DSIWLANListener.class.getClassLoader(),
                new Class[] {DSIWLANListener.class}, new InvocationHandler() {
                    public Object invoke(Object proxy, Method m, Object[] args) {
                        try {
                            String name = m.getName();
                            if (m.getDeclaringClass() == Object.class) {
                                if ("equals".equals(name)) {
                                    return args != null && args[0] == proxy ? Boolean.TRUE : Boolean.FALSE;
                                }
                                if ("hashCode".equals(name)) {
                                    return new Integer(System.identityHashCode(proxy));
                                }
                                return "mibsdk-wifi-listener";
                            }
                            onCallback(name, args);
                        } catch (Throwable t) {
                            // a stock callback thread must never see an SDK failure
                        }
                        return null;
                    }
                });
    }

    /** Called once the service is available (on the waiter thread). Ignored after {@link #detach}. */
    void attach(Object found) {
        DSIWLAN s = (DSIWLAN) RawServices.resolve(DSIWLAN.class.getName(), found);
        if (s == null) {
            SdkGlobals.LOG.append("ERROR", SOURCE, "attach: DSIWLAN resolve returned null");
            return;
        }
        synchronized (this) {
            if (closed || service != null) {
                return;
            }
            try {
                s.setNotification(attrs, (org.dsi.ifc.base.DSIListener) listener);
            } catch (Throwable t) {
                SdkGlobals.LOG.append("ERROR", SOURCE, "setNotification attrs=" + java.util.Arrays.asList(
                        new int[] {attrs[0], attrs[1], attrs[2], attrs[3], attrs[4], attrs[5]}) + " failed: "
                        + stack(t));
                throw new IllegalStateException(t);
            }
            service = s;
        }
        SdkGlobals.LOG.append("INFO", SOURCE, "DSIWLAN listener registered (attrs " + attrs[0] + "," + attrs[1]
                + "," + attrs[2] + "," + attrs[3] + "," + attrs[4] + "," + attrs[5] + ")");
        startAddressWatch();
        publish();
    }

    /**
     * The stock WLAN service associates {@code mlan0} but on this unit it does not always run DHCP, so the
     * interface comes up without an IPv4 and the unit is unreachable after a reboot. The watcher is the SDK's
     * replacement for the bench's manual {@code rcc ... dhcp.client} kick: when the interface has no address, run
     * the stock DHCP client and wait for the lease. It also sets the default route (dhcp.client does).
     */
    private void startAddressWatch() {
        Thread t = new Thread("mibsdk-wifi-addr") {
            public void run() {
                while (!closed) {
                    try {
                        if (wantDhcp != lastModeDhcp) {
                            clearAddress();
                            lastModeDhcp = wantDhcp;
                            staticApplied = "";
                        }
                        if (wantDhcp) {
                            if (forceDhcp) {
                                long age = System.currentTimeMillis() - lastSelectMs;
                                String ws = wpaState();
                                if ("COMPLETED".equals(ws)
                                        || (age > 15000 && ssid.length() > 0 && ssid.equals(currentSsid()))) {
                                    runDhcp(true);
                                    forceDhcp = false;
                                } else if (age > 45000) {
                                    forceDhcp = false; // association did not complete in time
                                    SdkGlobals.LOG.append("WARN", SOURCE, "association to " + ssid
                                            + " did not complete");
                                }
                            } else if (ipv4(CLIENT_IF).length() == 0 && !dhcpMissing) {
                                runDhcp(false);
                            }
                        } else if (wantIp.length() > 0) {
                            runStatic();
                        }
                    } catch (Throwable ignored) {
                        // a watcher failure must never end the loop
                    }
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        };
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.start();
    }

    public void ensureAddress(boolean dhcp, String ip, String netmask, String gateway, String dns) {
        wantDhcp = dhcp;
        wantIp = trim(ip);
        wantMask = trim(netmask);
        wantGw = trim(gateway);
        wantDns = trim(dns);
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    /** One bounded dhcp.client run; a recent attempt is not repeated for 30 s. */
    private void runDhcp(boolean waitForChange) {
        long now = System.currentTimeMillis();
        if (now - lastDhcpMs < 30000) {
            return;
        }
        lastDhcpMs = now;
        String before = ipv4(CLIENT_IF);
        Process p;
        try {
            p = Runtime.getRuntime().exec(new String[] {"/usr/sbin/dhcp.client", "-i", CLIENT_IF});
        } catch (Throwable t) {
            dhcpMissing = true;
            SdkGlobals.LOG.append("WARN", SOURCE, "cannot run /usr/sbin/dhcp.client: " + t);
            return;
        }
        SdkGlobals.LOG.append("INFO", SOURCE, (before.length() == 0 ? CLIENT_IF + " has no address"
                : CLIENT_IF + " switching; old address " + before) + "; running dhcp.client");
        long end = now + 12000;
        while (System.currentTimeMillis() < end && !closed) {
            String got = ipv4(CLIENT_IF);
            if (got.length() > 0 && (!waitForChange || !got.equals(before))) {
                break;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                break;
            }
        }
        try {
            p.destroy();
        } catch (Throwable ignored) {
            // already gone
        }
        String ip = ipv4(CLIENT_IF);
        if (ip.length() > 0) {
            String actual = currentSsid();
            if (actual.length() == 0) {
                actual = connectedSsid;
            }
            if (ssid.length() == 0 || ssid.equals(actual)) {
                state = WifiStatus.CONNECTED;
                connectedSsid = actual;
            }
            cachedSsidAt = 0;
            SdkGlobals.LOG.append("INFO", SOURCE, CLIENT_IF + " address " + ip);
            publish();
        } else {
            SdkGlobals.LOG.append("WARN", SOURCE, "dhcp.client did not get an address on " + CLIENT_IF);
        }
    }

    /** Unregisters the listener; later calls fail. Never throws. */
    void detach() {
        DSIWLAN s;
        synchronized (this) {
            closed = true;
            s = service;
            service = null;
        }
        if (s != null) {
            try {
                s.clearNotification(attrs, (org.dsi.ifc.base.DSIListener) listener);
            } catch (Throwable ignored) {
                // the service may be gone already
            }
        }
    }

    /** The bridge's DSI listener (tests). */
    Object listener() {
        return listener;
    }

    public WifiStatus status() {
        int st = state;
        String ip = ipv4(CLIENT_IF);
        if (st == WifiStatus.CONNECTED && ip.length() == 0) {
            st = WifiStatus.CONNECTING; // associated, DHCP still running
        }
        String actual = currentSsid();
        if (actual.length() == 0) {
            actual = connectedSsid;
        }
        refreshLease();
        return new WifiStatus(rfActive ? st : WifiStatus.OFF, actual, bssid, ip, signal, error, leasedNetmask,
                leasedGateway, leasedDns);
    }

    /** The stock net script writes the DHCP lease details here; refresh them every few seconds. */
    private void refreshLease() {
        long now = System.currentTimeMillis();
        if (now - leaseAt < 3000) {
            return;
        }
        leaseAt = now;
        String mask = "";
        String gw = "";
        String dns = "";
        try {
            java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(
                    new java.io.FileInputStream("/tmp/net.external"), "UTF-8"));
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    int eq = line.indexOf('=');
                    if (eq <= 0) {
                        continue;
                    }
                    String k = line.substring(0, eq).trim();
                    String v = line.substring(eq + 1).trim();
                    if ("NETMASK".equals(k)) {
                        mask = v;
                    } else if ("GATEWAY".equals(k)) {
                        gw = v;
                    } else if ("NAMESERVER1".equals(k)) {
                        dns = v;
                    } else if ("NAMESERVER2".equals(k) && v.length() > 0 && dns.length() > 0) {
                        dns = dns + "," + v;
                    }
                }
            } finally {
                in.close();
            }
        } catch (Throwable ignored) {
            // no lease file (or not read yet): leave the rows empty
        }
        leasedNetmask = mask;
        leasedGateway = gw;
        leasedDns = dns;
    }

    /** The interface's real SSID from the stock wpa_cli, cached for 2 s; empty if it cannot be read. */
    private String currentSsid() {
        long now = System.currentTimeMillis();
        if (now - cachedSsidAt < 2000) {
            return cachedSsid;
        }
        String cli = wpaCli();
        String found = "";
        if (cli != null) {
            String out = run(cli, new String[] {"-i", CLIENT_IF, "status"});
            int at = 0;
            while (at < out.length()) {
                int nl = out.indexOf('\n', at);
                String line = nl < 0 ? out.substring(at) : out.substring(at, nl);
                if (line.startsWith("ssid=")) {
                    found = line.substring(5).trim();
                    break;
                }
                if (nl < 0) {
                    break;
                }
                at = nl + 1;
            }
        }
        cachedSsid = found;
        cachedSsidAt = now;
        return found;
    }

    public List<WifiNetwork> scan(int timeoutMs) {
        if (fallbackMode) {
            return copy();
        }
        DSIWLAN s = service;
        if (s == null) {
            throw new IllegalStateException("DSIWLAN is not available");
        }
        ensureClientRole();
        int timeout = timeoutMs < 500 ? 500 : (timeoutMs > 15000 ? 15000 : timeoutMs);
        synchronized (scanLock) {
            if (scanning) {
                long end = System.currentTimeMillis() + timeout;
                while (scanning && System.currentTimeMillis() < end) {
                    try {
                        scanLock.wait(200);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                return copy();
            }
            scanning = true;
            scanDone = false;
            scanned.clear();
        }
        try {
            SdkGlobals.LOG.append("INFO", SOURCE, "requestNetworkSearch");
            DsiServiceProxy.beginSdkCall();
            try {
                s.requestNetworkSearch(0, 0);
            } finally {
                DsiServiceProxy.endSdkCall();
            }
        } catch (Throwable t) {
            synchronized (scanLock) {
                scanning = false;
                scanDone = true;
                scanLock.notifyAll();
            }
            throw new IllegalStateException("Wi-Fi scan failed: " + t);
        }
        long end = System.currentTimeMillis() + timeout;
        synchronized (scanLock) {
            while (!scanDone && System.currentTimeMillis() < end) {
                try {
                    scanLock.wait(Math.min(250, Math.max(1, end - System.currentTimeMillis())));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            scanning = false;
            scanLock.notifyAll();
            List<WifiNetwork> found = copy();
            SdkGlobals.LOG.append("INFO", SOURCE, "scan found " + found.size() + " network(s)");
            return found;
        }
    }

    public void connect(String ssid, String bssid, String password, boolean secured) {
        if (fallbackMode) {
            this.ssid = ssid == null ? "" : ssid;
            this.connectedSsid = "";
            this.bssid = bssid == null ? "" : bssid;
            this.error = "";
            this.state = WifiStatus.CONNECTING;
            publish();
            startWpaCliConnect(this.ssid, password, secured);
            return;
        }
        DSIWLAN s = service;
        if (s == null) {
            throw new IllegalStateException("DSIWLAN is not available");
        }
        ensureClientRole();
        String b = bssid == null ? "" : bssid;
        int enc = encryptionFor(ssid, secured);
        if (b.length() == 0) {
            DiscoveredNetwork found = findScanned(ssid);
            if (found == null) {
                try {
                    scan(5000);
                } catch (Throwable ignored) {
                    // fall through to the profile defaults
                }
                found = findScanned(ssid);
            }
            if (found != null) {
                b = found.bssidAddress == null ? "" : found.bssidAddress;
                enc = found.encryptionType;
            }
        }
        String p = password == null ? "" : password;
        this.ssid = ssid == null ? "" : ssid;
        this.connectedSsid = "";
        this.bssid = b;
        this.error = "";
        this.state = WifiStatus.CONNECTING;
        publish();
        SdkGlobals.LOG.append("INFO", SOURCE, "requestConnectNetwork ssid=" + this.ssid + " bssid=" + b
                + " bssidEmpty=" + (b.length() == 0) + " enc=" + enc);
        DsiServiceProxy.beginSdkCall();
        try {
            s.requestConnectNetwork(this.ssid, b, p, enc);
        } finally {
            DsiServiceProxy.endSdkCall();
        }
        startWpaCliFallback(this.ssid, p, secured);
    }

    /**
     * The bench (and some firmware builds) registers DSIWLAN but leaves the real `mlan0` to the direct
     * `wpa_supplicant`, so `requestConnectNetwork` is acknowledged and does nothing. If DSI has not produced an
     * address after a few seconds, drive the same stock `wpa_cli` the bench uses. On a car where DSI connects, this
     * never runs.
     */
    private void startWpaCliFallback(final String wantSsid, final String password, final boolean secured) {
        final long token = ++connectToken;
        Thread t = new Thread("mibsdk-wifi-fallback") {
            public void run() {
                try {
                    Thread.sleep(8000);
                } catch (InterruptedException e) {
                    return;
                }
                if (closed || token != connectToken || !wantSsid.equals(ssid)
                        || wantSsid.equals(connectedSsid)) {
                    return;
                }
                fallbackMode = true;
                SdkGlobals.LOG.append("INFO", SOURCE, "DSI did not connect; trying wpa_cli for " + wantSsid);
                if (wpaCliConnect(wantSsid, password, secured)) {
                    SdkGlobals.LOG.append("INFO", SOURCE, "wpa_cli selected " + wantSsid);
                } else {
                    SdkGlobals.LOG.append("WARN", SOURCE, "wpa_cli could not select " + wantSsid);
                }
            }
        };
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.start();
    }

    private void startWpaCliConnect(final String wantSsid, final String password, final boolean secured) {
        Thread t = new Thread("mibsdk-wifi-connect") {
            public void run() {
                if (wpaCliConnect(wantSsid, password, secured)) {
                    SdkGlobals.LOG.append("INFO", SOURCE, "wpa_cli selected " + wantSsid);
                } else {
                    SdkGlobals.LOG.append("WARN", SOURCE, "wpa_cli could not select " + wantSsid);
                }
            }
        };
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.start();
    }

    private boolean wpaCliConnect(String wantSsid, String password, boolean secured) {
        String cli = wpaCli();
        if (cli == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - lastSelectMs < 30000 && busyState(wpaState())) {
            return true; // already scanning/associating for the last select
        }
        int id = existingNetwork(cli, wantSsid);
        if (id < 0) {
            String add = run(cli, new String[] {"-i", CLIENT_IF, "add_network"});
            id = lastInteger(add);
            if (id < 0) {
                return false;
            }
        }
        run(cli, new String[] {"-i", CLIENT_IF, "set_network", String.valueOf(id), "ssid", quoted(wantSsid)});
        if (secured && password != null && password.length() > 0) {
            run(cli, new String[] {"-i", CLIENT_IF, "set_network", String.valueOf(id), "psk", quoted(password)});
        } else {
            run(cli, new String[] {"-i", CLIENT_IF, "set_network", String.valueOf(id), "key_mgmt", "NONE"});
        }
        run(cli, new String[] {"-i", CLIENT_IF, "enable_network", String.valueOf(id)});
        run(cli, new String[] {"-i", CLIENT_IF, "select_network", String.valueOf(id)});
        cachedSsidAt = 0;
        lastSelectMs = now;
        // Keep the old lease until the new association completes; then DHCP replaces it.
        forceDhcp = true;
        lastDhcpMs = 0;
        return true;
    }

    /** The id of an existing network with {@code ssid}, or -1. */
    private int existingNetwork(String cli, String wantSsid) {
        String out = run(cli, new String[] {"-i", CLIENT_IF, "list_networks"});
        int at = 0;
        while (at < out.length()) {
            int nl = out.indexOf('\n', at);
            String line = nl < 0 ? out.substring(at) : out.substring(at, nl);
            int tab = line.indexOf('\t');
            if (tab > 0) {
                String idPart = line.substring(0, tab).trim();
                int tab2 = line.indexOf('\t', tab + 1);
                String ssidPart = tab2 < 0 ? line.substring(tab + 1) : line.substring(tab + 1, tab2);
                if (ssidPart.equals(wantSsid)) {
                    try {
                        return Integer.parseInt(idPart);
                    } catch (NumberFormatException ignored) {
                        // keep looking
                    }
                }
            }
            if (nl < 0) {
                break;
            }
            at = nl + 1;
        }
        return -1;
    }

    private static int lastInteger(String s) {
        int end = s.length();
        while (end > 0) {
            char c = s.charAt(end - 1);
            if (c >= '0' && c <= '9') {
                break;
            }
            end--;
        }
        int start = end;
        while (start > 0) {
            char c = s.charAt(start - 1);
            if (c < '0' || c > '9') {
                break;
            }
            start--;
        }
        if (start == end) {
            return -1;
        }
        try {
            return Integer.parseInt(s.substring(start, end));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String quoted(String s) {
        StringBuffer b = new StringBuffer("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                b.append('\\').append(c);
            } else {
                b.append(c);
            }
        }
        return b.append('"').toString();
    }

    private String wpaCli() {
        if (!wpaCliProbed) {
            String[] paths = {"/mnt/app/armle/usr/sbin/wpa_cli", "/usr/sbin/wpa_cli", "/sbin/wpa_cli"};
            for (int i = 0; i < paths.length && wpaCliPath == null; i++) {
                if (canRun(paths[i])) {
                    wpaCliPath = paths[i];
                }
            }
            if (wpaCliPath == null && canRun("wpa_cli")) {
                wpaCliPath = "wpa_cli";
            }
            wpaCliProbed = true;
        }
        return wpaCliPath;
    }

    /** wpa_state from the stock wpa_cli, or empty. */
    private String wpaState() {
        String cli = wpaCli();
        if (cli == null) {
            return "";
        }
        String out = run(cli, new String[] {"-i", CLIENT_IF, "status"});
        int at = 0;
        while (at < out.length()) {
            int nl = out.indexOf('\n', at);
            String line = nl < 0 ? out.substring(at) : out.substring(at, nl);
            if (line.startsWith("wpa_state=")) {
                return line.substring(10).trim();
            }
            if (nl < 0) {
                break;
            }
            at = nl + 1;
        }
        return "";
    }

    private static boolean busyState(String ws) {
        return "SCANNING".equals(ws) || "ASSOCIATING".equals(ws) || "4WAY_HANDSHAKE".equals(ws)
                || "GROUP_HANDSHAKE".equals(ws) || "AUTHENTICATING".equals(ws);
    }

    private static boolean canRun(String binary) {
        try {
            Process p = Runtime.getRuntime().exec(new String[] {binary, "-h"});
            p.destroy();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Runs a short command and returns its stdout; never throws. */
    private static String run(String binary, String[] args) {
        String[] cmd = new String[args.length + 1];
        cmd[0] = binary;
        System.arraycopy(args, 0, cmd, 1, args.length);
        try {
            Process p = Runtime.getRuntime().exec(cmd);
            java.io.InputStream in = p.getInputStream();
            StringBuffer out = new StringBuffer();
            byte[] buf = new byte[512];
            int n;
            while ((n = in.read(buf)) > 0 && out.length() < 8192) {
                out.append(new String(buf, 0, n, "UTF-8"));
            }
            in.close();
            p.waitFor();
            return out.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    public void disconnect() {
        connectedSsid = "";
        DSIWLAN s = service;
        if (s == null) {
            return;
        }
        DsiServiceProxy.beginSdkCall();
        try {
            s.requestDisconnectNetwork(ssid == null ? "" : ssid, bssid == null ? "" : bssid);
        } finally {
            DsiServiceProxy.endSdkCall();
        }
    }

    public void forget(String ssid) {
        DSIWLAN s = service;
        if (s == null || ssid == null) {
            return;
        }
        DsiServiceProxy.beginSdkCall();
        try {
            s.requestDeleteTrustedNetwork(ssid, "");
        } finally {
            DsiServiceProxy.endSdkCall();
        }
    }

    public boolean cellularLinkUp() {
        for (int i = 0; i < CELLULAR_IFS.length; i++) {
            if (ipv4(CELLULAR_IFS[i]).length() > 0) {
                return true;
            }
        }
        return false;
    }

    // ---- stock callbacks (never block) ----

    private void onCallback(String name, Object[] args) {
        if (args == null) {
            return;
        }
        if (!"updateDiscoveredNetwork".equals(name)) {
            StringBuffer b = new StringBuffer(name);
            for (int i = 0; i < args.length && i < 4; i++) {
                b.append(' ').append(args[i]);
            }
            SdkGlobals.LOG.append("INFO", SOURCE, "callback " + b);
        }
        if ("updateConnectedNetwork".equals(name) && args.length >= 3) {
            onConnected(str(args[0]), str(args[1]), num(args[2]));
        } else if ("updateDiscoveredNetwork".equals(name) && args.length >= 1 && args[0] instanceof DiscoveredNetwork) {
            onDiscovered((DiscoveredNetwork) args[0]);
        } else if ("responseNetworkSearch".equals(name)) {
            synchronized (scanLock) {
                scanDone = true;
                scanLock.notifyAll();
            }
        } else if ("responseConnectNetwork".equals(name) && args.length >= 3) {
            onConnectResult(num(args[2]));
        } else if ("responseDisconnectNetwork".equals(name)) {
            state = WifiStatus.DISCONNECTED;
            ssid = "";
            connectedSsid = "";
            bssid = "";
            signal = 0;
            publish();
        } else if ("updateWlanEnabled".equals(name) && args.length >= 1) {
            boolean on = !Boolean.FALSE.equals(args[0]);
            rfActive = on;
            publish();
        } else if ("updateRole".equals(name)) {
            role = num(args[0]);
        } else if ("updateRFActive".equals(name)) {
            rfActive = num(args[0]) == 1;
            publish();
        }
    }

    private void onConnected(String network, String address, int result) {
        if (result == ok()) {
            connectedSsid = network == null ? "" : network;
            if (connectedSsid.length() > 0) {
                ssid = connectedSsid;
            }
            bssid = address == null ? "" : address;
            error = "";
            state = WifiStatus.CONNECTED;
        } else if (result == passwordRequired() || result == invalidPassword()) {
            error = "invalid password";
            state = WifiStatus.FAILED;
        } else {
            error = "connect failed (" + result + ")";
            state = WifiStatus.FAILED;
        }
        publish();
    }

    private void onConnectResult(int result) {
        if (result == ok()) {
            // updateConnectedNetwork carries the SSID; leave CONNECTING until it (or DHCP) lands
            state = WifiStatus.CONNECTING;
        } else if (result == passwordRequired() || result == invalidPassword()) {
            error = "invalid password";
            state = WifiStatus.FAILED;
        } else {
            error = "connect failed (" + result + ")";
            state = WifiStatus.FAILED;
        }
        publish();
    }

    private void onDiscovered(DiscoveredNetwork n) {
        if (n == null) {
            return;
        }
        synchronized (scanLock) {
            for (int i = 0; i < scanned.size(); i++) {
                DiscoveredNetwork o = scanned.get(i);
                if (eq(o.networkName, n.networkName) && eq(o.bssidAddress, n.bssidAddress)) {
                    scanned.set(i, n);
                    return;
                }
            }
            if (scanned.size() < 32) {
                scanned.add(n);
            }
        }
    }

    private void publish() {
        pump.publish(new WifiChanged(status()));
    }

    private List<WifiNetwork> copy() {
        List<WifiNetwork> out = new ArrayList<WifiNetwork>();
        for (int i = 0; i < scanned.size(); i++) {
            DiscoveredNetwork n = scanned.get(i);
            String label = encryptionLabel(n.encryptionType);
            out.add(new WifiNetwork(n.networkName == null ? "" : n.networkName, n.bssidAddress, n.signalStrength, label));
        }
        // strongest first; keep the car's order for equal strengths
        Collections.sort(out, new java.util.Comparator() {
            public int compare(Object a, Object b) {
                int sa = ((WifiNetwork) a).signal();
                int sb = ((WifiNetwork) b).signal();
                return sa == sb ? 0 : (sa < sb ? 1 : -1);
            }
        });
        return out;
    }

    /** The stock client flow switches the radio to AP+client before searching or joining. */
    private void ensureClientRole() {
        if (fallbackMode) {
            return;
        }
        DSIWLAN s = service;
        if (s == null) {
            return;
        }
        try {
            if (role != ROLE_COMBINED) {
                DsiServiceProxy.beginSdkCall();
                try {
                    s.setRole(ROLE_COMBINED);
                } finally {
                    DsiServiceProxy.endSdkCall();
                }
                role = ROLE_COMBINED;
            }
            if (!rfActive) {
                DsiServiceProxy.beginSdkCall();
                try {
                    s.setRFActive(true);
                } finally {
                    DsiServiceProxy.endSdkCall();
                }
                rfActive = true;
            }
        } catch (Throwable t) {
            SdkGlobals.LOG.append("WARN", SOURCE, "setRole/setRFActive failed: " + t);
        }
    }

    private DiscoveredNetwork findScanned(String ssid) {
        synchronized (scanLock) {
            for (int i = 0; i < scanned.size(); i++) {
                DiscoveredNetwork n = scanned.get(i);
                if (eq(n.networkName, ssid)) {
                    return n;
                }
            }
        }
        return null;
    }

    private int encryptionFor(String ssid, boolean secured) {
        synchronized (scanLock) {
            for (int i = 0; i < scanned.size(); i++) {
                DiscoveredNetwork n = scanned.get(i);
                if (eq(n.networkName, ssid)) {
                    return n.encryptionType;
                }
            }
        }
        return secured ? crypto("CRYPTOMODELS_CM_WPA2_AES_CCMP", 1) : crypto("CRYPTOMODELS_CM_PLAINTEXT", 0);
    }

    private static String encryptionLabel(int type) {
        if (type == crypto("CRYPTOMODELS_CM_PLAINTEXT", 0)) {
            return "open";
        }
        if (type == crypto("CRYPTOMODELS_CM_WPA2_AES_CCMP", 1)
                || type == crypto("CRYPTOMODELS_CM_WPA2_TKIP_CCMP", 1)
                || type == crypto("CRYPTOMODELS_CM_WPA2_TKIP", 1)) {
            return "WPA2";
        }
        if (type == crypto("CRYPTOMODELS_CM_AES_CCMP", 1)
                || type == crypto("CRYPTOMODELS_CM_TKIP", 1)
                || type == crypto("CRYPTOMODELS_CM_WPA_TKIP_CCMP", 1)) {
            return "WPA";
        }
        if (type == crypto("CRYPTOMODELS_CM_WEP64", 1) || type == crypto("CRYPTOMODELS_CM_WEP128", 1)) {
            return "WEP";
        }
        return "secured";
    }

    private static int attr(String name) {
        return DsiConstants.get("org.dsi.ifc.networking.DSIWLAN", name);
    }

    private static int crypto(String name, int dflt) {
        int v = DsiConstants.get("org.dsi.ifc.networking.DSIWLAN", name);
        return v < 0 ? dflt : v;
    }

    private static int ok() {
        return DsiConstants.get("org.dsi.ifc.networking.DSIWLAN", "RESULT_RESULT_OK");
    }

    private static int passwordRequired() {
        return DsiConstants.get("org.dsi.ifc.networking.DSIWLAN", "RESULT_RESULT_PASSWORD_REQUIRED");
    }

    private static int invalidPassword() {
        return DsiConstants.get("org.dsi.ifc.networking.DSIWLAN", "RESULT_ERROR_INVALID_PASSWORD");
    }

    /** Applies the static address and default route when they differ from the last applied set. */
    private void runStatic() {
        String sig = wantIp + "|" + wantMask + "|" + wantGw + "|" + wantDns;
        if (sig.equals(staticApplied) && wantIp.equals(ipv4(CLIENT_IF))) {
            return;
        }
        if (wantMask.length() == 0) {
            SdkGlobals.LOG.append("WARN", SOURCE, "static Wi-Fi needs a netmask");
            return;
        }
        SdkGlobals.LOG.append("INFO", SOURCE, "setting static " + wantIp + "/" + wantMask + " via " + wantGw);
        try {
            exec("/usr/sbin/ifconfig", new String[] {"ifconfig", CLIENT_IF, "inet", wantIp, "netmask", wantMask});
            if (wantGw.length() > 0) {
                route("delete", "default");
                route("add", "default", wantGw);
            }
            writeDns(wantDns);
            staticApplied = sig;
            publish();
            SdkGlobals.LOG.append("INFO", SOURCE, CLIENT_IF + " static address " + ipv4(CLIENT_IF));
        } catch (Throwable t) {
            SdkGlobals.LOG.append("WARN", SOURCE, "static address failed: " + t);
        }
    }

    /** Drops the address the previous mode set, so DHCP and static never stack. */
    private void clearAddress() {
        try {
            exec("/usr/sbin/ifconfig", new String[] {"ifconfig", CLIENT_IF, "inet", "0.0.0.0"});
            route("delete", "default");
        } catch (Throwable ignored) {
            // no address or no route: nothing to clear
        }
    }

    private boolean route(String... args) {
        return routeWith("/mnt/app/armle/sbin/route", args) || routeWith("/usr/sbin/route", args);
    }

    private boolean routeWith(String binary, String[] args) {
        String[] cmd = new String[args.length + 1];
        cmd[0] = binary;
        System.arraycopy(args, 0, cmd, 1, args.length);
        try {
            exec(binary, cmd);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void writeDns(String dns) {
        if (dns == null || dns.length() == 0) {
            return;
        }
        try {
            java.io.FileOutputStream out = new java.io.FileOutputStream("/etc/resolv.conf");
            try {
                int at = 0;
                while (at <= dns.length()) {
                    int comma = dns.indexOf(',', at);
                    String s = (comma < 0 ? dns.substring(at) : dns.substring(at, comma)).trim();
                    if (s.length() > 0) {
                        out.write(("nameserver " + s + "\n").getBytes("UTF-8"));
                    }
                    if (comma < 0) {
                        break;
                    }
                    at = comma + 1;
                }
            } finally {
                out.close();
            }
            SdkGlobals.LOG.append("INFO", SOURCE, "resolver set to " + dns);
        } catch (Throwable t) {
            SdkGlobals.LOG.append("WARN", SOURCE, "cannot write /etc/resolv.conf: " + t);
        }
    }

    /** Runs a command with the first binary path that exists; ignores a non-zero exit like the stock tools. */
    private void exec(String preferred, String[] cmd) throws java.io.IOException {
        try {
            Runtime.getRuntime().exec(cmd);
            return;
        } catch (java.io.IOException e) {
            if (!preferred.equals(cmd[0])) {
                throw e;
            }
        }
        String[] fallback = (String[]) cmd.clone();
        fallback[0] = preferred.substring(preferred.lastIndexOf('/') + 1);
        Runtime.getRuntime().exec(fallback);
    }

    /** The first non-loopback IPv4 on {@code ifName}; empty if there is none (or the interface is absent). */
    static String ipv4(String ifName) {
        try {
            NetworkInterface nif = NetworkInterface.getByName(ifName);
            if (nif == null) {
                return "";
            }
            Enumeration e = nif.getInetAddresses();
            while (e.hasMoreElements()) {
                InetAddress a = (InetAddress) e.nextElement();
                String ip = a.getHostAddress();
                if (!a.isLoopbackAddress() && a.getAddress().length == 4 && !"0.0.0.0".equals(ip)
                        && !ip.startsWith("169.254.")) {
                    return ip;
                }
            }
        } catch (Throwable ignored) {
            // an absent interface or an old JVM: no address
        }
        return "";
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static int num(Object o) {
        return o instanceof Number ? ((Number) o).intValue() : -1;
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static String stack(Throwable t) {
        java.io.StringWriter w = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(w));
        String s = w.toString().replace('\r', ' ').replace('\n', ' ');
        return s.length() > 900 ? s.substring(0, 900) : s;
    }
}
