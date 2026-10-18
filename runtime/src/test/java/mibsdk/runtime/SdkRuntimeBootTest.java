package mibsdk.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import mibsdk.adapter.fake.FakePlatform;
import mibsdk.api.Features;
import mibsdk.api.SoundDirections;
import mibsdk.platform.HookTable;
import mibsdk.platform.PlatformAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Task 12: boot guard, safe mode, delayed plugin start, health and heap timers, as wired in SdkRuntime. */
class SdkRuntimeBootTest {

    private static final String HI = "ctx.log().info(\"hi\");";
    private static final SoundDirections F1R1L0X0 = new SoundDirections(true, true, false, false);

    @TempDir
    File tmp;

    private File base;
    private File plugins;
    private PluginJars jars;
    private final FakeClock clock = new FakeClock();
    private final FakePlatform fake = new FakePlatform("HMI_TEST",
            new HashSet<String>(Arrays.asList(Features.IGNITION, Features.PARKING, Features.HOOK_PARKING)));
    private SdkRuntime rt;

    @BeforeEach
    void setUp() {
        base = new File(tmp, "mibsdk");
        plugins = new File(base, "plugins");
        plugins.mkdirs();
        jars = new PluginJars(new File(tmp, "fixtures"));
        SerialExecutor.watchdogPeriodMs = 10;
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        SdkRuntime.testEnableHook = null;
        if (rt != null) {
            final SdkRuntime r = rt;
            Thread t = new Thread() {
                public void run() {
                    r.stop();
                }
            };
            t.setDaemon(true);
            t.start();
            t.join(15000);
        }
        SerialExecutor.watchdogPeriodMs = 1000;
    }

    // ---- helpers ----

    private void plugin(String id) throws IOException {
        jars.write(plugins, id, "1", null, null, HI, null);
    }

    private SdkRuntime runtime(String sdkProperties, PlatformAdapter platform) throws IOException {
        write(new File(base, "sdk.properties"), "log.file=" + logFile().getPath() + "\n" + sdkProperties);
        rt = new SdkRuntime(RuntimeConfig.load(base), platform, clock, new HookTable(64), new RingLog(500));
        return rt;
    }

    private SdkRuntime start(String sdkProperties) throws IOException {
        runtime(sdkProperties, fake).start();
        return rt;
    }

    private File logFile() {
        return new File(tmp, "mibsdk.log");
    }

