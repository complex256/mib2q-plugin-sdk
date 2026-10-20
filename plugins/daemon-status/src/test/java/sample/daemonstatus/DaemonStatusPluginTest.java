package sample.daemonstatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import mibsdk.adapter.fake.FakePlatform;
import mibsdk.adapter.fake.FakeUi;
import mibsdk.api.Features;
import mibsdk.platform.HookTable;
import mibsdk.platform.SdkRoots;
import mibsdk.platform.UiTable;
import mibsdk.runtime.Clock;
import mibsdk.runtime.PluginState;
import mibsdk.runtime.RingLog;
import mibsdk.runtime.RuntimeConfig;
import mibsdk.runtime.SdkRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DaemonStatusPluginTest {

    private static final String PAGE = "daemon-status/status";
    private static final String TS = "v=1\ndaemon=tailscale\nstate=Running\nip=100.64.0.7\ntailnet=example.ts.net\n"
            + "peers=3\npeers_online=2\nlast_handshake_age_s=10\nage_s=1\n";
    private static final String MQ = "v=1\ndaemon=carmqtt\nversion=0.1.0\nmqtt=1\nlast_publish_age_s=5\nsignals=40\n"
            + "exlap=0\nexlap_err=\nuptime_s=100\n";

    /** Replies with a fixed text then closes; counts connections. */
    private static final class FakeDaemon implements AutoCloseable {
        final ServerSocket server;
        final AtomicInteger hits = new AtomicInteger();
        volatile String reply;

        FakeDaemon(String reply) throws IOException {
            this.reply = reply;
            server = new ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"));
            Thread t = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket s = server.accept()) {
                        hits.incrementAndGet();
                        OutputStream o = s.getOutputStream();
                        o.write(this.reply.getBytes("UTF-8"));
                        o.flush();
                        s.shutdownOutput();
                        s.getInputStream().read(new byte[16]);
                    } catch (IOException e) {
                        // closed or client gone
                    }
                }
            });
            t.setDaemon(true);
            t.start();
        }

        int port() {
            return server.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }

    @TempDir
    File tmp;

    private SdkRuntime rt;
    private final UiTable table = new UiTable(64);
    private final FakeUi ui = new FakeUi(table);

    @AfterEach
    void tearDown() {
        if (rt != null) {
            rt.stop();
        }
    }

    private static int closedPort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            return s.getLocalPort();
        }
    }

    private void start(int tsPort, int mqPort, String... features) throws IOException {
        Set<String> f = new HashSet<String>(Arrays.asList(features));
        FakePlatform fake = new FakePlatform("HMI_TEST", f);
        SdkRoots roots = FakePlatform.roots(tmp);
        File base = roots.install();
        new File(base, "plugins").mkdirs();
        Files.copy(new File(System.getProperty("mibsdk.pluginJar")).toPath(),
                new File(base, "plugins/daemon-status.jar").toPath(), StandardCopyOption.REPLACE_EXISTING);
        write(new File(base, "sdk.properties"), "plugin.start.delay.ms=0\nheap.low.bytes=0\nui.publish.min.ms=0\n"
                + "log.file=" + new File(tmp, "mibsdk.log").getPath() + "\n");
        write(new File(base, "grants.properties"), "daemon-status=network,ui.slot.statusbar\n");
        write(new File(roots.persist(), "data/daemon-status.properties"), "ts.port=" + tsPort + "\nmq.port=" + mqPort + "\n");
        Clock clock = new Clock() {
            public long uptimeMs() {
                return System.nanoTime() / 1000000L;
            }
        };
        rt = new SdkRuntime(RuntimeConfig.load(roots), fake, clock, new HookTable(64), table, new RingLog(500));
        rt.start();
        await(() -> rt.statuses().size() == 1 && rt.statuses().get(0).state() == PluginState.ENABLED, "ENABLED");
    }

    private void await(java.util.function.BooleanSupplier c, String what) {
        for (int i = 0; i < 1000; i++) {
            if (c.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }
        fail("never " + what + ": " + rt.statuses() + " status=" + ui.statusLine() + " page=" + ui.render(PAGE)
                + "\n" + rt.log().snapshot());
    }

    private static void write(File f, String text) throws IOException {
        f.getParentFile().mkdirs();
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(text.getBytes("UTF-8"));
        }
    }

    @Test
    void bothUp() throws Exception {
        try (FakeDaemon ts = new FakeDaemon(TS); FakeDaemon mq = new FakeDaemon(MQ)) {
            start(ts.port(), mq.port(), Features.SLOT_STATUS_BAR, Features.UI_PAGES);
            await(() -> "TS up MQ up".equals(ui.statusLine()), "TS up MQ up");
            await(() -> ui.render(PAGE).size() == 6, "full page");
            List<String> lines = ui.render(PAGE);
            assertEquals(Arrays.asList("Tailscale: Running 100.64.0.7", "Tailnet: example.ts.net", "Peers: 2/3",
                    "carmqtt: MQTT on, ExLAP off", "Last publish: 5 s ago", "Refresh now"), lines);
        }
    }

    @Test
    void daemonDownAndErrorShown() throws Exception {
        String down = "v=1\ndaemon=tailscale\nstate=down\nip=\ntailnet=\npeers=0\npeers_online=0\n";
        String mqErr = "v=1\ndaemon=carmqtt\nmqtt=0\nlast_publish_age_s=-1\nexlap=0\nexlap_err=connect refused\n";
        try (FakeDaemon ts = new FakeDaemon(down); FakeDaemon mq = new FakeDaemon(mqErr)) {
            start(ts.port(), mq.port(), Features.SLOT_STATUS_BAR, Features.UI_PAGES);
            await(() -> ui.render(PAGE).contains("Error: connect refused"), "page filled");
            await(() -> "TS dn MQ dn".equals(ui.statusLine()), "TS dn MQ dn");
            List<String> lines = ui.render(PAGE);
            assertTrue(lines.contains("Tailscale: down"), lines.toString());
            assertTrue(lines.contains("Last publish: never"), lines.toString());
            assertTrue(lines.contains("Error: connect refused"), lines.toString());
        }
    }

    @Test
    void unreachableShowsQuestionMarks() throws Exception {
        start(closedPort(), closedPort(), Features.SLOT_STATUS_BAR, Features.UI_PAGES);
        await(() -> "TS ? MQ ?".equals(ui.statusLine()), "TS ? MQ ?");
        assertTrue(ui.render(PAGE).contains("Refresh now"), ui.render(PAGE).toString());
    }

    @Test
    void refreshItemQueriesNow() throws Exception {
        try (FakeDaemon ts = new FakeDaemon(TS); FakeDaemon mq = new FakeDaemon(MQ)) {
            start(ts.port(), mq.port(), Features.SLOT_STATUS_BAR, Features.UI_PAGES);
            await(() -> "TS up MQ up".equals(ui.statusLine()), "first poll");
            mq.reply = MQ.replace("mqtt=1", "mqtt=0");
            ui.select(PAGE, "refresh");
            await(() -> "TS up MQ dn".equals(ui.statusLine()), "TS up MQ dn after refresh");
        }
    }

    @Test
    void noStatusSlotStillHasPage() throws Exception {
        try (FakeDaemon ts = new FakeDaemon(TS); FakeDaemon mq = new FakeDaemon(MQ)) {
            start(ts.port(), mq.port(), Features.UI_PAGES);
            await(() -> ui.render(PAGE).contains("Peers: 2/3"), "page filled");
            assertEquals("", ui.statusLine());
        }
    }

    @Test
    void staleLastPublishIsFlaggedButItemUnchanged() throws Exception {
        String old = MQ.replace("last_publish_age_s=5", "last_publish_age_s=4000");
        try (FakeDaemon ts = new FakeDaemon(TS); FakeDaemon mq = new FakeDaemon(old)) {
            start(ts.port(), mq.port(), Features.SLOT_STATUS_BAR, Features.UI_PAGES);
            await(() -> "TS up MQ up".equals(ui.statusLine()), "TS up MQ up");
            assertTrue(ui.render(PAGE).contains("Last publish: 4000 s ago (stale)"), ui.render(PAGE).toString());
        }
    }

    @Test
    void garbledRepliesAreToleratedAndWrongDaemonIsUnknown() throws Exception {
        String junk = "v=1\ndaemon=tailscale\nstate=Running\nip=\ntailnet=\npeers=lots\npeers_online=-3\n\u0000\u0001=\n===\n";
        String wrong = MQ; // carmqtt answering on the tailscale port
        try (FakeDaemon ts = new FakeDaemon(junk); FakeDaemon mq = new FakeDaemon(wrong.replace("daemon=carmqtt", "daemon=other"))) {
            start(ts.port(), mq.port(), Features.SLOT_STATUS_BAR, Features.UI_PAGES);
            await(() -> "TS up MQ ?".equals(ui.statusLine()), "TS up MQ ?");
            List<String> lines = ui.render(PAGE);
            assertTrue(lines.contains("Peers: ?/?"), lines.toString());
            assertTrue(lines.contains("carmqtt: ?"), lines.toString());
        }
    }
}
