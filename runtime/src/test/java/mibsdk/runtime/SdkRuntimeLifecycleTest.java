package mibsdk.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mibsdk.adapter.fake.FakePlatform;
import mibsdk.api.Features;
import mibsdk.platform.HookTable;
import mibsdk.platform.ParkingIo;
import mibsdk.platform.PlatformAdapter;
import mibsdk.platform.PlatformHost;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SdkRuntimeLifecycleTest {

    private static final String HMI = "HMI_TEST";
    private static final String LOG_IGNITION =
            "ctx.events().on(IgnitionChanged.class, e -> ctx.log().info(\"ignition=\" + e.isOn()));";

    @TempDir
    File tmp;

    private File base;
    private File plugins;
    private PluginJars jars;
    private final FakeClock clock = new FakeClock();
    private final FakePlatform fake = new FakePlatform(HMI,
            new HashSet<String>(Arrays.asList(Features.IGNITION, Features.PARKING, Features.HOOK_PARKING)));
    /** pluginId → permissions, written to grants.properties (inert until Task 10 enforces grants). */
    private final Map<String, String[]> grants = new LinkedHashMap<String, String[]>();
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
        if (rt != null) {
            stopWithin(15000); // off-thread: a wedged runtime must fail the test, not hang the JVM
        }
        SerialExecutor.watchdogPeriodMs = 1000;
    }

    /** Calls rt.stop() on another thread; true if it returned within {@code ms}. */
    private boolean stopWithin(long ms) throws InterruptedException {
        final SdkRuntime r = rt;
        Thread t = new Thread() {
            public void run() {
                r.stop();
            }
        };
        t.setDaemon(true);
        t.start();
        t.join(ms);
        return !t.isAlive();
    }

    // ---- helpers ----

    private void plugin(String id, String version, String[] deps, String onEnableBody) throws IOException {
        plugin(id, version, deps, onEnableBody, null);
    }

    private void plugin(String id, String version, String[] deps, String onEnableBody, String members)
            throws IOException {
        String[] perms = new String[] {"vehicle.read"};
        jars.write(plugins, id, version, deps, perms, onEnableBody, members);
        grants.put(id, perms);
    }

    private SdkRuntime start(String sdkProperties) throws IOException {
        return start(sdkProperties, fake);
    }

    private SdkRuntime start(String sdkProperties, PlatformAdapter platform) throws IOException {
        writeFile(new File(base, "sdk.properties"), "plugin.start.delay.ms=0\nheap.low.bytes=0\nlog.file=" + new File(tmp, "mibsdk.log").getPath() + "\n"
                + (sdkProperties == null ? "" : sdkProperties));
        StringBuilder g = new StringBuilder();
        for (Map.Entry<String, String[]> e : grants.entrySet()) {
            g.append(e.getKey()).append('=');
            String[] perms = e.getValue();
            for (int i = 0; i < perms.length; i++) {
                g.append(i > 0 ? "," : "").append(perms[i]);
            }
            g.append('\n');
        }
        writeFile(new File(base, "grants.properties"), g.toString());
        rt = new SdkRuntime(RuntimeConfig.load(base), platform, clock, new HookTable(64), new RingLog(500));
        rt.start();
        return rt;
    }

    private static void writeFile(File f, String text) throws IOException {
        f.getParentFile().mkdirs();
        Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8);
        try {
            w.write(text);
        } finally {
            w.close();
        }
    }

    private PluginStatus status(String id) {
        List<PluginStatus> all = rt.statuses();
        for (PluginStatus s : all) {
            if (s.id().equals(id)) {
                return s;
            }
        }
        fail("no status for " + id + " in " + all);
        return null;
    }

    private void awaitState(String id, PluginState state) throws InterruptedException {
        for (int i = 0; i < 500; i++) {
            if (status(id).state() == state) {
                return;
            }
            Thread.sleep(10);
        }
        fail(id + " never reached " + state + ": " + status(id).state() + " " + status(id).reason()
                + "\n" + rt.log().snapshot());
    }

    /** Lines logged by {@code source} whose message ends with {@code msg}. */
    private int count(String source, String msg) {
        int n = 0;
        for (String line : rt.log().snapshot()) {
            if (line.endsWith(" " + source + " " + msg)) {
                n++;
            }
        }
        return n;
    }

    private void awaitCount(String source, String msg, int n) throws InterruptedException {
        for (int i = 0; i < 500; i++) {
            if (count(source, msg) >= n) {
                return;
            }
            Thread.sleep(10);
        }
        fail("expected " + n + " x '" + source + " " + msg + "' in\n" + rt.log().snapshot());
    }

    private int indexOf(String source, String msg) {
        List<String> lines = rt.log().snapshot();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).endsWith(" " + source + " " + msg)) {
                return i;
            }
        }
        return -1;
    }

    // ---- tests ----

    @Test
    void enablesValidPluginAndDeliversEvents() throws Exception {
        plugin("hello", "1.0", null, LOG_IGNITION);
        start(null);

        assertEquals(PluginState.ENABLED, status("hello").state());
        assertEquals("1.0", status("hello").version());
        fake.setIgnition(true);
        awaitCount("hello", "ignition=true", 1);
        assertTrue(indexOf("hello", "DISCOVERED discovered") >= 0, rt.log().snapshot().toString());
        assertTrue(indexOf("hello", "ENABLED enabled") > indexOf("hello", "DISCOVERED discovered"));
        assertTrue(rt.log().snapshot().toString().contains(" STATUS hello ENABLED enabled"));
    }

    @Test
    void throwInOnEnableMarksFailedOthersUnaffected() throws Exception {
        plugin("bad", "1", null, LOG_IGNITION + "\nthrow new IllegalStateException(\"boom\");");
        plugin("good", "1", null, LOG_IGNITION);
        start(null);

        assertEquals(PluginState.FAILED, status("bad").state());
        assertTrue(status("bad").reason().contains("boom"), status("bad").reason());
        assertEquals(PluginState.ENABLED, status("good").state());
        assertEquals(1, count("bad", "disabled"), "immediate disable runs onDisable");

        fake.setIgnition(true);
        awaitCount("good", "ignition=true", 1);
        fake.setIgnition(false);
        awaitCount("good", "ignition=false", 1);
        assertEquals(0, count("bad", "ignition=true"), "the failed plugin's subscription was cancelled");
    }

    @Test
    void repeatedListenerFailuresQuarantine() throws Exception {
        plugin("flaky", "1", null, "ctx.events().on(IgnitionChanged.class, e -> {"
                + " ctx.log().info(\"saw\"); throw new RuntimeException(\"nope\"); });");
        plugin("good", "1", null, LOG_IGNITION);
        start(null);

        fake.setIgnition(true);
        clock.advance(10000);
        fake.setIgnition(false);
        awaitCount("flaky", "saw", 2);
        awaitCount("good", "ignition=false", 1);
        for (int i = 0; i < 200 && countFailures("flaky") < 2; i++) {
            Thread.sleep(10);
        }
        assertEquals(PluginState.ENABLED, status("flaky").state(), "two failures stay below the threshold");

        clock.advance(10000);
        fake.setIgnition(true);
        awaitState("flaky", PluginState.QUARANTINED);
        assertEquals(1, rt.eventSubscriberCount(), "only good's subscription is left");
        assertEquals(1, count("flaky", "disabled"), "quarantine runs onDisable");

        fake.setIgnition(false);
        awaitCount("good", "ignition=false", 2);
        Thread.sleep(50);
        assertEquals(3, count("flaky", "saw"), "subscriptions no longer fire after quarantine");
    }

    private int countFailures(String id) {
        int n = 0;
        for (String line : rt.log().snapshot()) {
            if (line.contains(" ERROR " + id + " ") && line.contains("nope")) {
                n++;
            }
        }
        return n;
    }

    @Test
    void hungCallbackQuarantines() throws Exception {
        plugin("stuck", "1", null, "ctx.events().on(IgnitionChanged.class, e -> {"
                + " ctx.log().info(\"blocking\"); Thread.sleep(600000L); });");
        plugin("good", "1", null, LOG_IGNITION);
        start("callback.hung.ms=1000\n");

        fake.setIgnition(true);
        awaitCount("stuck", "blocking", 1);
        awaitCount("good", "ignition=true", 1);
        Thread.sleep(50); // let good's task finish so only stuck's task is running when the clock jumps
        clock.advance(1001);
        awaitState("stuck", PluginState.QUARANTINED);
        assertTrue(status("stuck").reason().contains("hung"), status("stuck").reason());
        assertEquals(PluginState.ENABLED, status("good").state());

        fake.setIgnition(false);
        awaitCount("good", "ignition=false", 1);
    }

    @Test
    void dependencyOrderAndMissingDependencyBlocked() throws Exception {
        String logEnabled = "ctx.log().info(\"enabled\");";
        plugin("a-child", "1", new String[] {"z-base"}, logEnabled);
        plugin("z-base", "1", null, logEnabled);
        plugin("orphan", "1", new String[] {"nope"}, logEnabled);
        plugin("on-orphan", "1", new String[] {"orphan"}, logEnabled);
        plugin("cyc-a", "1", new String[] {"cyc-b"}, logEnabled);
        plugin("cyc-b", "1", new String[] {"cyc-a"}, logEnabled);
        plugin("on-failed", "1", new String[] {"failing"}, logEnabled);
        plugin("failing", "1", null, "throw new Exception(\"no\");");
        start(null);

        assertEquals(PluginState.ENABLED, status("z-base").state());
        assertEquals(PluginState.ENABLED, status("a-child").state());
        assertTrue(indexOf("z-base", "enabled") < indexOf("a-child", "enabled"), "dependency enabled first");
        assertEquals(PluginState.BLOCKED_DEPENDENCY, status("orphan").state());
        assertTrue(status("orphan").reason().contains("nope"), status("orphan").reason());
        assertEquals(PluginState.BLOCKED_DEPENDENCY, status("on-orphan").state());
        assertEquals(PluginState.BLOCKED_DEPENDENCY, status("cyc-a").state());
        assertEquals(PluginState.BLOCKED_DEPENDENCY, status("cyc-b").state());
        assertEquals(PluginState.FAILED, status("failing").state());
        assertEquals(PluginState.BLOCKED_DEPENDENCY, status("on-failed").state());
        assertEquals(-1, indexOf("orphan", "enabled"));
        assertEquals(-1, indexOf("cyc-a", "enabled"));
    }

    @Test
    void invalidManifestAndMisnamedJarBlockedInvalid() throws Exception {
        jars.writeRaw(new File(plugins, "broken.jar"), "package fx; public class Main {}", "{\"id\":\"broken\"}");
        jars.write(plugins, "real-id", "1", null, null, "", null);
        new File(plugins, "real-id.jar").renameTo(new File(plugins, "other-name.jar"));
        start(null);

        assertEquals(PluginState.BLOCKED_INVALID, status("broken").state());
        assertEquals(PluginState.BLOCKED_INVALID, status("other-name").state());
    }

    @Test
    void reloadSwapsCodeAndCancelsOldRegistrations() throws Exception {
        String body = "ctx.events().on(IgnitionChanged.class, e -> ctx.log().info(\"%s\"));"
                + "ctx.scheduler().every(1000, () -> ctx.log().info(\"%s tick\"));";
        plugin("swap", "1", null, body.replace("%s", "v1"));
        start(null);
        assertEquals("1", status("swap").version());

        plugin("swap", "2", null, body.replace("%s", "v2"));
        rt.reload("swap");
        assertEquals(PluginState.ENABLED, status("swap").state());
        assertEquals("2", status("swap").version());
        assertEquals(1, count("swap", "disabled"), "v1 onDisable ran");

        fake.setIgnition(true);
        awaitCount("swap", "v2", 1);
        clock.advance(1000);
        awaitCount("swap", "v2 tick", 1);
        Thread.sleep(100);
        assertEquals(1, count("swap", "v2"), "handled once");
        assertEquals(0, count("swap", "v1"), "old subscription cancelled");
        assertEquals(0, count("swap", "v1 tick"), "old timer cancelled");
        assertEquals(1, new File(base, "cache").listFiles().length, "old snapshot purged");
    }

    @Test
    void disableRunsOnDisableAndCancelsAll() throws Exception {
        plugin("first", "1", null, LOG_IGNITION + "ctx.storage().put(\"k\", \"v\");"
                + "ctx.scheduler().every(1000, () -> ctx.log().info(\"tick\"));");
        plugin("second", "1", new String[] {"first"}, LOG_IGNITION);
        start(null);

        assertEquals(2, rt.eventSubscriberCount());
        rt.disable("first", "test");
        assertEquals(1, rt.eventSubscriberCount(), "disable cancelled the plugin's subscriptions");
        assertEquals(PluginState.DISABLED, status("first").state());
        assertEquals("test", status("first").reason());
        assertEquals(1, count("first", "disabled"));
        assertTrue(new File(base, "data/first.properties").isFile(), "storage flushed on disable");

        fake.setIgnition(true);
        clock.advance(1000);
        awaitCount("second", "ignition=true", 1);
        Thread.sleep(150);
        assertEquals(0, count("first", "ignition=true"), "subscription cancelled");
        assertEquals(0, count("first", "tick"), "timer cancelled");

        rt.stop();
        assertEquals(PluginState.DISABLED, status("second").state());
        assertEquals(1, count("second", "disabled"));
        assertEquals(1, count("first", "disabled"), "already-disabled plugin not disabled twice");
    }

    @Test
    void stopDisablesInReverseEnableOrder() throws Exception {
        plugin("a-child", "1", new String[] {"z-base"}, "");
        plugin("z-base", "1", null, "");
        start(null);

        rt.stop();
        assertTrue(indexOf("a-child", "disabled") < indexOf("z-base", "disabled"), rt.log().snapshot().toString());
        assertEquals(PluginState.DISABLED, status("z-base").state());
    }

    @Test
    void pluginHoldingSdkObjectMonitorsCannotWedgeRuntime() throws Exception {
        plugin("lock-storage", "1", null, "ctx.events().on(IgnitionChanged.class, e -> {"
                + " synchronized (ctx.storage()) { ctx.log().info(\"locked\"); Thread.sleep(600000L); } });");
        plugin("lock-sub", "1", null, "final Subscription[] s = new Subscription[1];"
                + " s[0] = ctx.events().on(IgnitionChanged.class, e -> {"
                + " synchronized (s[0]) { ctx.log().info(\"locked\"); Thread.sleep(600000L); } });");
        plugin("good", "1", null, LOG_IGNITION);
        start("callback.hung.ms=1000\n");

        fake.setIgnition(true);
        awaitCount("lock-storage", "locked", 1);
        awaitCount("lock-sub", "locked", 1);
        awaitCount("good", "ignition=true", 1);
        Thread.sleep(50);
        clock.advance(1001);

        awaitState("lock-storage", PluginState.QUARANTINED);
        awaitState("lock-sub", PluginState.QUARANTINED);
        assertEquals(PluginState.ENABLED, status("good").state());
        rt.disable("good", "test");
        assertEquals(PluginState.DISABLED, status("good").state());
        rt.reload("good");
        assertEquals(PluginState.ENABLED, status("good").state());
        assertTrue(stopWithin(5000), "stop() returned");
        assertEquals(PluginState.DISABLED, status("good").state());
    }

    @Test
    void hungOnDisableIsQuarantined() throws Exception {
        jars.write(plugins, "slow-off", "1", null, null, "", null, "Thread.sleep(600000L);");
        start("callback.hung.ms=1000\n");

        Thread disabler = new Thread() {
            public void run() {
                rt.disable("slow-off", "test");
            }
        };
        disabler.setDaemon(true);
        disabler.start();
        awaitCount("slow-off", "disabled", 1);
        Thread.sleep(50);
        clock.advance(1001);
        disabler.join(5000);
        assertTrue(!disabler.isAlive(), "disable returned once the watchdog flagged onDisable");
        assertEquals(PluginState.QUARANTINED, status("slow-off").state());
        assertEquals("hung in onDisable", status("slow-off").reason());
    }

    @Test
    void sdkSideFailureWhileEnablingFailsOnlyThatPlugin() throws Exception {
        plugin("boom", "1", null, LOG_IGNITION);
        jars.writeRaw(new File(plugins, "no-main.jar"), "package fx; class Other {}",
                "{\"id\":\"no-main\",\"api\":1,\"main\":\"fx.Main\"}");
        plugin("good", "1", null, LOG_IGNITION);
        SdkRuntime.testEnableHook = new SdkRuntime.EnableHook() {
            public void afterResources(String pluginId) {
                if (pluginId.equals("boom")) {
                    throw new OutOfMemoryError("test: cannot create thread");
                }
            }
        };
        try {
            start(null);
        } finally {
            SdkRuntime.testEnableHook = null;
        }

        assertEquals(PluginState.FAILED, status("boom").state());
        assertTrue(status("boom").reason().contains("OutOfMemoryError"), status("boom").reason());
        assertEquals(PluginState.FAILED, status("no-main").state());
        assertEquals(PluginState.ENABLED, status("good").state());
        assertEquals(1, new File(base, "cache").listFiles().length, "failed plugins' snapshots cleaned up");
        assertEquals(1, rt.eventSubscriberCount());
        fake.setIgnition(true);
        awaitCount("good", "ignition=true", 1);
    }

    @Test
    void adapterStartFailureLoadsNothingEvenOnReload() throws Exception {
        plugin("hello", "1", null, LOG_IGNITION);
        PlatformAdapter broken = new PlatformAdapter() {
            public String id() {
                return "broken";
            }

            public boolean supports(String hmiVersion) {
                return true;
            }

            public Set<String> features() {
                return new HashSet<String>();
            }

            public void start(PlatformHost host) throws Exception {
                throw new IllegalStateException("no DSI");
            }

            public void stop() {
            }

            public ParkingIo parking() {
                return null;
            }
        };
        start(null, broken);
        assertTrue(rt.statuses().isEmpty());

        rt.reload("hello");
        assertTrue(rt.statuses().isEmpty(), "reload must not load plugins without an adapter");
        assertTrue(rt.log().snapshot().toString().contains("reload ignored"), rt.log().snapshot().toString());
    }

    @Test
    void stopReleasesCallerWhenStepOutlivesBound() throws Exception {
        // Clock frozen, so the watchdog never fires: each hanging onDisable costs its ~2 x hungMs real-time
        // backstop, and two of them outlast the caller's ~2 x hungMs bound.
        jars.write(plugins, "hang-a", "1", null, null, "", null, "Thread.sleep(600000L);");
        jars.write(plugins, "hang-b", "1", null, null, "", null, "Thread.sleep(600000L);");
        start("callback.hung.ms=200\n");

        assertTrue(stopWithin(3000), "stop() released its caller");
        assertTrue(rt.log().snapshot().toString().contains("runtime step stop still running"),
                rt.log().snapshot().toString());
    }
}