    private static void write(File f, String text) throws IOException {
        f.getParentFile().mkdirs();
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(text.getBytes(StandardCharsets.ISO_8859_1));
        } finally {
            out.close();
        }
    }

    private Properties bootState() throws IOException {
        Properties p = new Properties();
        FileInputStream in = new FileInputStream(new File(base, "boot.state"));
        try {
            p.load(in);
        } finally {
            in.close();
        }
        return p;
    }

    private void awaitBootState(String key, String value) throws Exception {
        for (int i = 0; i < 500; i++) {
            if (value.equals(bootState().getProperty(key))) {
                return;
            }
            Thread.sleep(10);
        }
        fail(key + " never became " + value + ": " + bootState() + "\n" + rt.log().snapshot());
    }

    private int count(String source, String msg) {
        int n = 0;
        for (String line : rt.log().snapshot()) {
            if (line.endsWith(" " + source + " " + msg)) {
                n++;
            }
        }
        return n;
    }

    private boolean logContains(String fragment) {
        return rt.log().snapshot().toString().contains(fragment);
    }

    private PluginStatus status(String id) {
        for (PluginStatus s : rt.statuses()) {
            if (s.id().equals(id)) {
                return s;
            }
        }
        throw new AssertionError("no status for " + id + " in " + rt.statuses() + "\n" + rt.log().snapshot());
    }

    private void awaitState(String id, PluginState state) throws InterruptedException {
        for (int i = 0; i < 500; i++) {
            List<PluginStatus> all = rt.statuses();
            for (PluginStatus s : all) {
                if (s.id().equals(id) && s.state() == state) {
                    return;
                }
            }
            Thread.sleep(10);
        }
        fail(id + " never reached " + state + ": " + rt.statuses() + "\n" + rt.log().snapshot());
    }

    private int enabledLines() {
        int n = 0;
        for (String line : rt.log().snapshot()) {
            if (line.contains(" ENABLED enabled")) {
                n++;
            }
        }
        return n;
    }

    // ---- STATUS line, adapter ----

    @Test
    void statusLineNamesAdapterAndSafeMode() throws Exception {
        start("plugin.start.delay.ms=0\nheap.low.bytes=0\n");
        assertEquals(1, count("mibsdk", "runtime started adapter=fake safeMode=false"), rt.log().snapshot().toString());
        assertFalse(rt.safeMode());
    }

    @Test
    void noAdapterLogsNoneAndStops() throws Exception {
        plugin("hello");
        runtime("plugin.start.delay.ms=0\n", null).start();

        assertEquals(1, count("mibsdk", "runtime started adapter=none safeMode=false"), rt.log().snapshot().toString());
        assertTrue(logContains("runtime stopped"), rt.log().snapshot().toString());
        assertTrue(rt.statuses().isEmpty());
        rt.reload("hello");
        assertTrue(rt.statuses().isEmpty(), "a stopped runtime loads nothing");
        assertFalse(new File(base, "boot.state").exists(), "the SDK is off: the boot guard is not touched");
        String file = new String(Files.readAllBytes(logFile().toPath()), StandardCharsets.UTF_8);
        assertTrue(file.contains(" STATUS mibsdk runtime started adapter=none safeMode=false"), file);
        rt.stop(); // harmless
    }

    // ---- delayed start ----

    @Test
    void pluginsLoadAfterStartDelayWithoutBlockingStart() throws Exception {
        plugin("hello");
        long t0 = System.nanoTime();
        start("plugin.start.delay.ms=30000\nheap.low.bytes=0\n");
        assertTrue((System.nanoTime() - t0) / 1000000L < 5000, "start() does not wait for the delay");
        assertEquals(1, count("mibsdk", "runtime started adapter=fake safeMode=false"));
        assertTrue(rt.statuses().isEmpty());

        clock.advance(29999);
        Thread.sleep(300);
        assertTrue(rt.statuses().isEmpty(), "not before the delay on the Clock");

        clock.advance(1);
        awaitState("hello", PluginState.ENABLED);
        assertEquals(1, count("hello", "hi"));
    }

    @Test
    void stopDuringStartDelayEnablesNothing() throws Exception {
        plugin("hello");
        start("plugin.start.delay.ms=30000\nheap.low.bytes=0\n");
        rt.stop();

        clock.advance(30000);
        Thread.sleep(500);
        assertTrue(rt.statuses().isEmpty(), rt.statuses().toString());
        assertEquals(0, count("hello", "hi"));
        assertEquals(0, enabledLines());
    }

    @Test
    void stopRequestedWhileLoadingBailsBeforeEnablingMore() throws Exception {
        plugin("a-first");
        plugin("b-second");
        final Thread[] stopper = new Thread[1];
        SdkRuntime.testEnableHook = new SdkRuntime.EnableHook() {
            public void afterResources(String pluginId) {
                if (!pluginId.equals("a-first")) {
                    return;
                }
                final SdkRuntime r = rt;
                Thread t = new Thread() {
                    public void run() {
                        r.stop();
                    }
                };
                t.setDaemon(true);
                stopper[0] = t;
                t.start();
                for (int i = 0; i < 500 && !r.isStopped(); i++) {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        };
        runtime("plugin.start.delay.ms=0\nheap.low.bytes=0\n", fake);
        rt.start();
        stopper[0].join(15000);
        assertFalse(stopper[0].isAlive());

        assertEquals(0, enabledLines(), rt.log().snapshot().toString());
        assertEquals(0, count("b-second", "hi"), "nothing enabled after stop() was requested");
        assertEquals(PluginState.DISABLED, status("a-first").state(), "the in-flight enable was undone");
        assertEquals(1, count("a-first", "disabled"), "onDisable ran for the in-flight plugin");
    }

    // ---- safe mode ----

    /** Carried from Task 11: safe mode replays pending ledger restores and loads no plugins. */
    @Test
    void safeModeMarkerRestoresLedgerAndLoadsNoPlugins() throws Exception {
        plugin("muter");
        write(new File(base, "ledger.properties"), "parking.soundDirections=muter|PERSISTENT|F1R1L0X0|F1R0L0X0\n");
        write(new File(base, "SAFE_MODE"), "");
        start("plugin.start.delay.ms=0\nheap.low.bytes=0\n");

        assertTrue(rt.safeMode());
        assertEquals(1, count("mibsdk", "runtime started adapter=fake safeMode=true"), rt.log().snapshot().toString());
        assertTrue(logContains("SAFE_MODE marker"), rt.log().snapshot().toString());
        for (int i = 0; i < 500 && fake.appliedLog().isEmpty(); i++) {
            Thread.sleep(10);
        }
        assertEquals(Arrays.asList(F1R1L0X0), fake.appliedLog(), "the prior is back");
        for (int i = 0; i < 500 && !rt.activeOverrides().isEmpty(); i++) {
            Thread.sleep(10);
        }
        assertTrue(rt.activeOverrides().isEmpty());
        File ledger = new File(base, "ledger.properties");
        for (int i = 0; i < 500 && ledger.length() != 0; i++) {
            Thread.sleep(10); // the file is rewritten just after the entry leaves memory
        }
        assertEquals(0, ledger.length());
        assertTrue(rt.statuses().isEmpty(), "no plugin discovered or enabled");
        assertEquals(0, count("muter", "hi"));

        rt.reload("muter");
        assertTrue(rt.statuses().isEmpty(), "reload is refused in safe mode");
        assertTrue(logContains("reload ignored: safe mode"), rt.log().snapshot().toString());

        clock.advance(120000);
        Thread.sleep(300);
        assertEquals(0, enabledLines());
    }

    @Test
    void unhealthyBootsEnterSafeModeAndStayUnhealthy() throws Exception {
        plugin("hello");
        write(new File(base, "boot.state"), "unhealthyBoots=2\nlastShutdownClean=false\n");
        start("plugin.start.delay.ms=0\nheap.low.bytes=0\n");

        assertTrue(rt.safeMode());
        assertTrue(logContains("3 unhealthy boots"), rt.log().snapshot().toString());
        assertEquals("3", bootState().getProperty("unhealthyBoots"));
        assertTrue(rt.statuses().isEmpty());

        clock.advance(60000); // no plugins enabled: safe mode never marks the boot healthy
        Thread.sleep(300);
        assertEquals("3", bootState().getProperty("unhealthyBoots"));

        fake.setIgnition(false); // a clean shutdown is still recorded
        awaitBootState("lastShutdownClean", "true");
    }

    // ---- boot guard wiring ----

    @Test
    void ignitionOffMarksCleanShutdown() throws Exception {
        start("plugin.start.delay.ms=0\nheap.low.bytes=0\n");
        assertEquals("false", bootState().getProperty("lastShutdownClean"));
        fake.setIgnition(false);
        assertEquals("true", bootState().getProperty("lastShutdownClean"), "written before publish returns");
    }

    @Test
    void healthyAfterUptimeWithPluginsEnabled() throws Exception {
        plugin("hello");
        write(new File(base, "boot.state"), "unhealthyBoots=1\nlastShutdownClean=false\n");
        start("plugin.start.delay.ms=30000\nhealthy.after.ms=60000\nheap.low.bytes=0\n");
        assertEquals("2", bootState().getProperty("unhealthyBoots"));

        clock.advance(30000);
        awaitState("hello", PluginState.ENABLED);
        clock.advance(59999);
        Thread.sleep(300);
        assertEquals("2", bootState().getProperty("unhealthyBoots"), "healthy only healthyAfterMs after enabling");

        clock.advance(1);
        awaitBootState("unhealthyBoots", "0");
        assertFalse(rt.safeMode());
    }

    @Test
    void notMarkedHealthyIfStoppedFirst() throws Exception {
        plugin("hello");
        write(new File(base, "boot.state"), "unhealthyBoots=1\nlastShutdownClean=false\n");
        start("plugin.start.delay.ms=0\nhealthy.after.ms=60000\nheap.low.bytes=0\n");
        rt.stop();
        clock.advance(60000);
        Thread.sleep(300);
        assertEquals("2", bootState().getProperty("unhealthyBoots"));
    }

    // ---- heap watch wiring ----

    @Test
    void heapLowQuarantinesMostRecentlyEnabledPlugin() throws Exception {
        plugin("a-first");
        plugin("b-second");
        // Any real reading is below 2 GB: every check is "low".
        start("plugin.start.delay.ms=0\nheap.low.bytes=2147483647\nheap.low.checks=3\nheap.check.ms=5000\n");
        assertEquals(PluginState.ENABLED, status("a-first").state());
        assertEquals(PluginState.ENABLED, status("b-second").state());

        for (int i = 0; i < 2; i++) {
            clock.advance(5000);
            Thread.sleep(300);
        }
        assertEquals(PluginState.ENABLED, status("b-second").state(), "two low readings are not enough");
        clock.advance(5000);
        awaitState("b-second", PluginState.QUARANTINED);
        assertTrue(status("b-second").reason().contains("heap low"), status("b-second").reason());
        assertEquals(PluginState.ENABLED, status("a-first").state(), "only the most recently enabled one");
    }
}
