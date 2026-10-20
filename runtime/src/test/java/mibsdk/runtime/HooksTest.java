package mibsdk.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import mibsdk.adapter.fake.FakePlatform;
import mibsdk.api.Features;
import mibsdk.api.HookPoint;
import mibsdk.api.SoundDirections;
import mibsdk.platform.HookTable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HooksTest {

    private static final String HOOKS = "hooks.parking";
    private static final SoundDirections V = new SoundDirections(true, false, true, false);

    @TempDir
    File tmp;

    private File base;
    private File plugins;
    private PluginJars jars;
    private final FakeClock clock = new FakeClock();
    private FakePlatform fake;
    private SdkRuntime rt;
    private final StringBuilder grants = new StringBuilder();

    @BeforeEach
    void setUp() {
        base = new File(tmp, "mibsdk");
        plugins = new File(base, "plugins");
        plugins.mkdirs();
        jars = new PluginJars(new File(tmp, "fixtures"));
        SerialExecutor.watchdogPeriodMs = 10;
        fake = platform(Features.IGNITION, Features.PARKING, Features.HOOK_PARKING);
    }

    @AfterEach
    void tearDown() {
        if (rt != null) {
            rt.stop();
        }
        SerialExecutor.watchdogPeriodMs = 1000;
    }

    private static FakePlatform platform(String... features) {
        return new FakePlatform("HMI_TEST", new HashSet<String>(Arrays.asList(features)));
    }

    private void plugin(String id, String[] perms, String[] granted, String body) throws IOException {
        jars.write(plugins, id, "1", null, perms, body, null);
        grants.append(id).append('=');
        for (int i = 0; i < granted.length; i++) {
            grants.append(i > 0 ? "," : "").append(granted[i]);
        }
        grants.append('\n');
    }

    private void start(String sdkProperties) throws IOException {
        write("sdk.properties", "plugin.start.delay.ms=0\nheap.low.bytes=0\nlog.file=" + new File(tmp, "mibsdk.log").getPath() + "\n"
                + (sdkProperties == null ? "" : sdkProperties));
        write("grants.properties", grants.toString());
        rt = new SdkRuntime(RuntimeConfig.load(FakePlatform.roots(tmp)), fake, clock, new HookTable(64), new RingLog(500));
        rt.start();
    }

    private void write(String name, String text) throws IOException {
        FileOutputStream out = new FileOutputStream(new File(base, name));
        try {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } finally {
            out.close();
        }
    }

    private PluginStatus status(String id) {
        List<PluginStatus> all = rt.statuses();
        for (PluginStatus s : all) {
            if (s.id().equals(id)) {
                return s;
            }
        }
        throw new AssertionError("no status for " + id + " in " + all);
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

    private void awaitCount(String source, String msg, int n) throws InterruptedException {
        for (int i = 0; i < 500; i++) {
            if (count(source, msg) >= n) {
                return;
            }
            Thread.sleep(10);
        }
        fail("expected " + n + " x '" + source + " " + msg + "' in\n" + rt.log().snapshot());
    }

    private void awaitState(String id, PluginState state) throws InterruptedException {
        for (int i = 0; i < 500; i++) {
            if (status(id).state() == state) {
                return;
            }
            Thread.sleep(10);
        }
        fail(id + " never reached " + state + ": " + status(id).state() + " " + status(id).reason());
    }

    private static final String[] PERMS = {"vehicle.read", HOOKS};

    @Test
    void vetoAppliesWithoutPluginCodeOnCallerThread() throws Exception {
        plugin("vetoer", PERMS, PERMS,
                "ctx.hooks().observe(HookPoint.PARKING_SOUND_SET, o -> ctx.log().info(\"obs vetoed=\" + o.vetoed()"
                + " + \" onPlugin=\" + Thread.currentThread().getName().startsWith(\"mibsdk-plugin-vetoer\")));\n"
                + "Veto v = ctx.hooks().veto(HookPoint.PARKING_SOUND_SET);\n"
                + "v.setActive(true);\n"
                + "ctx.log().info(\"armed=\" + v.isActive());");
        start(null);
        assertEquals(PluginState.ENABLED, status("vetoer").state(), status("vetoer").reason());
        awaitCount("vetoer", "armed=true", 1);

        assertFalse(fake.simulateHookCall(HookPoint.PARKING_SOUND_SET, V), "vetoed call is not forwarded");
        assertTrue(fake.simulateHookCall(HookPoint.PARKING_SOUND_UPDATE, V), "other points are untouched");

        awaitCount("vetoer", "obs vetoed=true onPlugin=true", 1);
        assertEquals(0, count("vetoer", "obs vetoed=false onPlugin=true"));
    }

    @Test
    void vetoDecisionDoesNotWaitForPluginCode() throws Exception {
        plugin("slowpoke", PERMS, PERMS,
                "ctx.hooks().veto(HookPoint.PARKING_SOUND_SET).setActive(true);\n"
                + "ctx.hooks().observe(HookPoint.PARKING_SOUND_SET, o -> { ctx.log().info(\"in\"); Thread.sleep(1500); });");
        start("callback.hung.ms=60000\n");

        assertFalse(fake.simulateHookCall(HookPoint.PARKING_SOUND_SET, V));
        awaitCount("slowpoke", "in", 1);
        long t0 = System.nanoTime();
        for (int i = 0; i < 20; i++) {
            assertFalse(fake.simulateHookCall(HookPoint.PARKING_SOUND_SET, V));
        }
        assertTrue((System.nanoTime() - t0) / 1000000L < 500, "stock-thread calls must not wait for a busy plugin");
    }

    @Test
    void observeOnlyReceivesItsPoint() throws Exception {
        plugin("watcher", PERMS, PERMS,
                "ctx.hooks().observe(HookPoint.PARKING_SOUND_UPDATE, o -> ctx.log().info(\"upd \" + o.point()));");
        start(null);

        fake.simulateHookCall(HookPoint.PARKING_SOUND_SET, V);
        fake.simulateHookCall(HookPoint.PARKING_SOUND_UPDATE, V);
        awaitCount("watcher", "upd PARKING_SOUND_UPDATE", 1);
        Thread.sleep(50);
        assertEquals(0, count("watcher", "upd PARKING_SOUND_SET"));
    }

    @Test
    void releasedVetoNoLongerApplies() throws Exception {
        plugin("rel", PERMS, PERMS,
                "Veto v = ctx.hooks().veto(HookPoint.PARKING_SOUND_SET);\n"
                + "v.setActive(true);\n"
                + "ctx.scheduler().after(0, () -> {});\n"
                + "ctx.events().on(IgnitionChanged.class, e -> {\n"
                + "  if (e.isOn()) { v.release(); ctx.log().info(\"released active=\" + v.isActive()); v.setActive(true); }\n"
                + "  else { v.setActive(false); ctx.log().info(\"cleared active=\" + v.isActive()); }\n"
                + "});");
        start(null);

        assertFalse(fake.simulateHookCall(HookPoint.PARKING_SOUND_SET, V));
        fake.setIgnition(true);
        awaitCount("rel", "released active=false", 1);
        assertTrue(fake.simulateHookCall(HookPoint.PARKING_SOUND_SET, V), "release lifts the veto");
        Thread.sleep(50);
        assertTrue(fake.simulateHookCall(HookPoint.PARKING_SOUND_SET, V), "setActive after release is a no-op");
    }

    @Test
    void setActiveFalseLiftsVetoWithoutReleasing() throws Exception {
        plugin("toggle", PERMS, PERMS,
                "Veto v = ctx.hooks().veto(HookPoint.PARKING_SOUND_UPDATE);\n"
                + "ctx.events().on(IgnitionChanged.class, e -> { v.setActive(e.isOn()); ctx.log().info(\"on=\" + e.isOn()); });");
        start(null);

        assertTrue(fake.simulateHookCall(HookPoint.PARKING_SOUND_UPDATE, V), "a veto starts inactive");
        fake.setIgnition(true);
        awaitCount("toggle", "on=true", 1);
        assertFalse(fake.simulateHookCall(HookPoint.PARKING_SOUND_UPDATE, V));
        fake.setIgnition(false);
        awaitCount("toggle", "on=false", 1);
        assertTrue(fake.simulateHookCall(HookPoint.PARKING_SOUND_UPDATE, V));
    }

    @Test
    void quarantineReleasesVetos() throws Exception {
        plugin("flaky", PERMS, PERMS,
                "ctx.hooks().veto(HookPoint.PARKING_SOUND_SET).setActive(true);\n"
                + "ctx.hooks().observe(HookPoint.PARKING_SOUND_SET, o -> ctx.log().info(\"saw\"));\n"
                + "ctx.events().on(IgnitionChanged.class, e -> { throw new RuntimeException(\"nope\"); });");
        start("quarantine.failures=2\n");
        assertFalse(fake.simulateHookCall(HookPoint.PARKING_SOUND_SET, V));
        awaitCount("flaky", "saw", 1);

        fake.setIgnition(true);
        fake.setIgnition(false);
        awaitState("flaky", PluginState.QUARANTINED);

        assertTrue(fake.simulateHookCall(HookPoint.PARKING_SOUND_SET, V), "quarantine lifts the plugin's vetos");
        Thread.sleep(50);
        assertEquals(1, count("flaky", "saw"), "and cancels its observers");
    }

    @Test
    void missingPermissionDenied() throws Exception {
        // vehicle.read alone is granted; the hooks and parking-write permissions are not.
        plugin("meek", new String[] {"vehicle.read"}, new String[] {"vehicle.read"},
                "try { ctx.hooks().veto(HookPoint.PARKING_SOUND_SET); ctx.log().info(\"veto allowed\"); }\n"
                + "catch (PermissionDeniedException e) { ctx.log().info(\"veto denied \" + e.permission()); }\n"
                + "try { ctx.hooks().observe(HookPoint.PARKING_SOUND_UPDATE, o -> {}); ctx.log().info(\"observe allowed\"); }\n"
                + "catch (PermissionDeniedException e) { ctx.log().info(\"observe denied \" + e.permission()); }\n"
                + "try { ctx.events().on(HookObserved.class, o -> {}); ctx.log().info(\"events allowed\"); }\n"
                + "catch (PermissionDeniedException e) { ctx.log().info(\"events denied \" + e.permission()); }\n"
                + "ctx.log().info(\"vehicle ok \" + (ctx.vehicle() != null));\n"
                + "ctx.log().info(\"granted read=\" + ctx.granted(Permissions.VEHICLE_READ) + \" hooks=\" + ctx.granted(Permissions.HOOKS_PARKING));");
        plugin("blind", new String[0], new String[0],
                "try { ctx.vehicle(); ctx.log().info(\"vehicle allowed\"); }\n"
                + "catch (PermissionDeniedException e) { ctx.log().info(\"vehicle denied \" + e.permission()); }");
        start(null);

        awaitCount("meek", "veto denied hooks.parking", 1);
        awaitCount("meek", "observe denied hooks.parking", 1);
        awaitCount("meek", "events denied hooks.parking", 1);
        awaitCount("meek", "vehicle ok true", 1);
        awaitCount("meek", "granted read=true hooks=false", 1);
        awaitCount("blind", "vehicle denied vehicle.read", 1);
        assertTrue(fake.simulateHookCall(HookPoint.PARKING_SOUND_SET, V), "a denied veto never registered");
    }

    @Test
    void netNeedsTheNetworkPermissionAtTheContext() throws Exception {
        // A granted but undeclared permission is not usable.
        plugin("offline", new String[] {"vehicle.read"}, new String[] {"vehicle.read", "network"},
                "try { ctx.net(); ctx.log().info(\"net allowed\"); }\n"
                + "catch (PermissionDeniedException e) { ctx.log().info(\"net denied \" + e.permission()); }");
        plugin("online", new String[] {"network"}, new String[] {"network"},
                "ctx.log().info(\"net ok \" + (ctx.net() != null));");
        start(null);

        awaitCount("offline", "net denied network", 1);
        awaitCount("online", "net ok true", 1);
    }

    @Test
    void grantedFlagsReflectGrantsFile() throws Exception {
        // Effective permissions are declared AND granted, so the manifest declares vehicle.parking.write too.
        plugin("full", new String[] {"vehicle.read", HOOKS, "vehicle.parking.write"},
                new String[] {"vehicle.read", HOOKS, "vehicle.parking.write", "platform.unrestricted"},
                "ctx.log().info(\"w=\" + ctx.granted(Permissions.VEHICLE_PARKING_WRITE) + \" u=\""
                + " + ctx.granted(Permissions.PLATFORM_UNRESTRICTED));");
        start(null);

        awaitCount("full", "w=true u=false", 1);
    }

    @Test
    void absentHookFeatureIsUnsupported() throws Exception {
        fake = platform(Features.IGNITION, Features.PARKING);
        plugin("nofeat", PERMS, PERMS,
                "try { ctx.hooks().veto(HookPoint.PARKING_SOUND_SET); ctx.log().info(\"veto ok\"); }\n"
                + "catch (UnsupportedOperationException e) { ctx.log().info(\"veto unsupported\"); }\n"
                + "try { ctx.hooks().observe(HookPoint.PARKING_SOUND_SET, o -> {}); ctx.log().info(\"observe ok\"); }\n"
                + "catch (UnsupportedOperationException e) { ctx.log().info(\"observe unsupported\"); }");
        start(null);

        awaitCount("nofeat", "veto unsupported", 1);
        awaitCount("nofeat", "observe unsupported", 1);
    }

    @Test
    void pluginHoldingVetoMonitorCannotWedgeShutdown() throws Exception {
        plugin("locker", PERMS, PERMS,
                "Veto v = ctx.hooks().veto(HookPoint.PARKING_SOUND_SET);\n"
                + "Subscription s = ctx.hooks().observe(HookPoint.PARKING_SOUND_SET, o -> {});\n"
                + "v.setActive(true);\n"
                + "Thread t = new Thread(() -> { synchronized (v) { synchronized (s) { ctx.log().info(\"locked\");"
                + " try { Thread.sleep(600000L); } catch (InterruptedException e) { } } } });\n"
                + "t.setDaemon(true); t.start();");
        start(null);
        awaitCount("locker", "locked", 1);

        final SdkRuntime r = rt;
        Thread stopper = new Thread() {
            public void run() {
                r.stop();
            }
        };
        stopper.setDaemon(true);
        stopper.start();
        stopper.join(10000);
        assertFalse(stopper.isAlive(), "stop() must not block on monitors the plugin holds");
    }
}
