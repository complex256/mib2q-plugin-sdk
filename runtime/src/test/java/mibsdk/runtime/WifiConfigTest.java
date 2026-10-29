package mibsdk.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import mibsdk.api.WifiNetwork;
import mibsdk.api.WifiStatus;
import mibsdk.platform.WifiIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WifiConfigTest {

    @TempDir
    File dir;

    @Test
    void missingFileUsesTheDefaultAndNoCredentials() {
        WifiConfig c = WifiConfig.load(new File(dir, "wifi.toml"), true);
        assertTrue(c.enabled);
        assertEquals("", c.ssid);
        assertEquals("", c.password);
        assertFalse(WifiConfig.load(new File(dir, "wifi.toml"), false).enabled);
    }

    @Test
    void writesAndReadsTheSameValues() throws Exception {
        File f = new File(dir, "wifi.toml");
        WifiConfig c = new WifiConfig(true, "beepboop", "test-password");
        Files.write(f.toPath(), c.toText().getBytes(StandardCharsets.UTF_8));
        assertEquals(c, WifiConfig.load(f, false));
    }

    @Test
    void quotesAndEscapesRoundTrip() throws Exception {
        File f = new File(dir, "wifi.toml");
        WifiConfig c = new WifiConfig(false, "a\"b\\c", "p\tq");
        Files.write(f.toPath(), c.toText().getBytes(StandardCharsets.UTF_8));
        assertEquals(c, WifiConfig.load(f, true));
    }

    @Test
    void aBrokenFileIsTreatedAsNoCredentials() throws Exception {
        File f = new File(dir, "wifi.toml");
        Files.write(f.toPath(), "enabled = true\nssid = \"unterminated\n".getBytes(StandardCharsets.UTF_8));
        WifiConfig c = WifiConfig.load(f, false);
        assertFalse(c.enabled);
        assertEquals("", c.ssid);
    }

    @Test
    void managerAppliesAnExternalEditAndWritesTheStatusFile() throws Exception {
        File cfg = new File(dir, "wifi.toml");
        File status = new File(dir, "wifi.status");
        FakeWifi io = new FakeWifi();
        RingLog log = new RingLog(32);
        FakeClock clock = new FakeClock();
        final int[] changed = new int[1];
        WifiManager m = new WifiManager(cfg, status, () -> io, log, clock, () -> changed[0]++);
        m.start();
        assertTrue(cfg.isFile(), "first run creates the file");
        assertTrue(m.enabled(), "no SIM: on by default");
        assertTrue(io.connects.isEmpty());

        Files.write(cfg.toPath(), "enabled = true\nssid = \"beepboop\"\npassword = \"test-password\"\n"
                .getBytes(StandardCharsets.UTF_8));
        clock.advance(2500);
        m.poll();
        assertEquals(Arrays.asList("beepboop"), io.connects);
        assertEquals("test-password", io.password);
        assertTrue(status.isFile());
        assertTrue(new String(Files.readAllBytes(status.toPath()), StandardCharsets.UTF_8).contains("ssid=beepboop"));

        m.setEnabled(false);
        assertTrue(io.disconnected);
        assertFalse(m.enabled());
        m.forget();
        assertTrue(io.forgotten.contains("beepboop"));
        assertEquals("", m.savedSsid());
        assertTrue(changed[0] > 0);
    }

    @Test
    void managerAppliesAnAddressModeChange() throws Exception {
        File cfg = new File(dir, "wifi.toml");
        File status = new File(dir, "wifi.status");
        FakeWifi io = new FakeWifi();
        FakeClock clock = new FakeClock();
        WifiManager m = new WifiManager(cfg, status, () -> io, new RingLog(16), clock, () -> { });
        m.start();
        assertTrue(io.lastDhcp);
        Files.write(cfg.toPath(), ("enabled = true\nssid = \"\"\ndhcp = false\nip = \"10.20.0.222\"\n"
                + "netmask = \"255.255.255.0\"\ngateway = \"10.20.0.1\"\n").getBytes(StandardCharsets.UTF_8));
        clock.advance(2500);
        m.poll();
        assertFalse(io.lastDhcp);
        assertEquals("10.20.0.222", io.lastIp);
    }

    private static final class FakeWifi implements WifiIo {
        final List<String> connects = new ArrayList<String>();
        final List<String> forgotten = new ArrayList<String>();
        String password = "";
        boolean disconnected;
        boolean lastDhcp = true;
        String lastIp = "";
        WifiStatus status = new WifiStatus(WifiStatus.DISCONNECTED, "", "", "", 0, "");

        public WifiStatus status() {
            return status;
        }

        public List<WifiNetwork> scan(int timeoutMs) {
            return new ArrayList<WifiNetwork>();
        }

        public void connect(String ssid, String bssid, String password, boolean secured) {
            connects.add(ssid);
            this.password = password;
            status = new WifiStatus(WifiStatus.CONNECTED, ssid, bssid, "10.0.0.2", 50, "");
        }

        public void disconnect() {
            disconnected = true;
            status = new WifiStatus(WifiStatus.DISCONNECTED, "", "", "", 0, "");
        }

        public void forget(String ssid) {
            forgotten.add(ssid);
        }

        public void ensureAddress(boolean dhcp, String ip, String netmask, String gateway, String dns) {
            lastDhcp = dhcp;
            lastIp = ip;
        }

        public boolean cellularLinkUp() {
            return false;
        }
    }
}
