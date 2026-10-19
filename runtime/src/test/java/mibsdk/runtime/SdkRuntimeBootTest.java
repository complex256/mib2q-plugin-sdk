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
        SdkRuntime.testBeforeLoadAll = null;
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
        rt = new SdkRuntime(RuntimeConfig.load(FakePlatform.roots(tmp)), platform, clock, new HookTable(64), new RingLog(500));
        return rt;
    }

    private SdkRuntime start(String sdkProperties) throws IOException {
        runtime(sdkProperties, fake).start();
        return rt;
    }

    /** The writable persistent root (boot.state, ledger.properties, data/); base is the read-only install root. */
    private File persist() {
        return FakePlatform.roots(tmp).persist();
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
        FileInputStream in = new FileInputStream(new File(persist(), "boot.state"));
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
        assertTrue(rt.log().snapshot().toString().matches("(?s).* STATUS mibsdk heap free=\\d+ total=\\d+.*"),
                "the free-heap baseline is logged once at start: " + rt.log().snapshot());
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
        assertFalse(new File(persist(), "boot.state").exists(), "the SDK is off: the boot guard is not touched");
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
        write(new File(persist(), "ledger.properties"), "parking.soundDirections=muter|PERSISTENT|F1R1L0X0|F1R0L0X0\n");
        write(new File(base, "SAFE_MODE"), "");
        start("plugin.start.delay.ms=0\nheap.low.bytes=0\n");

        assertTrue(rt.safeMode());
        assertEquals(1, count("mibsdk", "runtime started adapter=fake safeMode=true"), rt.log().snapshot().toString());
        assertTrue(logContains("safeMode=true reason=marker"), rt.log().snapshot().toString());
        for (int i = 0; i < 500 && fake.appliedLog().isEmpty(); i++) {
            Thread.sleep(10);
        }
        assertEquals(Arrays.asList(F1R1L0X0), fake.appliedLog(), "the prior is back");
        for (int i = 0; i < 500 && !rt.activeOverrides().isEmpty(); i++) {
            Thread.sleep(10);
        }
        assertTrue(rt.activeOverrides().isEmpty());
        File ledger = new File(persist(), "ledger.properties");
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
        write(new File(persist(), "boot.state"), "unhealthyBoots=2\nlastShutdownClean=false\n");
        start("plugin.start.delay.ms=0\nheap.low.bytes=0\n");

        assertTrue(rt.safeMode());
        assertTrue(logContains("safeMode=true reason=unhealthy-boots n=3"), rt.log().snapshot().toString());
        assertEquals("3", bootState().getProperty("unhealthyBoots"));
        assertEquals("true", bootState().getProperty("lastShutdownClean"), "a safe-mode boot does not count");
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
        write(new File(persist(), "boot.state"), "unhealthyBoots=1\nlastShutdownClean=false\n");
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
    void wrapBootsClearedOnceTheHmiRanHealthyEvenWithoutPlugins() throws Exception {
        write(new File(persist(), "boot.state"), "unhealthyBoots=0\nlastShutdownClean=true\nwrapBoots=2\n");
        write(new File(base, "SAFE_MODE"), ""); // no plugin ever loads: HMI health alone counts
        start("plugin.start.delay.ms=30000\nhealthy.after.ms=60000\nheap.low.bytes=0\n");
        assertTrue(rt.safeMode());
        assertEquals("2", bootState().getProperty("wrapBoots"));
        clock.advance(59999);
        Thread.sleep(300);
        assertEquals("2", bootState().getProperty("wrapBoots"), "healthyAfterMs after the runtime started");
        clock.advance(1);
        awaitBootState("wrapBoots", "0");
    }

    @Test
    void wrapLoopIsLoggedAndLatched() throws Exception {
        write(new File(persist(), "boot.state"), "unhealthyBoots=0\nlastShutdownClean=true\nwrapBoots=3\n");
        start("plugin.start.delay.ms=0\nhealthy.after.ms=60000\nheap.low.bytes=0\n");
        assertFalse(rt.safeMode(), "plugins still run, on the unwrapped services");
        assertTrue(logContains("WARN mibsdk wrapping=off reason=wrap-boots n=3"), rt.log().snapshot().toString());
        clock.advance(60000);
        Thread.sleep(300);
        fake.setIgnition(false);
        assertEquals("3", bootState().getProperty("wrapBoots"), "only a reset clears a latched wrap loop");
    }

    // ---- writable roots: degrade loudly (B1: /mnt/app was read-only and nothing said so) ----

    private void awaitLog(String fragment) throws InterruptedException {
        for (int i = 0; i < 500 && !logContains(fragment); i++) {
            Thread.sleep(10);
        }
        assertTrue(logContains(fragment), "no '" + fragment + "' in " + rt.log().snapshot());
    }

    private File ram() {
        return FakePlatform.roots(tmp).ram();
    }

    @Test
    void writableRootsAreReportedAndHealthNamesTheEnabledPlugins() throws Exception {
        plugin("hello");
        start("plugin.start.delay.ms=0\nhealthy.after.ms=60000\nheap.low.bytes=0\n");
        assertEquals(1, count("mibsdk", "state persist=" + persist() + " ok ram=" + ram() + " ok bootGuard=armed"),
                rt.log().snapshot().toString());
        clock.advance(60000);
        awaitLog("INFO mibsdk boot healthy: 1 plugin(s) enabled for 60000 ms");
        assertTrue(persist().isDirectory() && new File(ram(), "cache").isDirectory(), "roots created when missing");
    }

    @Test
    void unwritablePersistRootMakesTheBootGuardInertAndSaysSo() throws Exception {
        plugin("hello");
        write(persist(), "a file where the directory should be"); // nothing can be created under it
        start("plugin.start.delay.ms=0\nhealthy.after.ms=60000\nheap.low.bytes=0\n");
        assertEquals(1, count("mibsdk", "state persist=" + persist() + " UNWRITABLE ram=" + ram()
                + " ok bootGuard=inert"), rt.log().snapshot().toString());
        assertTrue(logContains("WARN mibsdk persistent root " + persist() + " is not writable"),
                rt.log().snapshot().toString());
        assertEquals(PluginState.ENABLED, status("hello").state(), "plugins still run, without the safety net");

        clock.advance(60000);
        awaitLog("WARN mibsdk boot guard inert");
        assertFalse(logContains("boot healthy"), rt.log().snapshot().toString());
    }

    @Test
    void unwritableRamRootIsReportedAndAllFailedIsNeverHealthy() throws Exception {
        plugin("hello");
        write(ram(), "a file where the directory should be");
        start("plugin.start.delay.ms=0\nhealthy.after.ms=60000\nheap.low.bytes=0\n");
        assertEquals(1, count("mibsdk", "state persist=" + persist() + " ok ram=" + ram()
                + " UNWRITABLE bootGuard=armed"), rt.log().snapshot().toString());
        assertTrue(logContains("WARN mibsdk RAM root " + ram() + " is not writable"), rt.log().snapshot().toString());
        assertEquals(PluginState.FAILED, status("hello").state());

        clock.advance(60000);
        awaitLog("no plugin is enabled (FAILED=1)");
        assertFalse(logContains("boot healthy"), rt.log().snapshot().toString());
        assertEquals("0", bootState().getProperty("unhealthyBoots"), "the HMI itself survived: no crash to count");
    }

    @Test
    void notMarkedHealthyIfStoppedFirst() throws Exception {
        plugin("hello");
        write(new File(persist(), "boot.state"), "unhealthyBoots=1\nlastShutdownClean=false\n");
        start("plugin.start.delay.ms=0\nhealthy.after.ms=60000\nheap.low.bytes=0\n");
        rt.stop();
        clock.advance(60000);
        Thread.sleep(300);
        assertEquals("2", bootState().getProperty("unhealthyBoots"));
    }

    /** The adapter may learn of a wrapped hook service only after start(): plugins see the features at load. */
    @Test
    void featuresAreReadAgainBeforePluginsLoad() throws Exception {
        jars.write(plugins, "probe", "1", null, null,
                "ctx.log().info(\"hook=\" + ctx.has(Features.HOOK_PARKING));", null);
        final java.util.concurrent.atomic.AtomicBoolean hooked = new java.util.concurrent.atomic.AtomicBoolean();
        final FakePlatform inner = new FakePlatform("HMI_TEST",
                new HashSet<String>(Arrays.asList(Features.IGNITION, Features.PARKING)));
        PlatformAdapter late = new PlatformAdapter() {
            public String id() {
                return inner.id();
            }

            public boolean supports(String v) {
                return inner.supports(v);
            }

            public java.util.Set<String> features() {
                java.util.Set<String> f = new HashSet<String>(inner.features());
                if (hooked.get()) {
                    f.add(Features.HOOK_PARKING);
                }
                return f;
            }

            public void start(mibsdk.platform.PlatformHost host) {
                inner.start(host);
            }

            public void stop() {
                inner.stop();
            }

            public mibsdk.platform.ParkingIo parking() {
                return inner.parking();
            }
        };
        runtime("plugin.start.delay.ms=30000\nheap.low.bytes=0\n", late).start();
        hooked.set(true); // e.g. the parking service registers (and is wrapped) during the start delay
        clock.advance(30000);
        awaitState("probe", PluginState.ENABLED);
        assertEquals(1, count("probe", "hook=true"), rt.log().snapshot().toString());
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

        // The GC did not help: the interval doubled, so three more ticks hold at most two readings.
        for (int i = 0; i < 3; i++) {
            clock.advance(5000);
            Thread.sleep(300);
        }
        assertEquals(PluginState.ENABLED, status("a-first").state(), "backed off: no second victim yet");
        for (int i = 0; i < 20 && status("a-first").state() == PluginState.ENABLED; i++) {
            clock.advance(5000); // a tick that arrives while the control thread is busy may be skipped: keep going
            Thread.sleep(200);
        }
        awaitState("a-first", PluginState.QUARANTINED);
    }

    @Test
    void heapWatchLeavesTheHmiAloneWithoutPlugins() throws Exception {
        // No plugin: low readings trigger neither a GC nor a quarantine (nothing to log).
        start("plugin.start.delay.ms=0\nheap.low.bytes=2147483647\nheap.low.checks=1\nheap.check.ms=5000\n");
        for (int i = 0; i < 3; i++) {
            clock.advance(5000);
            Thread.sleep(100);
        }
        assertFalse(logContains("free heap below"), rt.log().snapshot().toString());
    }

    // ---- fix round 1 ----

    @Test
    void sdkFailureDuringTheInitialLoadDoesNotDeferReloadsForever() throws Exception {
        plugin("hello");
        SdkRuntime.testBeforeLoadAll = new Runnable() {
            public void run() {
                throw new IllegalStateException("simulated SDK-side failure in loadAll");
            }
        };
        start("plugin.start.delay.ms=0\nheap.low.bytes=0\n");
        for (int i = 0; i < 500 && !logContains("simulated SDK-side failure in loadAll"); i++) {
            Thread.sleep(10); // logged by the control executor just after the step completes
        }
        assertTrue(logContains("simulated SDK-side failure in loadAll"), rt.log().snapshot().toString());
        SdkRuntime.testBeforeLoadAll = null;

        rt.reload("hello");
        assertFalse(logContains("reload deferred"), rt.log().snapshot().toString());
        assertEquals(PluginState.ENABLED, status("hello").state());
        assertEquals(1, count("hello", "hi"));
    }

    @Test
    void reloadDuringStartDelayIsDeferredAndHarmless() throws Exception {
        plugin("hello");
        plugin("keeper");
        write(new File(persist(), "ledger.properties"), "parking.soundDirections=keeper|PERSISTENT|F1R1L0X0|F1R0L0X0\n");
        start("plugin.start.delay.ms=30000\nheap.low.bytes=0\ndev.poll.ms=2000\n");
        File marker = new File(plugins, "hello.reload"); // dropped after start: markers from before are consumed
        assertTrue(marker.createNewFile());

        clock.advance(2000); // the runtime's dev channel polls during the start delay
        for (int i = 0; i < 500 && !logContains("reload deferred: plugins not loaded yet"); i++) {
            Thread.sleep(10);
        }
        assertTrue(logContains("reload deferred: plugins not loaded yet"), rt.log().snapshot().toString());
        assertTrue(marker.exists(), "never deleted: /mnt/app is read-only at runtime");
        assertTrue(rt.statuses().isEmpty(), "nothing loaded by the deferred reload");

        clock.advance(30000);
        awaitState("hello", PluginState.ENABLED);
        awaitState("keeper", PluginState.ENABLED);
        Thread.sleep(200);
        int hellos = 0;
        for (PluginStatus st : rt.statuses()) {
            hellos += st.id().equals("hello") ? 1 : 0;
        }
        assertEquals(1, hellos, rt.statuses().toString());
        assertEquals(1, count("hello", "hi"), "exactly one instance enabled");
        assertTrue(fake.appliedLog().isEmpty(), "keeper's PERSISTENT override was not restored: " + fake.appliedLog());
        assertEquals(1, rt.activeOverrides().size());
        assertFalse(rt.activeOverrides().get(0).restorePending());
    }

    @Test
    void staleUntilIgnitionOffOverrideRestoredAtStartNotAfterTheDelay() throws Exception {
        plugin("muter");
        write(new File(persist(), "ledger.properties"),
                "parking.soundDirections=muter|UNTIL_IGNITION_OFF|F1R1L0X0|F1R0L0X0\n");
        start("plugin.start.delay.ms=30000\nheap.low.bytes=0\n");
        for (int i = 0; i < 500 && fake.appliedLog().isEmpty(); i++) {
            Thread.sleep(10);
        }
        assertEquals(Arrays.asList(F1R1L0X0), fake.appliedLog(), "prior back without any Clock advance");
        assertTrue(rt.statuses().isEmpty(), "plugins still wait for the delay");
    }

    @Test
    void safeModeBootDoesNotCount() throws Exception {
        File marker = new File(base, "SAFE_MODE");
        write(marker, "");
        for (int i = 0; i < 3; i++) { // three safe-mode boots that all "crash" (stop() marks nothing)
            start("plugin.start.delay.ms=0\nheap.low.bytes=0\n");
            assertTrue(rt.safeMode());
            rt.stop();
        }
        assertEquals("0", bootState().getProperty("unhealthyBoots"));
        assertTrue(marker.delete());
        start("plugin.start.delay.ms=0\nheap.low.bytes=0\n");
        assertFalse(rt.safeMode());
    }

    @Test
    void restartsDuringTheStartDelayDoNotCount() throws Exception {
        plugin("hello");
        for (int i = 0; i < 4; i++) { // e.g. slay -s11 j9 on the bench before plugins load
            start("plugin.start.delay.ms=30000\nheap.low.bytes=0\n");
            assertFalse(rt.safeMode(), "boot " + (i + 1));
            assertEquals("true", bootState().getProperty("lastShutdownClean"));
            rt.stop();
        }
        assertEquals("0", bootState().getProperty("unhealthyBoots"));
    }

    @Test
    void restartsAfterPluginsLoadedCount() throws Exception {
        plugin("hello");
        for (int i = 0; i < 3; i++) {
            start("plugin.start.delay.ms=0\nheap.low.bytes=0\n");
            assertFalse(rt.safeMode(), "boot " + (i + 1));
            assertEquals("false", bootState().getProperty("lastShutdownClean"));
            rt.stop(); // like a crash: no ignition-off, not healthy yet
        }
        start("plugin.start.delay.ms=0\nheap.low.bytes=0\n");
        assertTrue(rt.safeMode());
    }

    /** Review Focus 3 through the runtime: the ignition goes off before the delay ends. */
    @Test
    void ignitionOffDuringStartDelayKeepsTheBootClean() throws Exception {
        plugin("hello");
        for (int i = 0; i < 3; i++) {
            start("plugin.start.delay.ms=30000\nheap.low.bytes=0\n");
            assertFalse(rt.safeMode(), "boot " + (i + 1));
            fake.setIgnition(false);
            clock.advance(30000);
            awaitState("hello", PluginState.ENABLED); // the delay ends before power-off
            assertEquals("true", bootState().getProperty("lastShutdownClean"));
            rt.stop();
        }
        start("plugin.start.delay.ms=30000\nheap.low.bytes=0\n");
        assertFalse(rt.safeMode());
        assertEquals("0", bootState().getProperty("unhealthyBoots"));
    }
}
