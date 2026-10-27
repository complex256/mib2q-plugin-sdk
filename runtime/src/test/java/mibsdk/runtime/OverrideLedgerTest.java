package mibsdk.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import mibsdk.adapter.fake.FakePlatform;
import mibsdk.api.Features;
import mibsdk.api.OverrideConflictException;
import mibsdk.api.OverrideScope;
import mibsdk.api.SoundDirections;
import mibsdk.api.VehicleOverride;
import mibsdk.platform.HookTable;
import mibsdk.platform.ParkingIo;
import mibsdk.platform.PlatformAdapter;
import mibsdk.platform.PlatformHost;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OverrideLedgerTest {

    private static final String KEY = "parking.soundDirections";
    private static final SoundDirections F1R1L0X0 = new SoundDirections(true, true, false, false);
    private static final SoundDirections F1R0L0X0 = new SoundDirections(true, false, false, false);
    private static final SoundDirections F1R1L1X1 = new SoundDirections(true, true, true, true);
    private static final SoundDirections F1R0L1X1 = new SoundDirections(true, false, true, true);
    private static final String[] READ_WRITE = {"vehicle.read", "vehicle.parking.write"};

    /**
     * On every IgnitionChanged(true): the first acquires rear=false (scope from storage key "scope"), the
     * next releases it. A conflict is logged.
     */
    private static final String TOGGLE_MEMBERS = "VehicleOverride ov;";
    private static final String TOGGLE_BODY =
            "final OverrideScope scope = OverrideScope.valueOf(ctx.storage().get(\"scope\", \"UNTIL_IGNITION_OFF\"));\n"
            + "ctx.events().on(IgnitionChanged.class, e -> {\n"
            + "  if (!e.isOn()) return;\n"
            + "  if (ov == null) {\n"
            + "    try {\n"
            + "      ov = ctx.vehicle().parking().overrideSoundDirections(\n"
            + "          ctx.vehicle().parking().soundDirections().withRear(false), scope);\n"
            + "      ctx.log().info(\"override active=\" + ov.isActive());\n"
            + "    } catch (OverrideConflictException x) { ctx.log().info(\"conflict \" + x.getMessage()); }\n"
            + "  } else { ov.release(); ctx.log().info(\"released active=\" + ov.isActive()); ov = null; }\n"
            + "});";

    @TempDir
    File tmp;

    private File base;
    private File plugins;
    private PluginJars jars;
    private final FakeClock clock = new FakeClock();
    private FakePlatform fake;
    private SdkRuntime rt;
    private final StringBuilder grants = new StringBuilder();
    private final List<OverrideLedger> ledgers = new ArrayList<OverrideLedger>();

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
    void tearDown() throws InterruptedException {
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
        for (OverrideLedger l : ledgers) {
            l.shutdown();
        }
        SerialExecutor.watchdogPeriodMs = 1000;
    }

    // ---- helpers ----

    private static FakePlatform platform(String... features) {
        return new FakePlatform("HMI_TEST", new HashSet<String>(Arrays.asList(features)));
    }

    private void plugin(String id, String[] perms, String[] granted, String body, String members) throws IOException {
        jars.write(plugins, id, "1", null, perms, body, members);
        grants.append(id).append('=');
        for (int i = 0; i < granted.length; i++) {
            grants.append(i > 0 ? "," : "").append(granted[i]);
        }
        grants.append('\n');
    }

    private void togglePlugin(String id, String scope) throws IOException {
        plugin(id, READ_WRITE, READ_WRITE, TOGGLE_BODY, TOGGLE_MEMBERS);
        if (scope != null) {
            write(new File(FakePlatform.roots(tmp).persist(), "data/" + id + ".properties"), "scope=" + scope + "\n");
        }
    }

    private void start(String sdkProperties) throws IOException {
        start(sdkProperties, fake);
    }

    private void start(String sdkProperties, PlatformAdapter platform) throws IOException {
        write(new File(base, "sdk.properties"), "plugin.start.delay.ms=0\nheap.low.bytes=0\nlog.file=" + new File(tmp, "mibsdk.log").getPath() + "\n"
                + (sdkProperties == null ? "" : sdkProperties));
        write(new File(base, "grants.properties"), grants.toString());
        rt = new SdkRuntime(RuntimeConfig.load(FakePlatform.roots(tmp)), platform, clock, new HookTable(64), new RingLog(500));
        rt.start();
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

    private File ledgerFile() {
        return new File(FakePlatform.roots(tmp).persist(), "ledger.properties");
    }

    private static Properties readProps(File f) throws IOException {
        Properties p = new Properties();
        FileInputStream in = new FileInputStream(f);
        try {
            p.load(in);
        } finally {
            in.close();
        }
        return p;
    }

    private static String text(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.ISO_8859_1);
    }

    private PluginStatus status(String id) {
        for (PluginStatus s : rt.statuses()) {
            if (s.id().equals(id)) {
                return s;
            }
        }
        throw new AssertionError("no status for " + id + " in " + rt.statuses());
    }

    private int count(RingLog log, String source, String msg) {
        int n = 0;
        for (String line : log.snapshot()) {
            if (line.endsWith(" " + source + " " + msg)) {
                n++;
            }
        }
        return n;
    }

    private void awaitCount(String source, String msg, int n) throws InterruptedException {
        for (int i = 0; i < 500; i++) {
            if (count(rt.log(), source, msg) >= n) {
                return;
            }
            Thread.sleep(10);
        }
        fail("expected " + n + " x '" + source + " " + msg + "' in\n" + rt.log().snapshot());
    }

    private boolean logContains(RingLog log, String fragment) {
        for (String line : log.snapshot()) {
            if (line.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private void awaitLog(RingLog log, String fragment) throws InterruptedException {
        for (int i = 0; i < 500; i++) {
            if (logContains(log, fragment)) {
                return;
            }
            Thread.sleep(10);
        }
        fail("expected '" + fragment + "' in\n" + log.snapshot());
    }

    private void awaitLogHas(RingLog log, String level, String fragment) throws InterruptedException {
        for (int i = 0; i < 500; i++) {
            if (logHas(log, level, fragment)) {
                return;
            }
            Thread.sleep(10);
        }
        fail("expected " + level + " '" + fragment + "' in\n" + log.snapshot());
    }

    private void awaitApplied(int size, SoundDirections last) throws InterruptedException {
        List<SoundDirections> log = null;
        for (int i = 0; i < 500; i++) {
            log = fake.appliedLog();
            if (log.size() >= size) {
                break;
            }
            Thread.sleep(10);
        }
        assertEquals(size, log.size(), "applied " + log + "\n" + (rt == null ? "" : rt.log().snapshot().toString()));
        assertEquals(last, log.get(size - 1));
    }

    private void awaitNoOverrides() throws InterruptedException {
        for (int i = 0; i < 500 && !rt.activeOverrides().isEmpty(); i++) {
            Thread.sleep(10);
        }
        assertTrue(rt.activeOverrides().isEmpty(), rt.activeOverrides().toString());
    }

    private void awaitLedgerEmpty() throws Exception {
        for (int i = 0; i < 500; i++) {
            if (ledgerFile().isFile() && readProps(ledgerFile()).isEmpty()) {
                return;
            }
            Thread.sleep(10);
        }
        fail("ledger file still holds entries: " + (ledgerFile().isFile() ? text(ledgerFile()) : "(missing)"));
    }

    private static void awaitActiveSize(OverrideLedger l, int n) throws InterruptedException {
        for (int i = 0; i < 500 && l.active().size() != n; i++) {
            Thread.sleep(10);
        }
        assertEquals(n, l.active().size(), l.active().toString());
    }

    private static void awaitAttempts(RecordingApplier a, int n) throws InterruptedException {
        for (int i = 0; i < 500 && a.threads.size() < n; i++) {
            Thread.sleep(10);
        }
        assertEquals(n, a.threads.size(), "apply attempts");
    }

    private static void awaitFirst(List<String> calls, String value) throws InterruptedException {
        for (int i = 0; i < 500 && !calls.contains(value); i++) {
            Thread.sleep(10);
        }
        assertTrue(calls.contains(value), calls.toString());
    }

    /**
     * Records every apply; optionally fails the first {@code failures} calls. With {@code reportTo} set it
     * models an ECU that reports: a successful apply reports its value to that ledger before returning.
     */
    private static final class RecordingApplier implements OverrideLedger.Applier {
        final List<String> calls = Collections.synchronizedList(new ArrayList<String>());
        final List<String> threads = Collections.synchronizedList(new ArrayList<String>());
        volatile int failures;
        volatile OverrideLedger reportTo;

        @Override
        public void apply(String key, String encodedValue) throws Exception {
            threads.add(Thread.currentThread().getName());
            if (failures > 0) {
                failures--;
                throw new IllegalStateException("ECU busy");
            }
            calls.add(key + "=" + encodedValue);
            OverrideLedger l = reportTo;
            if (l != null) {
                l.onObserved(key, encodedValue);
            }
        }

        void await(int n) throws InterruptedException {
            for (int i = 0; i < 500 && calls.size() < n; i++) {
                Thread.sleep(10);
            }
            assertEquals(n, calls.size(), calls.toString());
        }
    }

    /** An applier whose ECU reports every accepted write to {@code l}. */
    private static RecordingApplier reporting(OverrideLedger l) {
        RecordingApplier a = new RecordingApplier();
        a.reportTo = l;
        return a;
    }

    private OverrideLedger ledger(RingLog log) {
        OverrideLedger l = new OverrideLedger(ledgerFile(), clock, log);
        ledgers.add(l);
        return l;
    }

    // ---- brief: runtime + FakePlatform via ParkingImpl ----

    @Test
    void overrideAppliesAndReleaseRestoresPrior() throws Exception {
        togglePlugin("muter", null);
        start(null);
        fake.ecuSetSoundDirections(F1R1L0X0);

        fake.setIgnition(true);
        awaitCount("muter", "override active=true", 1);
        awaitApplied(1, F1R0L0X0);
        assertEquals("parking.soundDirections=muter|UNTIL_IGNITION_OFF|F1R1L0X0|F1R0L0X0|A\n", text(ledgerFile()));
        List<ActiveOverride> active = rt.activeOverrides();
        assertEquals(1, active.size());
        assertEquals(KEY, active.get(0).key());
        assertEquals("muter", active.get(0).pluginId());
        assertEquals(OverrideScope.UNTIL_IGNITION_OFF, active.get(0).scope());
        assertEquals("F1R0L0X0", active.get(0).activeValue());

        fake.setIgnition(true); // second press releases
        awaitCount("muter", "released active=false", 1);
        awaitApplied(2, F1R1L0X0);
        awaitLedgerEmpty();
        awaitNoOverrides();
    }

    @Test
    void ignitionOffRestoresUntilIgnitionOffScopeOnly() throws Exception {
        // Ledger level: two keys, one per scope.
        RingLog log = new RingLog(100);
        OverrideLedger l = ledger(log);
        RecordingApplier a = reporting(l);
        VehicleOverride temp = l.acquire("k.temp", "p", OverrideScope.UNTIL_IGNITION_OFF, "A0", "A1", a);
        VehicleOverride keep = l.acquire("k.keep", "p", OverrideScope.PERSISTENT, "B0", "B1", a);
        a.await(2);

        l.releaseScope(OverrideScope.UNTIL_IGNITION_OFF);
        a.await(3);
        assertEquals("k.temp=A0", a.calls.get(2));
        assertFalse(temp.isActive());
        assertTrue(keep.isActive());
        awaitActiveSize(l, 1); // k.temp leaves once its restore has completed
        assertEquals("k.keep", l.active().get(0).key());
        l.shutdown();
        assertTrue(ledgerFile().delete()); // the runtime half starts from a clean ledger

        // Runtime level: IgnitionChanged(false) restores an UNTIL_IGNITION_OFF parking override, not a PERSISTENT one.
        togglePlugin("temp", "UNTIL_IGNITION_OFF");
        start(null);
        fake.ecuSetSoundDirections(F1R1L0X0);
        fake.setIgnition(true);
        awaitApplied(1, F1R0L0X0);
        fake.setIgnition(false);
        awaitApplied(2, F1R1L0X0);
        awaitNoOverrides();
        awaitLedgerEmpty();
    }

    @Test
    void persistentOverrideSurvivesIgnitionOff() throws Exception {
        togglePlugin("keeper", "PERSISTENT");
        start(null);
        fake.ecuSetSoundDirections(F1R1L0X0);
        fake.setIgnition(true);
        awaitApplied(1, F1R0L0X0);

        fake.setIgnition(false);
        Thread.sleep(200);
        assertEquals(1, fake.appliedLog().size(), "a persistent override is not restored on ignition off");
        assertEquals(1, rt.activeOverrides().size());
        assertEquals(OverrideScope.PERSISTENT, rt.activeOverrides().get(0).scope());
    }

    @Test
    void stickyReapplyWhenEcuChangesBack() throws Exception {
        togglePlugin("muter", null);
        start(null);
        fake.ecuSetSoundDirections(F1R1L0X0);
        fake.setIgnition(true);
        awaitApplied(1, F1R0L0X0);

        fake.ecuSetSoundDirections(F1R1L0X0); // the car flips it back
        clock.advance(500);
        Thread.sleep(300);
        assertEquals(1, fake.appliedLog().size(), "no re-apply before the 1000 ms debounce");
        clock.advance(500);
        awaitApplied(2, F1R0L0X0);
        assertEquals(F1R0L0X0, fake.ecuSoundDirections());

        fake.ecuSetSoundDirections(F1R0L0X0); // a report of our own value changes nothing
        clock.advance(2000);
        Thread.sleep(300);
        assertEquals(2, fake.appliedLog().size());
    }

    @Test
    void stickyDebounceRestartsOnEveryChange() throws Exception {
        togglePlugin("muter", null);
        start(null);
        fake.ecuSetSoundDirections(F1R1L0X0);
        fake.setIgnition(true);
        awaitApplied(1, F1R0L0X0);

        fake.ecuSetSoundDirections(F1R1L0X0);
        clock.advance(800);
        fake.ecuSetSoundDirections(F1R1L1X1); // still flapping
        clock.advance(800);
        Thread.sleep(300);
        assertEquals(1, fake.appliedLog().size(), "debounce counts from the last change");
        clock.advance(200);
        awaitApplied(2, F1R0L0X0);
    }

    @Test
    void stickyGivesUpAfterFiveReappliesPerMinute() throws Exception {
        togglePlugin("muter", null);
        start(null);
        fake.ecuSetSoundDirections(F1R1L0X0);
        fake.setIgnition(true);
        awaitApplied(1, F1R0L0X0);

        for (int i = 1; i <= 5; i++) {
            fake.ecuSetSoundDirections(F1R1L0X0);
            clock.advance(1000);
            awaitApplied(1 + i, F1R0L0X0);
        }
        fake.ecuSetSoundDirections(F1R1L0X0);
        clock.advance(1000);
        awaitLog(rt.log(), "giving up");
        Thread.sleep(200);
        assertEquals(6, fake.appliedLog().size(), "the sixth re-apply within 60 s is not attempted");
        clock.advance(120000);
        fake.ecuSetSoundDirections(F1R1L0X0);
        clock.advance(1000);
        Thread.sleep(300);
        assertEquals(6, fake.appliedLog().size(), "once given up, the override is no longer sticky");
        assertEquals(1, rt.activeOverrides().size(), "giving up does not drop the restore obligation");
    }

    @Test
    void stickyBudgetIsPerSixtySecondWindow() throws Exception {
        RingLog log = new RingLog(100);
        OverrideLedger l = ledger(log);
        RecordingApplier a = reporting(l);
        l.acquire(KEY, "p", OverrideScope.PERSISTENT, "F1R1L0X0", "F1R0L0X0", a);
        a.await(1);
        for (int i = 1; i <= 7; i++) {
            l.onObserved(KEY, "F1R1L0X0");
            clock.advance(15000); // 15 s apart: never 5 within any 60 s window... until the window fills
            a.await(1 + i);
        }
        assertFalse(logContains(log, "giving up"), log.snapshot().toString());
    }

    @Test
    void pendingEntriesRestoredOnBoot() throws Exception {
        write(ledgerFile(), "parking.soundDirections=muter|PERSISTENT|F1R1L0X0|F1R0L0X0\n"
                + "other.key=gone|UNTIL_IGNITION_OFF|V0|V1\n");
        OverrideLedger l = ledger(new RingLog(100));
        assertEquals(2, l.active().size());
        RecordingApplier a = reporting(l);

        l.restorePendingOnBoot(a);
        a.await(2);
        assertTrue(a.calls.contains("parking.soundDirections=F1R1L0X0"), a.calls.toString());
        assertTrue(a.calls.contains("other.key=V0"), a.calls.toString());
        awaitLedgerEmpty();
        assertTrue(l.active().isEmpty());
        assertEquals(0, ledgerFile().length(), "an empty ledger is an empty file (no date comment)");
    }

    @Test
    void uninstalledPluginEntryRestored() throws Exception {
        write(ledgerFile(), "parking.soundDirections=gone|PERSISTENT|F1R1L1X1|F1R0L1X1\n");
        togglePlugin("present", null); // unrelated plugin, no override
        start(null);

        awaitApplied(1, F1R1L1X1);
        awaitLedgerEmpty();
        awaitNoOverrides();
        awaitLog(rt.log(), "gone");
    }

    @Test
    void revokedPluginEntryRestoredAtBoot() throws Exception {
        write(ledgerFile(), "parking.soundDirections=revoked|PERSISTENT|F1R1L1X1|F1R0L1X1\n");
        jars.write(plugins, "revoked", "1", null, READ_WRITE, "", null); // no grants line: BLOCKED_PERMISSIONS
        start(null);

        assertEquals(PluginState.BLOCKED_PERMISSIONS, status("revoked").state());
        awaitApplied(1, F1R1L1X1);
        awaitLedgerEmpty();
        awaitNoOverrides();
    }

    @Test
    void untilIgnitionOffEntryLeftByCrashRestoredAtBoot() throws Exception {
        togglePlugin("muter", null);
        write(ledgerFile(), "parking.soundDirections=muter|UNTIL_IGNITION_OFF|F1R1L1X1|F1R0L1X1\n");
        start(null);

        assertEquals(PluginState.ENABLED, status("muter").state());
        awaitApplied(1, F1R1L1X1);
        awaitLedgerEmpty();
    }

    @Test
    void persistentEntryReadoptedKeepsOriginalPrior() throws Exception {
        write(ledgerFile(), "parking.soundDirections=keeper|PERSISTENT|F1R1L0X0|F1R0L0X0\n");
        togglePlugin("keeper", "PERSISTENT");
        start(null);
        assertEquals(1, rt.activeOverrides().size(), "kept for its still-installed, enabled plugin");
        fake.ecuSetSoundDirections(F1R0L0X0); // the ECU still holds the override from the last boot

        fake.setIgnition(true); // re-acquire: current() is the overridden value, must not become the prior
        awaitCount("keeper", "override active=true", 1);
        awaitApplied(1, F1R0L0X0);
        assertEquals("parking.soundDirections=keeper|PERSISTENT|F1R1L0X0|F1R0L0X0|A\n", text(ledgerFile()));

        fake.setIgnition(true); // release
        awaitApplied(2, F1R1L0X0);
        awaitLedgerEmpty();
    }

    @Test
    void conflictingOverrideRejected() throws Exception {
        togglePlugin("alpha", null);
        togglePlugin("beta", null);
        start(null);
        fake.ecuSetSoundDirections(F1R1L0X0);

        fake.setIgnition(true);
        for (int i = 0; i < 500 && count(rt.log(), "alpha", "override active=true")
                + count(rt.log(), "beta", "override active=true") < 1; i++) {
            Thread.sleep(10);
        }
        String holder = count(rt.log(), "alpha", "override active=true") == 1 ? "alpha" : "beta";
        String loser = holder.equals("alpha") ? "beta" : "alpha";
        awaitCount(loser, "conflict override parking.soundDirections is held by " + holder, 1);
        assertEquals(0, count(rt.log(), loser, "override active=true"));
        assertEquals(holder, rt.activeOverrides().get(0).pluginId());

        // Ledger level, with the holder id in the exception.
        OverrideLedger l = ledger(new RingLog(10));
        RecordingApplier a = reporting(l);
        l.acquire("k", "one", OverrideScope.PERSISTENT, "0", "1", a);
        OverrideConflictException e = assertThrows(OverrideConflictException.class,
                () -> l.acquire("k", "two", OverrideScope.PERSISTENT, "0", "2", a));
        assertTrue(e.getMessage().contains("one"), e.getMessage());
    }

    // ---- carried obligations ----

    @Test
    void disablingPluginRestores() throws Exception {
        togglePlugin("muter", null);
        start(null);
        fake.ecuSetSoundDirections(F1R1L0X0);
        fake.setIgnition(true);
        awaitApplied(1, F1R0L0X0);

        rt.disable("muter", "test");
        assertEquals(PluginState.DISABLED, status("muter").state());
        awaitApplied(2, F1R1L0X0);
        awaitLedgerEmpty();
    }

    @Test
    void quarantineRestoresPersistentOverride() throws Exception {
        plugin("flaky", READ_WRITE, READ_WRITE, TOGGLE_BODY
                + "\nctx.events().on(IgnitionChanged.class, e -> { if (!e.isOn()) throw new RuntimeException(\"x\"); });",
                TOGGLE_MEMBERS);
        write(new File(FakePlatform.roots(tmp).persist(), "data/flaky.properties"), "scope=PERSISTENT\n");
        start("quarantine.failures=1\n");
        fake.ecuSetSoundDirections(F1R1L0X0);
        fake.setIgnition(true);
        awaitApplied(1, F1R0L0X0);

        fake.setIgnition(false); // PERSISTENT: not restored by ignition off, but the plugin throws → quarantined
        for (int i = 0; i < 500 && status("flaky").state() != PluginState.QUARANTINED; i++) {
            Thread.sleep(10);
        }
        assertEquals(PluginState.QUARANTINED, status("flaky").state());
        awaitApplied(2, F1R1L0X0);
        awaitNoOverrides();
    }

    @Test
    void parkingWriteRequiresPermission() throws Exception {
        String body = "ctx.log().info(\"read \" + ctx.vehicle().parking().soundDirections());\n"
                + "try { ctx.vehicle().parking().overrideSoundDirections(new SoundDirections(true, false, false, false),"
                + " OverrideScope.UNTIL_IGNITION_OFF); ctx.log().info(\"write allowed\"); }\n"
                + "catch (PermissionDeniedException e) { ctx.log().info(\"write denied \" + e.permission()); }";
        plugin("reader", new String[] {"vehicle.read"}, new String[] {"vehicle.read"}, body, null);
        start(null);

        awaitCount("reader", "read " + F1R1L1X1, 1);
        awaitCount("reader", "write denied vehicle.parking.write", 1);
        assertTrue(fake.appliedLog().isEmpty());
        assertTrue(rt.activeOverrides().isEmpty());
    }

    @Test
    void grantedButUndeclaredPermissionIsNotUsable() throws Exception {
        String body = "ctx.log().info(\"granted write=\" + ctx.granted(Permissions.VEHICLE_PARKING_WRITE)"
                + " + \" read=\" + ctx.granted(Permissions.VEHICLE_READ));\n"
                + "try { ctx.vehicle().parking().overrideSoundDirections(new SoundDirections(true, false, false, false),"
                + " OverrideScope.UNTIL_IGNITION_OFF); ctx.log().info(\"write allowed\"); }\n"
                + "catch (PermissionDeniedException e) { ctx.log().info(\"write denied \" + e.permission()); }";
        // The manifest declares only vehicle.read; the owner's grants file also lists vehicle.parking.write.
        plugin("sneaky", new String[] {"vehicle.read"}, READ_WRITE, body, null);
        start(null);

        assertEquals(PluginState.ENABLED, status("sneaky").state());
        awaitCount("sneaky", "granted write=false read=true", 1);
        awaitCount("sneaky", "write denied vehicle.parking.write", 1);
        assertTrue(fake.appliedLog().isEmpty());
    }

    @Test
    void missingParkingFeatureIsUnsupported() throws Exception {
        fake = platform(Features.IGNITION);
        plugin("nopark", READ_WRITE, READ_WRITE,
                "try { ctx.vehicle().parking(); ctx.log().info(\"parking ok\"); }\n"
                + "catch (UnsupportedOperationException e) { ctx.log().info(\"parking unsupported\"); }", null);
        start(null);

        awaitCount("nopark", "parking unsupported", 1);
    }

    @Test
    void parkingLearnedAfterTheLoadBecomesAvailable() throws Exception {
        fake = platform(Features.IGNITION);
        plugin("latepark", READ_WRITE, READ_WRITE,
                "ctx.events().on(IgnitionChanged.class, e -> {\n"
                + "  try { ctx.vehicle().parking(); ctx.log().info(\"parking ok\"); }\n"
                + "  catch (UnsupportedOperationException x) { ctx.log().info(\"parking unsupported\"); }\n"
                + "});", null);
        start(null);
        fake.setIgnition(true);
        awaitCount("latepark", "parking unsupported", 1);

        fake.setFeatures(new HashSet<String>(Arrays.asList(Features.IGNITION, Features.PARKING)));
        awaitLog(rt.log(), "STATUS mibsdk features=[vehicle.ignition, vehicle.parking]");
        fake.setIgnition(false);
        awaitCount("latepark", "parking ok", 1);
    }

    @Test
    void applyRunsOnLedgerThreadAndFailureIsContained() throws Exception {
        final List<String> threads = Collections.synchronizedList(new ArrayList<String>());
        final int[] failNext = {1};
        PlatformAdapter flaky = new DelegatingAdapter(fake) {
            @Override
            void beforeApply(SoundDirections v) throws Exception {
                threads.add(Thread.currentThread().getName());
                synchronized (failNext) {
                    if (failNext[0] > 0) {
                        failNext[0]--;
                        throw new IllegalStateException("DSI not ready");
                    }
                }
            }
        };
        togglePlugin("muter", null);
        start(null, flaky);
        fake.ecuSetSoundDirections(F1R1L0X0);

        fake.setIgnition(true); // apply fails once
        awaitCount("muter", "override active=true", 1);
        awaitLog(rt.log(), "DSI not ready");
        assertEquals(1, rt.activeOverrides().size(), "a failed apply keeps the entry (and its prior)");
        assertTrue(fake.appliedLog().isEmpty());

        fake.setIgnition(true); // release → restore succeeds
        awaitApplied(1, F1R1L0X0);
        awaitLedgerEmpty();
        for (String t : threads) {
            assertEquals("mibsdk-ledger", t, "ParkingIo.apply runs only on the ledger thread: " + threads);
        }
    }

    @Test
    void restoreFailureIsRetriedAndKeptInLedger() throws Exception {
        RingLog log = new RingLog(100);
        OverrideLedger l = ledger(log);
        RecordingApplier a = reporting(l);
        VehicleOverride ov = l.acquire("k", "p", OverrideScope.PERSISTENT, "OLD", "NEW", a);
        a.await(1);

        a.failures = 1;
        ov.release();
        assertFalse(ov.isActive());
        awaitLog(log, "ECU busy");
        assertTrue(text(ledgerFile()).contains("k=p|PERSISTENT|OLD|NEW"), "kept until the prior is really back");
        assertEquals(1, l.active().size(), "still shown while the restore is pending");
        assertTrue(l.active().get(0).restorePending());

        clock.advance(5000); // retry delay on the Clock
        a.await(2);
        assertEquals("k=OLD", a.calls.get(1));
        awaitLedgerEmpty();
    }

    @Test
    void failedRestoreNeverGivesUpBacksOffAndStaysVisible() throws Exception {
        RingLog log = new RingLog(200);
        OverrideLedger l = ledger(log);
        RecordingApplier a = reporting(l);
        VehicleOverride ov = l.acquire("k", "p", OverrideScope.PERSISTENT, "OLD", "NEW", a);
        a.await(1);

        a.failures = 7; // more than the 5 attempts an apply gets
        ov.release();
        awaitAttempts(a, 2);
        long[] backoff = {5000, 10000, 20000, 40000, 60000, 60000, 60000};
        for (int n = 1; n <= backoff.length; n++) {
            assertEquals(backoff[n - 1], OverrideLedger.retryDelay(n));
            List<ActiveOverride> act = l.active();
            assertEquals(1, act.size(), "visible while restoring: " + act);
            assertTrue(act.get(0).restorePending());
            assertEquals("NEW", act.get(0).activeValue());
            assertTrue(text(ledgerFile()).contains("k=p|PERSISTENT|OLD|NEW"));
            clock.advance(backoff[n - 1] - 1);
            Thread.sleep(150);
            assertEquals(1 + n, a.threads.size(), "no retry before the backoff elapsed (attempt " + n + ")");
            clock.advance(1);
            awaitAttempts(a, 2 + n);
        }
        a.await(2);
        assertEquals("k=OLD", a.calls.get(1));
        awaitLedgerEmpty();
        assertTrue(l.active().isEmpty());
        assertFalse(logContains(log, "stopped retrying"), log.snapshot().toString());
    }

    @Test
    void observationRetriesPendingRestoreAndObservedPriorCompletesIt() throws Exception {
        RingLog log = new RingLog(200);
        OverrideLedger l = ledger(log);
        RecordingApplier a = reporting(l);
        VehicleOverride ov = l.acquire("k", "p", OverrideScope.PERSISTENT, "OLD", "NEW", a);
        a.await(1);
        a.failures = 1000; // the ECU never accepts the write

        ov.release();
        awaitAttempts(a, 2);
        l.onObserved("k", "NEW"); // any report of the key: retry at once, no Clock advance needed
        awaitAttempts(a, 3);
        assertTrue(l.active().get(0).restorePending());

        l.onObserved("k", "OLD"); // the car has the prior back (e.g. the user reset it): restore is done
        awaitLedgerEmpty();
        assertTrue(l.active().isEmpty());
        assertEquals(1, a.calls.size(), "completed without another write");
        awaitLog(log, "reported by the car");
    }

    /**
     * Controller ruling (Task 11 re-review): an adapter that publishes the current value after every rejected
     * write must not turn observation-triggered retries into a tight loop; retries stay on the Clock backoff.
     */
    @Test
    void identicalObservationsAfterRejectedRestoreDoNotRetryStorm() throws Exception {
        RingLog log = new RingLog(50);
        final OverrideLedger[] ref = new OverrideLedger[1];
        final List<String> restores = Collections.synchronizedList(new ArrayList<String>());
        OverrideLedger.Applier rejecting = new OverrideLedger.Applier() {
            public void apply(String key, String value) throws Exception {
                if (value.equals("NEW")) {
                    restores.add("applied");
                    return; // the override itself goes through
                }
                restores.add(value);
                ref[0].onObserved(key, "NEW"); // the ECU re-reports its (unchanged) value
                throw new IllegalStateException("rejected");
            }
        };
        OverrideLedger l = ledger(log);
        ref[0] = l;
        VehicleOverride ov = l.acquire("k", "p", OverrideScope.PERSISTENT, "OLD", "NEW", rejecting);
        awaitFirst(restores, "applied");
        restores.clear();

        ov.release();
        Thread.sleep(500); // real time only: without coalescing this is hundreds of attempts
        int early = restores.size();
        assertEquals(2, early, "the release, plus one retry for the first (changed) report: " + restores);

        for (int i = 0; i < 10; i++) { // ten minutes of simulated time
            clock.advance(60000);
            Thread.sleep(100);
        }
        Thread.sleep(200);
        int total = restores.size();
        assertTrue(total > early, "backoff retries still happen: " + total);
        assertTrue(total <= early + 10, "at most one retry per elapsed backoff: " + total);
        assertTrue(l.active().get(0).restorePending());

        l.onObserved("k", "OLD"); // a different value (the prior) still completes it at once
        awaitActiveSize(l, 0);
    }

    @Test
    void distinctObservationsQueuedBeforeTheWorkerRunsCoalesce() throws Exception {
        RingLog log = new RingLog(50);
        final Object gate = new Object();
        final boolean[] open = {true};
        final List<String> restores = Collections.synchronizedList(new ArrayList<String>());
        OverrideLedger.Applier blocking = new OverrideLedger.Applier() {
            public void apply(String key, String value) throws Exception {
                if (value.equals("NEW")) {
                    restores.add("applied");
                    return;
                }
                restores.add(value);
                synchronized (gate) {
                    while (!open[0]) {
                        gate.wait();
                    }
                }
                throw new IllegalStateException("rejected");
            }
        };
        OverrideLedger l = ledger(log);
        VehicleOverride ov = l.acquire("k", "p", OverrideScope.PERSISTENT, "OLD", "NEW", blocking);
        awaitFirst(restores, "applied");
        restores.clear();
        synchronized (gate) {
            open[0] = false;
        }
        ov.release();
        for (int i = 0; i < 500 && restores.isEmpty(); i++) {
            Thread.sleep(10);
        }
        assertEquals(1, restores.size());
        for (int i = 0; i < 20; i++) { // the worker is busy: every distinct report would queue one retry
            l.onObserved("k", "V" + i);
        }
        synchronized (gate) {
            open[0] = true;
            gate.notifyAll();
        }
        Thread.sleep(500);
        assertEquals(2, restores.size(), "twenty reports while busy queue one retry: " + restores);
    }

    @Test
    void ignitionOnRetriesFailedRestoreImmediately() throws Exception {
        final boolean[] failing = {false};
        PlatformAdapter asleep = new DelegatingAdapter(fake) {
            @Override
            void beforeApply(SoundDirections v) throws Exception {
                synchronized (failing) {
                    if (failing[0]) {
                        throw new IllegalStateException("parking ECU asleep");
                    }
                }
            }
        };
        togglePlugin("muter", null); // UNTIL_IGNITION_OFF
        start(null, asleep);
        fake.ecuSetSoundDirections(F1R1L0X0);
        fake.setIgnition(true);
        awaitApplied(1, F1R0L0X0);

        synchronized (failing) {
            failing[0] = true;
        }
        fake.setIgnition(false); // the restore fails: the ECU went to sleep with the ignition
        awaitLog(rt.log(), "parking ECU asleep");
        assertEquals(1, rt.activeOverrides().size(), "the indicator keeps showing it");
        assertTrue(rt.activeOverrides().get(0).restorePending());
        assertTrue(text(ledgerFile()).contains("muter|UNTIL_IGNITION_OFF|F1R1L0X0|F1R0L0X0"));

        synchronized (failing) {
            failing[0] = false;
        }
        fake.setIgnition(true); // next drive: retried at once, without waiting for the backoff
        awaitApplied(2, F1R1L0X0);
        awaitNoOverrides();
        awaitLedgerEmpty();
    }

    @Test
    void pluginReleaseInOnDisableDuringStopRestoresPersistent() throws Exception {
        jars.write(plugins, "tidy", "1", null, READ_WRITE,
                "ov = ctx.vehicle().parking().overrideSoundDirections(\n"
                + "    new SoundDirections(true, false, true, true), OverrideScope.PERSISTENT);",
                "VehicleOverride ov;",
                "ov.release(); ctx.log().info(\"released active=\" + ov.isActive());");
        grants.append("tidy=vehicle.read,vehicle.parking.write\n");
        start(null);
        awaitApplied(1, F1R0L1X1);

        rt.stop();
        assertEquals(1, count(rt.log(), "tidy", "released active=false"), rt.log().snapshot().toString());
        awaitApplied(2, F1R1L1X1);
        awaitLedgerEmpty();
        assertTrue(rt.activeOverrides().isEmpty());
    }

    @Test
    void reacquireWhileRestorePendingInheritsPrior() throws Exception {
        RingLog log = new RingLog(100);
        OverrideLedger l = ledger(log);
        final Object gate = new Object();
        final boolean[] open = {false};
        final List<String> calls = Collections.synchronizedList(new ArrayList<String>());
        OverrideLedger.Applier slow = new OverrideLedger.Applier() {
            public void apply(String key, String v) throws Exception {
                synchronized (gate) {
                    while (!open[0]) {
                        gate.wait();
                    }
                }
                calls.add(v);
                l.onObserved(key, v); // the car reports the write
            }
        };
        VehicleOverride first = l.acquire("k", "p", OverrideScope.PERSISTENT, "ORIG", "X", slow);
        first.release(); // restore queued behind the blocked apply
        // The ECU has not been restored yet, so the caller's notion of "prior" is the overridden value.
        VehicleOverride second = l.acquire("k", "q", OverrideScope.PERSISTENT, "X", "Y", slow);
        assertTrue(second.isActive());
        synchronized (gate) {
            open[0] = true;
            gate.notifyAll();
        }
        for (int i = 0; i < 500 && !calls.contains("Y"); i++) {
            Thread.sleep(10);
        }
        assertEquals("Y", calls.get(calls.size() - 1), calls.toString());
        assertEquals("k=q|PERSISTENT|ORIG|Y|A\n", text(ledgerFile()));
        second.release();
        for (int i = 0; i < 500 && !"ORIG".equals(calls.get(calls.size() - 1)); i++) {
            Thread.sleep(10);
        }
        assertEquals("ORIG", calls.get(calls.size() - 1));
    }

    @Test
    void corruptLedgerFileIsEmptyButLogged() throws Exception {
        write(ledgerFile(), "parking.soundDirections=only-two|fields\nk2=p|NOT_A_SCOPE|A|B\nk3=p|PERSISTENT|A|B\n");
        RingLog log = new RingLog(100);
        OverrideLedger l = ledger(log);
        assertEquals(1, l.active().size(), "well-formed lines survive");
        assertEquals("k3", l.active().get(0).key());
        assertTrue(logContains(log, "corrupt"), log.snapshot().toString());

        write(ledgerFile(), "bad=\\u12zz\n");
        RingLog log2 = new RingLog(100);
        OverrideLedger l2 = ledger(log2);
        assertTrue(l2.active().isEmpty());
        assertTrue(logContains(log2, "corrupt"), log2.snapshot().toString());
    }

    @Test
    void ledgerFileHasNoWallClockAndSurvivesRestart() throws Exception {
        OverrideLedger l = ledger(new RingLog(10));
        RecordingApplier a = reporting(l);
        l.acquire(KEY, "muter", OverrideScope.PERSISTENT, "F1R1L0X0", "F1R0L0X0", a);
        assertEquals("parking.soundDirections=muter|PERSISTENT|F1R1L0X0|F1R0L0X0|A\n", text(ledgerFile()),
                "the prior is on disk before acquire returns, and nothing else is");
        assertFalse(new File(ledgerFile().getPath() + ".tmp").exists());

        OverrideLedger again = ledger(new RingLog(10));
        assertEquals(1, again.active().size());
        ActiveOverride o = again.active().get(0);
        assertEquals("muter", o.pluginId());
        assertEquals(OverrideScope.PERSISTENT, o.scope());
        assertEquals("F1R0L0X0", o.activeValue());
    }

    @Test
    void releaseAllForRestoresOnlyThatPlugin() throws Exception {
        OverrideLedger l = ledger(new RingLog(10));
        RecordingApplier a = reporting(l);
        VehicleOverride x = l.acquire("k1", "p1", OverrideScope.PERSISTENT, "A0", "A1", a);
        VehicleOverride y = l.acquire("k2", "p2", OverrideScope.UNTIL_IGNITION_OFF, "B0", "B1", a);
        a.await(2);

        l.releaseAllFor("p1");
        a.await(3);
        assertEquals("k1=A0", a.calls.get(2));
        assertFalse(x.isActive());
        assertTrue(y.isActive());
        x.release(); // already released: no second restore
        Thread.sleep(100);
        assertEquals(3, a.calls.size());
    }

    @Test
    void shutdownKeepsPersistentForNextBootAndRestoresUntilIgnitionOff() throws Exception {
        OverrideLedger l = ledger(new RingLog(10));
        RecordingApplier a = reporting(l);
        l.acquire("k1", "p", OverrideScope.PERSISTENT, "A0", "A1", a);
        l.acquire("k2", "p", OverrideScope.UNTIL_IGNITION_OFF, "B0", "B1", a);
        a.await(2);

        l.beginShutdown();
        l.releaseAllFor("p"); // what disabling the plugin does during runtime stop
        assertTrue(l.awaitSettled(5000));
        assertEquals(3, a.calls.size(), a.calls.toString());
        assertEquals("k2=B0", a.calls.get(2));
        assertEquals("k1=p|PERSISTENT|A0|A1|A\n", text(ledgerFile()));
    }

    @Test
    void pluginHoldingOverrideMonitorCannotWedgeStop() throws Exception {
        plugin("locker", READ_WRITE, READ_WRITE,
                "final VehicleOverride v = ctx.vehicle().parking().overrideSoundDirections(\n"
                + "    new SoundDirections(true, false, true, true), OverrideScope.UNTIL_IGNITION_OFF);\n"
                + "Thread t = new Thread(() -> { synchronized (v) { ctx.log().info(\"locked\");"
                + " try { Thread.sleep(600000L); } catch (InterruptedException e) { } } });\n"
                + "t.setDaemon(true); t.start();", null);
        start(null);
        awaitCount("locker", "locked", 1);
        awaitApplied(1, F1R0L1X1);

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
        awaitApplied(2, F1R1L1X1); // UNTIL_IGNITION_OFF does not outlive the runtime
    }

    @Test
    void soundDirectionsEncoding() {
        assertEquals("F1R0L0X0", ParkingImpl.encode(F1R0L0X0));
        assertEquals("F0R1L0X1", ParkingImpl.encode(new SoundDirections(false, true, false, true)));
        assertEquals(F1R1L0X0, ParkingImpl.decode("F1R1L0X0"));
        assertEquals(new SoundDirections(false, false, true, true), ParkingImpl.decode("F0R0L1X1"));
        assertThrows(IllegalArgumentException.class, () -> ParkingImpl.decode("F1R1L0"));
        assertThrows(IllegalArgumentException.class, () -> ParkingImpl.decode("F1R2L0X0"));
        assertThrows(IllegalArgumentException.class, () -> ParkingImpl.decode("F1R1X0L0"));
        assertThrows(IllegalArgumentException.class, () -> ParkingImpl.decode(null));
        assertEquals(KEY, ParkingImpl.KEY);
    }

    @Test
    void acquireRejectsValuesThatWouldBreakTheFileFormat() {
        OverrideLedger l = ledger(new RingLog(10));
        RecordingApplier a = reporting(l);
        assertThrows(IllegalArgumentException.class, () -> l.acquire("k", "p|q", OverrideScope.PERSISTENT, "0", "1", a));
        assertThrows(IllegalArgumentException.class, () -> l.acquire("k=x", "p", OverrideScope.PERSISTENT, "0", "1", a));
        assertThrows(IllegalArgumentException.class, () -> l.acquire("k", "p", OverrideScope.PERSISTENT, "0\n", "1", a));
        assertThrows(IllegalArgumentException.class, () -> l.acquire("k", "p", null, "0", "1", a));
        assertThrows(IllegalArgumentException.class, () -> l.acquire("k", "p", OverrideScope.PERSISTENT, "0", "1", null));
        assertTrue(l.active().isEmpty());
        assertFalse(ledgerFile().exists());
    }

    // ---- restore state on disk (T11) ----

    private void awaitLedgerText(String expected) throws Exception {
        for (int i = 0; i < 500; i++) {
            if (ledgerFile().isFile() && expected.equals(text(ledgerFile()))) {
                return;
            }
            Thread.sleep(10);
        }
        assertEquals(expected, ledgerFile().isFile() ? text(ledgerFile()) : "(missing)");
    }

    @Test
    void fileLinesCarryTheRestoreState() throws Exception {
        OverrideLedger l = ledger(new RingLog(50));
        RecordingApplier a = reporting(l);
        a.failures = Integer.MAX_VALUE; // the ECU never accepts a write
        VehicleOverride ov = l.acquire("k", "p", OverrideScope.PERSISTENT, "OLD", "NEW", a);
        assertEquals("k=p|PERSISTENT|OLD|NEW|A\n", text(ledgerFile()));

        ov.release();
        awaitLedgerText("k=p|PERSISTENT|OLD|NEW|R\n"); // no Clock advance: written before the first restore
    }

    @Test
    void fourFieldLinesFromOlderRunsLoadAsHeld() throws Exception {
        write(ledgerFile(), "k=p|PERSISTENT|OLD|NEW\n");
        OverrideLedger l = ledger(new RingLog(50));
        assertEquals(1, l.active().size());
        assertFalse(l.active().get(0).restorePending());

        final List<String> fileAtApply = Collections.synchronizedList(new ArrayList<String>());
        final List<String> calls = Collections.synchronizedList(new ArrayList<String>());
        l.attach(new OverrideLedger.Applier() {
            public void apply(String key, String value) throws Exception {
                fileAtApply.add(text(ledgerFile()));
                calls.add(key + "=" + value);
                l.onObserved(key, value); // the car reports the write
            }
        });
        assertTrue(l.awaitSettled(5000));
        assertTrue(calls.isEmpty(), "a held entry is not applied by attach: " + calls);

        l.releaseAllFor("p");
        awaitFirst(calls, "k=OLD");
        assertEquals("k=p|PERSISTENT|OLD|NEW|R\n", fileAtApply.get(0), "the R state is on disk before the restore");
        awaitLedgerEmpty();
    }

    @Test
    void restorePendingAtShutdownIsFinishedByTheNextLedger() throws Exception {
        OverrideLedger l = ledger(new RingLog(50));
        RecordingApplier failing = reporting(l);
        failing.failures = Integer.MAX_VALUE;
        VehicleOverride ov = l.acquire("k", "p", OverrideScope.PERSISTENT, "OLD", "NEW", failing);
        ov.release();
        awaitAttempts(failing, 2);
        assertFalse(l.awaitSettled(5000), "a restore in backoff is not settled");
        l.shutdown();
        assertTrue(text(ledgerFile()).endsWith("|R\n"), text(ledgerFile()));

        OverrideLedger next = ledger(new RingLog(50));
        assertEquals(1, next.active().size());
        assertTrue(next.active().get(0).restorePending());
        RecordingApplier ok = reporting(next);
        next.attach(ok);
        ok.await(1);
        assertEquals("k=OLD", ok.calls.get(0));
        awaitActiveSize(next, 0);
        awaitLedgerEmpty();
    }

    @Test
    void pendingRestoreOfAnEnabledPluginIsFinishedAtTheNextStart() throws Exception {
        togglePlugin("keeper", "PERSISTENT");
        start(null);
        fake.ecuSetSoundDirections(F1R1L0X0);
        fake.setIgnition(true);
        awaitApplied(1, F1R0L0X0);

        fake.ecuFailWrites(Integer.MAX_VALUE);
        rt.disable("keeper", "test");
        awaitApplied(2, F1R1L0X0); // the restore was attempted and rejected
        rt.stop();
        assertTrue(text(ledgerFile()).endsWith("|R\n"), text(ledgerFile()));

        fake.ecuFailWrites(0);
        start(null); // same roots; keeper is still installed and enabled, and does not re-acquire
        assertEquals(PluginState.ENABLED, status("keeper").state());
        awaitNoOverrides();
        assertEquals(F1R1L0X0, fake.ecuSoundDirections());
        awaitLedgerEmpty();
    }

    /** Task 3 review: the R state reaches the file through shutdown()'s own write, not the queued one. */
    @Test
    void shutdownWritesTheRestoreStateWhileTheLedgerThreadIsBlocked() throws Exception {
        OverrideLedger l = ledger(new RingLog(50));
        final java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
        OverrideLedger.Applier gated = new OverrideLedger.Applier() {
            public void apply(String key, String value) throws Exception {
                entered.countDown();
                gate.await(); // the ledger thread is stuck in the platform
            }
        };
        VehicleOverride ov = l.acquire("k", "p", OverrideScope.PERSISTENT, "OLD", "NEW", gated);
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));

        ov.release(); // its R write is queued behind the blocked apply
        Thread.sleep(100);
        assertEquals("k=p|PERSISTENT|OLD|NEW|A\n", text(ledgerFile()), "the queued write has not run");
        l.shutdown();
        assertEquals("k=p|PERSISTENT|OLD|NEW|R\n", text(ledgerFile()));

        gate.countDown();
        Thread.sleep(100);
        assertEquals("k=p|PERSISTENT|OLD|NEW|R\n", text(ledgerFile()), "nothing after shutdown rewrites it");
    }

    // ---- a restore completes only when the car reports the prior (I4) ----

    private static boolean logHas(RingLog log, String level, String fragment) {
        for (String line : log.snapshot()) {
            if (line.contains(" " + level + " ") && line.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void restoreStaysPendingUntilTheCarReportsThePrior() throws Exception {
        RingLog log = new RingLog(100);
        OverrideLedger l = ledger(log);
        RecordingApplier a = new RecordingApplier(); // a silent ECU: accepts writes, reports nothing
        VehicleOverride ov = l.acquire("k", "p", OverrideScope.PERSISTENT, "OLD", "NEW", a);
        a.await(1);

        ov.release();
        a.await(2);
        assertEquals("k=OLD", a.calls.get(1));
        assertFalse(l.awaitSettled(300), "the write returned; the car has not confirmed it");
        assertEquals(1, l.active().size());
        assertTrue(l.active().get(0).restorePending());
        assertEquals("k=p|PERSISTENT|OLD|NEW|R\n", text(ledgerFile()));

        clock.advance(5000);
        awaitLog(log, "restore of k to OLD for p not confirmed by the car (no report within 5000 ms) (attempt 1);"
                + " retrying in 5000 ms");
        assertTrue(l.active().get(0).restorePending());
        clock.advance(5000);
        a.await(3);
        assertEquals("k=OLD", a.calls.get(2));

        l.onObserved("k", "OLD");
        awaitActiveSize(l, 0);
        awaitLedgerEmpty();
        assertEquals(0, ledgerFile().length());
        assertEquals(3, a.calls.size());
        awaitLog(log, "restored k=OLD (confirmed by the car)");
    }

    @Test
    void reportArrivingBeforeApplyReturnsConfirms() throws Exception {
        RingLog log = new RingLog(100);
        final OverrideLedger l = ledger(log);
        final List<String> calls = Collections.synchronizedList(new ArrayList<String>());
        OverrideLedger.Applier racing = new OverrideLedger.Applier() {
            public void apply(String key, String value) throws Exception {
                calls.add(value);
                l.onObserved(key, value); // the report overtakes the return of apply()
                Thread.sleep(50);
            }
        };
        VehicleOverride ov = l.acquire("k", "p", OverrideScope.PERSISTENT, "OLD", "NEW", racing);
        awaitFirst(calls, "NEW");

        ov.release();
        awaitActiveSize(l, 0);
        awaitLedgerEmpty();
        assertTrue(l.awaitSettled(5000));
        assertEquals(Arrays.asList("NEW", "OLD"), calls, "exactly one restore write");
        awaitLog(log, "restored k=OLD (confirmed by the car)");
    }

    @Test
    void lastReportEqualToPriorConfirmsAfterTimeout() throws Exception {
        RingLog log = new RingLog(100);
        final OverrideLedger l = ledger(log);
        final List<String> calls = Collections.synchronizedList(new ArrayList<String>());
        OverrideLedger.Applier a = new OverrideLedger.Applier() {
            public void apply(String key, String value) throws Exception {
                calls.add(value);
                // the car rejects NEW silently, and the restore write is accepted silently: the value did not
                // change, so the car says nothing
            }
        };
        VehicleOverride ov = l.acquire("k", "p", OverrideScope.PERSISTENT, "OLD", "NEW", a);
        awaitFirst(calls, "NEW");
        assertTrue(l.awaitSettled(5000)); // the apply returned
        l.onObserved("k", "OLD"); // after the override's apply: the car still reports OLD

        ov.release();
        awaitFirst(calls, "OLD");
        assertFalse(l.awaitSettled(300), "no report since the write");
        assertTrue(l.active().get(0).restorePending());

        clock.advance(5000);
        awaitActiveSize(l, 0);
        awaitLedgerEmpty();
        assertEquals(Arrays.asList("NEW", "OLD"), calls);
        awaitLogHas(log, "WARN", "restored k=OLD (confirmed by the car's last report; none after the write)");
    }

    @Test
    void reportOfAnotherValueAfterRestoreWriteRetries() throws Exception {
        RingLog log = new RingLog(100);
        OverrideLedger l = ledger(log);
        RecordingApplier a = new RecordingApplier(); // silent
        VehicleOverride ov = l.acquire("k", "p", OverrideScope.PERSISTENT, "OLD", "NEW", a);
        a.await(1);

        ov.release();
        a.await(2);
        assertFalse(l.awaitSettled(300));
        l.onObserved("k", "NEW"); // the car still has the override value: the write did not take
        a.await(3); // no Clock advance
        assertEquals("k=OLD", a.calls.get(2));
        assertTrue(l.active().get(0).restorePending());
        assertTrue(logContains(log, "restore of k to OLD for p not confirmed by the car (reported NEW) (attempt 1);"
                + " retrying now"), log.snapshot().toString());
        assertFalse(logContains(log, "(reported NEW) (attempt 1); retrying in"), log.snapshot().toString());
    }

    @Test
    void droppedRestoreWriteIsRetriedUntilObserved() throws Exception {
        togglePlugin("muter", null); // UNTIL_IGNITION_OFF, rear=false
        start(null);
        fake.ecuSetSoundDirections(F1R1L0X0);
        fake.setIgnition(true);
        awaitApplied(1, F1R0L0X0);

        fake.ecuDropWrites(2);
        fake.setIgnition(false);
        awaitApplied(2, F1R1L0X0);
        assertFalse(rt.ledger().awaitSettled(300));
        assertTrue(rt.activeOverrides().get(0).restorePending());
        assertTrue(text(ledgerFile()).endsWith("|R\n"), text(ledgerFile()));

        clock.advance(5000); // timeout 1
        awaitLog(rt.log(), "(no report within 5000 ms) (attempt 1); retrying in 5000 ms");
        clock.advance(5000); // backoff 1
        awaitApplied(3, F1R1L0X0);
        assertFalse(rt.ledger().awaitSettled(300));
        clock.advance(5000); // timeout 2
        awaitLog(rt.log(), "(no report within 5000 ms) (attempt 2); retrying in 10000 ms");
        clock.advance(10000); // backoff 2: the third restore write is accepted and reported
        awaitApplied(4, F1R1L0X0);
        awaitNoOverrides();
        awaitLedgerEmpty();
        assertEquals(F1R1L0X0, fake.ecuSoundDirections());
        assertEquals(2, fake.droppedWrites());
    }

    /** Review Focus 1 with the fake ECU: the restore rewrites the value the ECU already holds, so it stays silent. */
    @Test
    void restoreOfAValueTheEcuStillHoldsConfirmsByItsLastReport() throws Exception {
        togglePlugin("muter", null);
        start(null);
        fake.ecuSetSoundDirections(F1R1L0X0);
        fake.ecuReportUnchanged(false);
        fake.ecuDropWrites(1); // the override itself never reaches the ECU
        fake.setIgnition(true);
        awaitApplied(1, F1R0L0X0);
        assertTrue(rt.ledger().awaitSettled(5000)); // the apply returned
        fake.ecuSetSoundDirections(F1R1L0X0); // the car reports the value it kept, after the apply

        fake.setIgnition(false);
        awaitApplied(2, F1R1L0X0);
        assertFalse(rt.ledger().awaitSettled(300), "an unchanged value is not reported");
        assertTrue(rt.activeOverrides().get(0).restorePending());

        clock.advance(5000);
        awaitNoOverrides();
        awaitLedgerEmpty();
        awaitLog(rt.log(), "(confirmed by the car's last report; none after the write)");
        assertEquals(2, fake.appliedLog().size(), "no second restore write");
    }

    @Test
    void stopKeepsAnUnconfirmedRestorePendingForTheNextBoot() throws Exception {
        jars.write(plugins, "tidy", "1", null, READ_WRITE,
                "ov = ctx.vehicle().parking().overrideSoundDirections(\n"
                + "    new SoundDirections(true, false, true, true), OverrideScope.PERSISTENT);",
                "VehicleOverride ov;",
                "ov.release();");
        grants.append("tidy=vehicle.read,vehicle.parking.write\n");
        start(null);
        awaitApplied(1, F1R0L1X1);

        fake.ecuDropWrites(Integer.MAX_VALUE);
        long t0 = System.nanoTime();
        rt.stop();
        long ms = (System.nanoTime() - t0) / 1000000L;
        assertTrue(ms < 8500, "stop took " + ms + " ms");
        assertEquals(2, fake.appliedLog().size(), "the restore was written once and dropped");
        assertTrue(text(ledgerFile()).endsWith("|R\n"), text(ledgerFile()));
        assertTrue(logContains(rt.log(), "override restores still pending at stop"), rt.log().snapshot().toString());
    }

    /**
     * Ruling A: at the timeout, a last report equal to the prior confirms only if it came after the
     * override's own apply. Here the car reports OLD before the NEW write lands, NEW's notification is lost and
     * the restore write is dropped: the car still holds NEW, so the restore must be retried.
     */
    @Test
    void lastReportFromBeforeTheOverrideApplyDoesNotConfirmAtTimeout() throws Exception {
        RingLog log = new RingLog(100);
        final OverrideLedger l = ledger(log);
        final List<String> calls = Collections.synchronizedList(new ArrayList<String>());
        OverrideLedger.Applier a = new OverrideLedger.Applier() {
            public void apply(String key, String value) throws Exception {
                calls.add(value);
                if (value.equals("NEW") && calls.size() == 1) {
                    l.onObserved(key, "OLD"); // a stale report, overtaken by the write; NEW's report is lost
                }
                // the restore writes are dropped: the car keeps NEW and says nothing
            }
        };
        VehicleOverride ov = l.acquire("k", "p", OverrideScope.PERSISTENT, "OLD", "NEW", a);
        awaitFirst(calls, "NEW");
        assertTrue(l.awaitSettled(5000));

        ov.release();
        awaitFirst(calls, "OLD");
        assertFalse(l.awaitSettled(300));
        clock.advance(5000);
        awaitLog(log, "restore of k to OLD for p not confirmed by the car (no report within 5000 ms) (attempt 1);"
                + " retrying in 5000 ms");
        assertEquals(1, l.active().size());
        assertTrue(l.active().get(0).restorePending());
        assertFalse(logContains(log, "confirmed by the car's last report"), log.snapshot().toString());

        clock.advance(5000);
        for (int i = 0; i < 500 && calls.size() < 3; i++) {
            Thread.sleep(10);
        }
        assertEquals(Arrays.asList("NEW", "OLD", "OLD"), calls, "the restore is written again");
        l.onObserved("k", "OLD");
        awaitActiveSize(l, 0);
        awaitLedgerEmpty();
    }

    /** Ruling B: a restore moved to backoff (not awaiting confirmation) still means "not settled". */
    @Test
    void awaitSettledIsFalseWhileARestoreWaitsForItsBackoff() throws Exception {
        OverrideLedger l = ledger(new RingLog(50));
        RecordingApplier a = reporting(l);
        VehicleOverride ov = l.acquire("k", "p", OverrideScope.PERSISTENT, "OLD", "NEW", a);
        a.await(1);
        a.failures = Integer.MAX_VALUE;
        ov.release();
        awaitAttempts(a, 2); // the restore threw: a failed attempt, retried on the Clock in 5 s
        assertFalse(l.awaitSettled(300));
    }

    @Test
    void stopWarnsWhenARestoreIsInBackoff() throws Exception {
        togglePlugin("keeper", "PERSISTENT");
        start(null);
        fake.ecuSetSoundDirections(F1R1L0X0);
        fake.setIgnition(true);
        awaitApplied(1, F1R0L0X0);

        fake.ecuFailWrites(Integer.MAX_VALUE);
        rt.disable("keeper", "test");
        awaitApplied(2, F1R1L0X0); // rejected: in backoff, nothing awaits confirmation
        rt.stop();
        assertTrue(logHas(rt.log(), "WARN", "override restores still pending at stop"), rt.log().snapshot().toString());
    }

    // ---- ignition-aware ledger and power loss around KL15 (T18) ----

    @Test
    void untilIgnitionOffOverrideAcquiredWhileOffEndsAtIgnitionOn() throws Exception {
        RingLog log = new RingLog(100);
        OverrideLedger l = ledger(log);
        RecordingApplier a = reporting(l);
        l.ignitionChanged(false);
        l.acquire("k", "p", OverrideScope.UNTIL_IGNITION_OFF, "OLD", "NEW", a);
        a.await(1);
        assertEquals("k=NEW", a.calls.get(0));
        assertTrue(l.awaitSettled(5000));
        assertEquals(1, l.active().size());

        l.ignitionChanged(true);
        a.await(2);
        assertEquals("k=OLD", a.calls.get(1));
        awaitActiveSize(l, 0);
        awaitLedgerEmpty();
        awaitLog(log, "restoring k: acquired while the ignition was off; ignition on");
    }

    @Test
    void persistentOverrideAcquiredWhileOffIsKept() throws Exception {
        OverrideLedger l = ledger(new RingLog(50));
        RecordingApplier a = reporting(l);
        l.ignitionChanged(false);
        l.acquire("k", "p", OverrideScope.PERSISTENT, "OLD", "NEW", a);
        a.await(1);

        l.ignitionChanged(true);
        assertTrue(l.awaitSettled(5000));
        assertEquals(1, l.active().size());
        assertFalse(l.active().get(0).restorePending());
        assertEquals(1, a.calls.size(), a.calls.toString());
        assertEquals("k=p|PERSISTENT|OLD|NEW|A\n", text(ledgerFile()));
    }

    @Test
    void untilIgnitionOffOverrideAcquiredWhileOnIsKeptAtIgnitionOn() throws Exception {
        OverrideLedger l = ledger(new RingLog(50));
        RecordingApplier a = reporting(l);
        l.ignitionChanged(true);
        l.acquire("k", "p", OverrideScope.UNTIL_IGNITION_OFF, "OLD", "NEW", a);
        a.await(1);

        l.ignitionChanged(true); // a repeated ignition on ends nothing
        assertTrue(l.awaitSettled(5000));
        assertEquals(1, l.active().size());
        assertFalse(l.active().get(0).restorePending());

        l.ignitionChanged(false);
        a.await(2);
        assertEquals("k=OLD", a.calls.get(1));
        awaitActiveSize(l, 0);
    }

    @Test
    void haltWritesNothingMore() throws Exception {
        OverrideLedger l = ledger(new RingLog(50));
        RecordingApplier a = new RecordingApplier(); // silent: the restore waits for a report
        VehicleOverride ov = l.acquire("k", "p", OverrideScope.PERSISTENT, "OLD", "NEW", a);
        a.await(1);
        ov.release();
        a.await(2);
        awaitLedgerText("k=p|PERSISTENT|OLD|NEW|R\n");

        l.halt();
        l.onObserved("k", "OLD"); // would complete the restore and empty the file
        clock.advance(60000);
        Thread.sleep(100);
        l.shutdown(); // after a halt: no final write either
        assertEquals("k=p|PERSISTENT|OLD|NEW|R\n", text(ledgerFile()));
        assertEquals(2, a.calls.size(), a.calls.toString());
    }

    /** Fix round 1: a task already on the ledger thread when the power goes cannot write the file afterwards. */
    @Test
    void haltStopsTheWriteOfARestoreFinishingOnTheLedgerThread() throws Exception {
        final OverrideLedger l = ledger(new RingLog(50));
        final java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch returned = new java.util.concurrent.CountDownLatch(1);
        OverrideLedger.Applier a = new OverrideLedger.Applier() {
            public void apply(String key, String value) throws Exception {
                if (value.equals("OLD")) {
                    entered.countDown();
                    while (gate.getCount() > 0) { // the platform call ignores the halt's interrupt
                        try {
                            gate.await();
                        } catch (InterruptedException ignored) {
                            // keep blocking until the test releases it
                        }
                    }
                    returned.countDown();
                }
            }
        };
        VehicleOverride ov = l.acquire("k", "p", OverrideScope.PERSISTENT, "OLD", "NEW", a);
        assertTrue(l.awaitSettled(5000)); // NEW applied: the R write is queued ahead of the restore below
        ov.release();
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
        awaitLedgerText("k=p|PERSISTENT|OLD|NEW|R\n");

        l.onObserved("k", "OLD"); // overtakes the blocked restore write: it completes when apply() returns
        l.halt();
        gate.countDown();
        assertTrue(returned.await(5, java.util.concurrent.TimeUnit.SECONDS));
        awaitActiveSize(l, 0); // the restore finished in memory and tried to rewrite the file
        Thread.sleep(100);
        assertEquals("k=p|PERSISTENT|OLD|NEW|R\n", text(ledgerFile()), "no write after the halt");
    }

    /**
     * T5 review: the adapter's first observation is "off" (KL15 already off at boot) and arrives before the plugin
     * load. An UNTIL_IGNITION_OFF override a plugin takes after that (here in onEnable) belongs to no drive: the next
     * ignition on ends it.
     */
    @Test
    void overrideAcquiredAfterAnInitialIgnitionOffEndsAtTheNextIgnitionOn() throws Exception {
        plugin("early", READ_WRITE, READ_WRITE,
                "ov = ctx.vehicle().parking().overrideSoundDirections(new SoundDirections(true, false, true, true),\n"
                + "    OverrideScope.UNTIL_IGNITION_OFF);\n"
                + "ctx.log().info(\"acquired active=\" + ov.isActive());",
                "VehicleOverride ov;");
        start("plugin.start.delay.ms=30000\n");
        fake.ecuSetSoundDirections(F1R1L1X1);
        fake.setIgnition(false); // the adapter's first observation, before the load
        clock.advance(30000);
        awaitCount("early", "acquired active=true", 1);
        awaitApplied(1, F1R0L1X1);
        assertEquals(1, rt.activeOverrides().size());

        fake.setIgnition(true);
        awaitNoOverrides();
        assertEquals(F1R1L1X1, fake.ecuSoundDirections());
        awaitLog(rt.log(), "restoring " + KEY + ": acquired while the ignition was off; ignition on");
        awaitLedgerEmpty();
    }

    /** Fix round 1: a plugin that re-acquires on IgnitionChanged(true) keeps its new override in force. */
    @Test
    void reacquireOnIgnitionOnAfterTheRestoreOfAnOverrideAcquiredWhileOffStays() throws Exception {
        plugin("shift", READ_WRITE, READ_WRITE,
                "ctx.events().on(IgnitionChanged.class, e -> {\n"
                + "  try {\n"
                + "    ov = ctx.vehicle().parking().overrideSoundDirections(e.isOn()\n"
                + "        ? new SoundDirections(true, true, false, false) : new SoundDirections(true, false, true, true),\n"
                + "        OverrideScope.UNTIL_IGNITION_OFF);\n"
                + "    ctx.log().info(\"acquired on=\" + e.isOn());\n"
                + "  } catch (OverrideConflictException x) { ctx.log().info(\"conflict \" + x.getMessage()); }\n"
                + "});",
                "VehicleOverride ov;");
        start(null);
        fake.ecuSetSoundDirections(F1R1L1X1);
        fake.setIgnition(false);
        awaitApplied(1, F1R0L1X1); // acquired while off

        fake.setIgnition(true); // the ledger restores the off-override first, then the plugin re-acquires
        awaitLog(rt.log(), "acquired on=true");
        for (int i = 0; i < 500 && !F1R1L0X0.equals(fake.ecuSoundDirections()); i++) {
            Thread.sleep(10);
        }
        assertTrue(rt.ledger().awaitSettled(5000), rt.activeOverrides().toString());
        assertEquals(F1R1L0X0, fake.ecuSoundDirections());
        assertEquals(1, rt.activeOverrides().size());
        assertFalse(rt.activeOverrides().get(0).restorePending());
        assertEquals("shift", rt.activeOverrides().get(0).pluginId());
        assertTrue(text(ledgerFile()).startsWith(KEY + "=shift|UNTIL_IGNITION_OFF|"), text(ledgerFile()));
        assertTrue(text(ledgerFile()).endsWith("|A\n"), text(ledgerFile()));
        awaitLog(rt.log(), "restoring " + KEY + ": acquired while the ignition was off; ignition on");
    }

    @Test
    void overrideAppliedAsIgnitionDropsIsRestoredAfterPowerLoss() throws Exception {
        togglePlugin("muter", null); // UNTIL_IGNITION_OFF, rear=false
        start(null);
        fake.ecuSetSoundDirections(F1R1L0X0);
        fake.setIgnition(true);
        awaitApplied(1, F1R0L0X0);

        fake.ecuDropWrites(Integer.MAX_VALUE); // the PDC ECU sleeps after KL15 off
        fake.setIgnition(false);
        for (int i = 0; i < 500 && !(ledgerFile().isFile() && text(ledgerFile()).endsWith("|R\n")
                && fake.droppedWrites() >= 1); i++) {
            Thread.sleep(10);
        }
        assertTrue(text(ledgerFile()).endsWith("|R\n"), text(ledgerFile()));
        assertEquals(1, fake.droppedWrites());
        assertEquals(F1R0L0X0, fake.ecuSoundDirections());

        rt.haltForTest(); // power loss: no stop(), no awaitSettled, no final write
        assertTrue(text(ledgerFile()).endsWith("|R\n"), text(ledgerFile()));

        fake.ecuDropWrites(0);
        start(null); // the next boot, same roots and the same car
        awaitNoOverrides();
        assertEquals(F1R1L0X0, fake.ecuSoundDirections());
        awaitLedgerEmpty();
    }

    @Test
    void acquireIsOnDiskBeforeTheEcuSeesIt() throws Exception {
        togglePlugin("muter", null);
        final List<String> fileAtApply = Collections.synchronizedList(new ArrayList<String>());
        PlatformAdapter watching = new DelegatingAdapter(fake) {
            @Override
            void beforeApply(SoundDirections v) throws Exception {
                fileAtApply.add(text(ledgerFile()));
            }
        };
        start(null, watching);
        fake.ecuSetSoundDirections(F1R1L0X0);
        fake.setIgnition(true);
        awaitApplied(1, F1R0L0X0);
        assertEquals(1, fileAtApply.size(), fileAtApply.toString());
        assertTrue(fileAtApply.get(0).startsWith(KEY + "=muter|UNTIL_IGNITION_OFF|"), fileAtApply.get(0));
        assertTrue(fileAtApply.get(0).endsWith("|A\n"), fileAtApply.get(0));
    }

    /**
     * Final review C1: the override is released while its own NEW write is in flight, and the car reports the
     * prior (OLD) before NEW lands. That report predates the write: it must not confirm the restore. The restore
     * is written after NEW and the entry stays until the car reports OLD after it.
     */
    @Test
    void reportOfThePriorDuringTheOverrideWriteDoesNotConfirmTheRestore() throws Exception {
        RingLog log = new RingLog(100);
        final OverrideLedger l = ledger(log);
        final List<String> calls = Collections.synchronizedList(new ArrayList<String>());
        final java.util.concurrent.CountDownLatch landed = new java.util.concurrent.CountDownLatch(1);
        OverrideLedger.Applier a = new OverrideLedger.Applier() {
            public void apply(String key, String value) throws Exception {
                calls.add(value);
                if (value.equals("NEW")) {
                    landed.await(); // the NEW write is in flight; its report is lost
                }
            }
        };
        VehicleOverride ov = l.acquire("k", "p", OverrideScope.UNTIL_IGNITION_OFF, "OLD", "NEW", a);
        awaitFirst(calls, "NEW");
        ov.release();
        l.onObserved("k", "OLD"); // the car's value before NEW landed
        landed.countDown();

        awaitFirst(calls, "OLD");
        assertEquals(Arrays.asList("NEW", "OLD"), calls);
        assertFalse(l.awaitSettled(300), "the restore awaits a report after its write");
        assertEquals(1, l.active().size());
        assertTrue(l.active().get(0).restorePending());
        assertFalse(logContains(log, "restored k=OLD (reported by the car)"), log.snapshot().toString());

        l.onObserved("k", "OLD");
        awaitActiveSize(l, 0);
        awaitLedgerEmpty();
    }

    /**
     * Final review C1, end to end: the mute is in flight when the ignition goes off and the PDC ECU reports its
     * old value before the mute lands. The old value must be written back and the override kept until the car
     * reports it after that write.
     */
    @Test
    void staleReportWhileTheMuteIsInFlightAtIgnitionOffStillRestores() throws Exception {
        togglePlugin("muter", null); // UNTIL_IGNITION_OFF, rear=false
        final java.util.concurrent.CountDownLatch inFlight = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch landed = new java.util.concurrent.CountDownLatch(1);
        PlatformAdapter blocking = new DelegatingAdapter(fake) {
            @Override
            void beforeApply(SoundDirections v) throws Exception {
                if (v.equals(F1R0L0X0)) {
                    inFlight.countDown();
                    landed.await();
                }
            }
        };
        start(null, blocking);
        fake.ecuSetSoundDirections(F1R1L0X0);
        fake.ecuReportWrites(false); // the ECU's reports of the SDK's writes are lost
        fake.setIgnition(true);
        assertTrue(inFlight.await(5, java.util.concurrent.TimeUnit.SECONDS));

        fake.setIgnition(false);
        fake.ecuSetSoundDirections(F1R1L0X0); // stale: the ECU reports its value before the mute lands
        landed.countDown();

        awaitApplied(2, F1R1L0X0);
        assertEquals(F1R1L0X0, fake.ecuSoundDirections());
        assertFalse(rt.ledger().awaitSettled(300));
        assertEquals(1, rt.activeOverrides().size());
        assertTrue(rt.activeOverrides().get(0).restorePending());

        fake.ecuSetSoundDirections(F1R1L0X0); // the car reports the old value after the restore write
        awaitNoOverrides();
        awaitLedgerEmpty();
    }

    /**
     * Final review M3 follow-up: a plugin re-acquires at the moment the ledger finishes restoring its previous
     * override. The value it read as the prior is still the override value; the ledger has since restored and
     * dropped the entry. Recording that stale prior would end the new override back on the muted value. The
     * prior is read again instead.
     */
    @Test
    void priorReadBeforeARestoreFinishedIsReadAgain() throws Exception {
        final OverrideLedger l = ledger(new RingLog(100));
        final RecordingApplier a = reporting(l);
        final VehicleOverride first = l.acquire(KEY, "p", OverrideScope.UNTIL_IGNITION_OFF, "F1R1L1X1", "F1R0L1X1", a);
        a.await(1);
        final List<String> reads = Collections.synchronizedList(new ArrayList<String>());
        ParkingIo io = new ParkingIo() {
            public SoundDirections current() {
                if (reads.isEmpty()) {
                    reads.add("stale");
                    first.release(); // the restore finishes right after this read
                    try {
                        awaitActiveSize(l, 0);
                    } catch (InterruptedException x) {
                        throw new AssertionError(x);
                    }
                    return F1R0L1X1;
                }
                reads.add("fresh");
                return F1R1L1X1;
            }

            public void apply(SoundDirections value) throws Exception {
                a.apply(KEY, ParkingImpl.encode(value));
            }
        };
        Set<String> granted = new HashSet<String>(Arrays.asList(READ_WRITE));
        ParkingImpl parking = new ParkingImpl("p", io, l, new Registrations(), granted, granted);

        parking.overrideSoundDirections(F1R0L1X1, OverrideScope.UNTIL_IGNITION_OFF);
        assertEquals(Arrays.asList("stale", "fresh"), reads);
        awaitLedgerText(KEY + "=p|UNTIL_IGNITION_OFF|F1R1L1X1|F1R0L1X1|A\n");
    }

    /** Triage T1: the ledger file is in place but its final sync failed: a WARN says it may not survive. */
    @Test
    void ledgerWriteWhoseFinalSyncFailsIsWarned() throws Exception {
        RingLog log = new RingLog(100);
        OverrideLedger l = ledger(log);
        l.fileOps = new TargetSyncFails();
        RecordingApplier a = reporting(l);
        l.acquire("k", "p", OverrideScope.PERSISTENT, "OLD", "NEW", a);
        a.await(1);
        assertEquals("k=p|PERSISTENT|OLD|NEW|A\n", text(ledgerFile()));
        awaitLogHas(log, "WARN", "ledger.properties written, but its final sync failed");
    }

    /** Untouched {@link DurableFiles#SYSTEM}, except that the sync of a file (not a directory) fails. */
    static final class TargetSyncFails implements DurableFiles.Ops {
        public void syncFile(java.io.FileDescriptor fd) throws IOException {
            DurableFiles.SYSTEM.syncFile(fd);
        }

        public void syncPath(File f, boolean directory) throws IOException {
            if (!directory) {
                throw new IOException("sync refused (test)");
            }
            DurableFiles.SYSTEM.syncPath(f, directory);
        }

        public boolean rename(File from, File to) {
            return DurableFiles.SYSTEM.rename(from, to);
        }

        public boolean delete(File f) {
            return DurableFiles.SYSTEM.delete(f);
        }
    }

    /**
     * An UNTIL_IGNITION_OFF override taken before the ledger saw any ignition event ends when the first event is
     * "off".
     */
    @Test
    void untilIgnitionOffOverrideTakenBeforeTheFirstIgnitionEventEndsAtAFirstOff() throws Exception {
        OverrideLedger l = ledger(new RingLog(50));
        RecordingApplier a = reporting(l);
        l.acquire("k", "p", OverrideScope.UNTIL_IGNITION_OFF, "OLD", "NEW", a);
        a.await(1);

        l.ignitionChanged(false);
        a.await(2);
        assertEquals("k=OLD", a.calls.get(1));
        awaitActiveSize(l, 0);
        awaitLedgerEmpty();
    }

    // ---- fixtures ----

    /** Delegates to a {@link FakePlatform}, with a hook before every ParkingIo.apply. */
    private static class DelegatingAdapter implements PlatformAdapter {
        private final FakePlatform inner;

        DelegatingAdapter(FakePlatform inner) {
            this.inner = inner;
        }

        void beforeApply(SoundDirections v) throws Exception {
        }

        public String id() {
            return inner.id();
        }

        public boolean supports(String hmiVersion) {
            return inner.supports(hmiVersion);
        }

        public Set<String> features() {
            return inner.features();
        }

        public void start(PlatformHost host) {
            inner.start(host);
        }

        public void stop() {
            inner.stop();
        }

        public ParkingIo parking() {
            final ParkingIo io = inner.parking();
            assertNotNull(io);
            return new ParkingIo() {
                public SoundDirections current() {
                    return io.current();
                }

                public void apply(SoundDirections value) throws Exception {
                    beforeApply(value);
                    io.apply(value);
                }
            };
        }
    }
}
