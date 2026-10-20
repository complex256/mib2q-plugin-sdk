package mibsdk.adapter.hqc;

import static mibsdk.adapter.hqc.Fakes.eventually;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import mibsdk.adapter.hqc.Fakes.Recorder;
import mibsdk.adapter.hqc.Fakes.RecordingHost;
import mibsdk.api.Features;
import mibsdk.api.IgnitionChanged;
import mibsdk.api.ParkingSoundChanged;
import mibsdk.api.SoundDirections;
import mibsdk.platform.PlatformAdapter;
import mibsdk.runtime.AdapterSelector;
import org.dsi.ifc.carparkingsystem.DSICarParkingSystem;
import org.dsi.ifc.carparkingsystem.DSICarParkingSystemListener;
import org.dsi.ifc.carparkingsystem.PDCSoundReproduction;
import org.dsi.ifc.powermanagement.ClampSignal;
import org.dsi.ifc.powermanagement.DSIPowerManagement;
import org.dsi.ifc.powermanagement.DSIPowerManagementListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

class HqcPlatformTest {

    private static final String PARKING = DSICarParkingSystem.class.getName();
    private static final String POWER = DSIPowerManagement.class.getName();
    private static final int VALID = DsiConstants.get("org.dsi.ifc.base.DSIListener", "ATTRVALIDFLAG_VALID");

    @TempDir
    File dir;

    /** A BundleContext serving the given services by interface name; optionally blocking every lookup. */
    static final class Ctx {
        final Map<String, Object> services = new ConcurrentHashMap<>();
        final AtomicInteger lookups = new AtomicInteger();
        final List<String> lookupThreads = Collections.synchronizedList(new ArrayList<>());
        volatile long blockMs;

        BundleContext context() {
            return (BundleContext) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {BundleContext.class}, (p, m, a) -> {
                        switch (m.getName()) {
                            case "getServiceReferences": {
                                lookups.incrementAndGet();
                                lookupThreads.add(Thread.currentThread().getName());
                                if (blockMs > 0) {
                                    Thread.sleep(blockMs);
                                }
                                Object s = services.get((String) a[0]);
                                return s == null ? null : new ServiceReference[] {ref((String) a[0])};
                            }
                            case "getService":
                                return services.get(a[0].toString());
                            case "hashCode":
                                return System.identityHashCode(p);
                            case "equals":
                                return p == a[0];
                            case "toString":
                                return "fake-ctx";
                            default:
                                return null;
                        }
                    });
        }

        private static ServiceReference ref(String name) {
            return (ServiceReference) Proxy.newProxyInstance(Ctx.class.getClassLoader(),
                    new Class<?>[] {ServiceReference.class}, (p, m, a) -> {
                        switch (m.getName()) {
                            case "toString":
                                return name;
                            case "hashCode":
                                return name.hashCode();
                            case "equals":
                                return p == a[0];
                            default:
                                return null;
                        }
                    });
        }
    }

    private File versionFile(String hmiLine) throws IOException {
        File f = new File(dir, "version_info.txt");
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(("MU = whatever\n" + hmiLine + "\n").getBytes(StandardCharsets.ISO_8859_1));
        }
        return f;
    }

    @Test
    void supportsOnlyExactVersion() throws Exception {
        HqcPlatform p = new HqcPlatform(new Ctx().context());
        assertEquals("HMI_AU_MIB2_HQC_NAR_G22_C10_180803", HqcPlatform.SUPPORTED);
        assertEquals(HqcVersion.SUPPORTED, HqcPlatform.SUPPORTED);
        assertTrue(p.supports("HMI_AU_MIB2_HQC_NAR_G22_C10_180803"));
        assertFalse(p.supports("HMI_AU_MIB2_HQC_NAR_G22_C10_180804"));
        assertFalse(p.supports("HMI_AU_MIB2_HQC_NAR_G22_C10_180803 "), "the adapter itself compares exactly");
        assertFalse(p.supports(null));

        String trailing = AdapterSelector.readHmiVersion(versionFile("HMI = HMI_AU_MIB2_HQC_NAR_G22_C10_180803   "));
        assertSame(p, AdapterSelector.select(trailing, new PlatformAdapter[] {p}), "trimmed by the selector");
        String other = AdapterSelector.readHmiVersion(versionFile("HMI = HMI_AU_MIB2_HQC_NAR_G22_C10_180804"));
        assertNull(AdapterSelector.select(other, new PlatformAdapter[] {p}));
    }

    @Test
    void idAndFeatures() {
        boolean wrapping = SdkGlobals.wrappingEnabled;
        boolean ui = SdkGlobals.uiEnabled;
        try {
            SdkGlobals.uiEnabled = false; // UI features: uiFeaturesFollowUiEnabled
            HqcPlatform p = new HqcPlatform(new Ctx().context());
            assertEquals("hqc-nar-g22-c10", p.id());
            HashSet<String> base = new HashSet<>(Arrays.asList(Features.PARKING, Features.IGNITION));
            HashSet<String> hooked = new HashSet<>(base);
            hooked.add(Features.HOOK_PARKING);

            RawServices.clear();
            SdkGlobals.wrappingEnabled = false;
            assertEquals(base, p.features(), "configurator off (safe mode, wrap loop, other HMI): no hook points");
            RawServices.record(PARKING, new Object());
            assertEquals(base, p.features(), "wrapping disabled");

            RawServices.clear();
            SdkGlobals.wrappingEnabled = true;
            assertEquals(base, p.features(), "wrapping on, but the parking service was never wrapped");
            RawServices.record(PARKING, new Object());
            assertEquals(hooked, p.features());
            assertThrows(UnsupportedOperationException.class, () -> p.features().add("x"));
        } finally {
            SdkGlobals.wrappingEnabled = wrapping;
            SdkGlobals.uiEnabled = ui;
            RawServices.clear();
        }
    }

    @Test
    void uiFeaturesFollowUiEnabled() {
        boolean wrapping = SdkGlobals.wrappingEnabled;
        boolean ui = SdkGlobals.uiEnabled;
        try {
            HqcPlatform p = new HqcPlatform(new Ctx().context());
            List<String> uiFeatures = Arrays.asList(Features.SLOT_CAR_MENU, Features.UI_PAGES, Features.SLOT_STATUS_BAR);
            for (boolean hooked : new boolean[] {false, true}) {
                RawServices.clear();
                SdkGlobals.wrappingEnabled = hooked;
                if (hooked) {
                    RawServices.record(PARKING, new Object());
                }
                SdkGlobals.uiEnabled = true;
                assertTrue(p.features().containsAll(uiFeatures), "ui on, hooked=" + hooked);
                assertEquals(hooked, p.features().contains(Features.HOOK_PARKING));
                assertTrue(p.features().contains(Features.PARKING));
                assertThrows(UnsupportedOperationException.class, () -> p.features().add("x"));
                SdkGlobals.uiEnabled = false;
                for (String f : uiFeatures) {
                    assertFalse(p.features().contains(f), "ui off, hooked=" + hooked + ": " + f);
                }
                assertEquals(hooked, p.features().contains(Features.HOOK_PARKING));
            }
        } finally {
            SdkGlobals.wrappingEnabled = wrapping;
            SdkGlobals.uiEnabled = ui;
            RawServices.clear();
        }
    }

    private static boolean threadAlive(String name) {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (name.equals(t.getName()) && t.isAlive()) {
                return true;
            }
        }
        return false;
    }

    /** Carried requirement 4: with the UI off (wrap-loop latch) start never waits for the HMI nor starts mibsdk-ui. */
    @Test
    void uiOffStartsNoUiThreadAndNeverAsksForTheHmi() throws Exception {
        boolean ui = SdkGlobals.uiEnabled;
        try {
            SdkGlobals.uiEnabled = false;
            Ctx c = new Ctx();
            c.services.put(HqcPlatform.FRAMEWORK_ACCESS, new Object());
            HqcPlatform p = new HqcPlatform(c.context(), () -> 0L);
            p.start(new RecordingHost());
            assertTrue(eventually(2000, () -> c.lookups.get() >= 2));
            Thread.sleep(300);
            assertFalse(c.lookupThreads.contains("mibsdk-wait-IFrameworkAccess"), "no HMI wait");
            assertFalse(threadAlive("mibsdk-ui"));
            p.stop();
        } finally {
            SdkGlobals.uiEnabled = ui;
        }
    }

    @Test
    void uiOnStartsTheApplierOnceTheHmiFrameworkAppearsAndStopEndsIt() throws Exception {
        boolean ui = SdkGlobals.uiEnabled;
        try {
            SdkGlobals.uiEnabled = true;
            Ctx c = new Ctx();
            HqcPlatform p = new HqcPlatform(c.context(), () -> 0L);
            p.start(new RecordingHost());
            assertTrue(eventually(2000, () -> c.lookupThreads.contains("mibsdk-wait-IFrameworkAccess")));
            assertFalse(threadAlive("mibsdk-ui"), "not before the framework service exists");
            c.services.put(HqcPlatform.FRAMEWORK_ACCESS, Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {de.audi.atip.base.IFrameworkAccess.class}, (px, m, a) -> null));
            assertTrue(eventually(3000, () -> threadAlive("mibsdk-ui")));
            p.stop();
            assertFalse(threadAlive("mibsdk-ui"), "stop waits for the applier's teardown");
        } finally {
            SdkGlobals.uiEnabled = ui;
        }
    }

    /** The class-loader seam: a split loader would give the activator its own, never-set SdkGlobals. */
    @Test
    void seamLineReportsWhatTheConfiguratorLeft() {
        boolean wrapping = SdkGlobals.wrappingEnabled;
        boolean seen = SdkGlobals.configuratorSeen;
        String reason = SdkGlobals.wrapOffReason;
        try {
            RawServices.clear();
            SdkGlobals.wrapOffReason = null;
            SdkGlobals.configuratorSeen = false;
            SdkGlobals.wrappingEnabled = false;
            assertEquals("seam configurator=absent wrapping=false raw.parking=false", SdkBoot.seamLine());
            SdkGlobals.configuratorSeen = true;
            SdkGlobals.wrappingEnabled = true;
            RawServices.record(PARKING, new Object());
            assertEquals("seam configurator=seen wrapping=true raw.parking=true", SdkBoot.seamLine());
            SdkGlobals.wrappingEnabled = false;
            SdkGlobals.wrapOffReason = "safe-mode";
            assertEquals("seam configurator=seen wrapping=false reason=safe-mode raw.parking=true",
                    SdkBoot.seamLine());
        } finally {
            SdkGlobals.wrapOffReason = reason;
            SdkGlobals.wrappingEnabled = wrapping;
            SdkGlobals.configuratorSeen = seen;
            RawServices.clear();
        }
    }

    @Test
    void startNeverBlocksTheCaller() {
        Ctx c = new Ctx();
        c.blockMs = 5000;
        HqcPlatform p = new HqcPlatform(c.context());
        long t0 = System.nanoTime();
        assertDoesNotThrow(() -> p.start(new RecordingHost()));
        assertTrue((System.nanoTime() - t0) / 1000000 < 100, "start returns at once");
        assertNotNull(p.parking());
        p.stop();
    }

    @Test
    void bridgesAttachOnceServicesAppearAndPublishOffTheStockThread() throws Exception {
        Ctx c = new Ctx();
        Recorder parking = new Recorder();
        Recorder power = new Recorder();
        c.services.put(POWER, Fakes.fake(DSIPowerManagement.class, power));
        HqcPlatform p = new HqcPlatform(c.context());
        RecordingHost host = new RecordingHost();
        p.start(host);
        assertThrows(IllegalStateException.class, () -> p.parking().apply(new SoundDirections(true, true, true, true)),
                "parking service not there yet");
        Thread.sleep(250);
        String m = marker();
        c.services.put(PARKING, Fakes.fake(DSICarParkingSystem.class, parking));
        assertTrue(eventually(3000, () -> parking.named("setNotification").size() == 1));
        assertTrue(eventually(3000, () -> countSince(m, " STATUS mibsdk seam configurator=") == 1),
                "the seam is logged again once the parking service is there: " + SdkGlobals.LOG.snapshot());
        assertTrue(eventually(3000, () -> power.named("setNotification").size() == 1));
        for (String t : c.lookupThreads) {
            assertTrue(t.startsWith("mibsdk-wait"), t);
        }

        DSICarParkingSystemListener pl = (DSICarParkingSystemListener) parking.named("setNotification").get(0).args[0];
        DSIPowerManagementListener il = (DSIPowerManagementListener) power.named("setNotification").get(0).args[0];
        il.updateClampSignal(new ClampSignal(true, true, true, false), VALID);
        pl.updatePDCSoundReproduction(new PDCSoundReproduction(true, true, false, false), VALID);
        assertTrue(eventually(2000, () -> host.snapshot().size() == 2));
        assertTrue(((IgnitionChanged) host.snapshot().get(0)).isOn());
        assertEquals(new SoundDirections(true, true, false, false),
                ((ParkingSoundChanged) host.snapshot().get(1)).value());
        for (String t : host.threads) {
            assertEquals("mibsdk-hqc-events", t);
        }

        SoundDirections v = new SoundDirections(false, true, true, true);
        p.parking().apply(v);
        assertEquals(v, p.parking().current());
        assertEquals(1, parking.named("setPDCSoundReproduction").size());

        p.stop();
        assertEquals(1, parking.named("clearNotification").size());
        assertEquals(1, power.named("clearNotification").size());
        assertThrows(IllegalStateException.class, () -> p.parking().apply(v));
    }

    @Test
    void lateParkingServiceAnnouncesFeatureChange() throws Exception {
        Ctx c = new Ctx();
        Recorder parking = new Recorder();
        c.services.put(POWER, Fakes.fake(DSIPowerManagement.class, new Recorder()));
        HqcPlatform p = new HqcPlatform(c.context());
        RecordingHost host = new RecordingHost();
        p.start(host);
        try {
            Thread.sleep(250);
            assertEquals(0, host.featuresChanged.get(), "nothing learned yet");
            c.services.put(PARKING, Fakes.fake(DSICarParkingSystem.class, parking));
            assertTrue(eventually(3000, () -> host.featuresChanged.get() == 1));
            assertEquals(1, parking.named("setNotification").size(), "announced after the bridge attached");
            Thread.sleep(300);
            assertEquals(1, host.featuresChanged.get(), "once");
        } finally {
            p.stop();
        }
    }

    @Test
    void stopEndsTheServiceWait() throws Exception {
        Ctx c = new Ctx();
        HqcPlatform p = new HqcPlatform(c.context());
        p.start(new RecordingHost());
        assertTrue(eventually(2000, () -> c.lookups.get() >= 2));
        p.stop();
        Thread.sleep(300); // let an in-flight retry finish
        int n = c.lookups.get();
        Thread.sleep(500);
        assertEquals(n, c.lookups.get(), "no polling after stop");
        assertDoesNotThrow(p::stop, "idempotent");
    }

    // ---- bundle activator ----

    private File baseDir() throws IOException {
        File base = new File(dir, "mibsdk");
        assertTrue(base.mkdirs());
        try (FileOutputStream out = new FileOutputStream(new File(base, "sdk.properties"))) {
            String props = "log.file=" + new File(dir, "mibsdk.log").getAbsolutePath().replace('\\', '/') + "\n"
                    + "plugin.start.delay.ms=600000\n" + "dev.poll.ms=60000\n" + "tick.ms=50\n";
            out.write(props.getBytes(StandardCharsets.ISO_8859_1));
        }
        return base;
    }

    private static String marker() {
        String m = "test-marker-" + System.nanoTime();
        SdkGlobals.LOG.append("INFO", "test", m);
        return m;
    }

    /** SdkGlobals.LOG lines after the marker that contain {@code text}. */
    private static int countSince(String marker, String text) {
        List<String> lines = SdkGlobals.LOG.snapshot();
        int n = 0;
        boolean seen = false;
        for (String l : lines) {
            if (l.indexOf(marker) >= 0) {
                seen = true;
            } else if (seen && l.indexOf(text) >= 0) {
                n++;
            }
        }
        return n;
    }

    @Test
    void activatorStartReturnsQuickly() throws Exception {
        String m = marker();
        Ctx c = new Ctx();
        c.blockMs = 5000;
        SdkBundleActivator a = new SdkBundleActivator(baseDir(), new File(dir, "persist"), new File(dir, "ram"),
                versionFile("HMI = " + HqcVersion.SUPPORTED));
        long t0 = System.nanoTime();
        a.start(c.context());
        long ms = (System.nanoTime() - t0) / 1000000;
        assertTrue(ms < 100, "start took " + ms + " ms");
        assertTrue(eventually(5000, () -> countSince(m, "runtime started adapter=hqc-nar-g22-c10") == 1),
                SdkGlobals.LOG.snapshot().toString());
        assertTrue(eventually(2000, () -> c.lookups.get() >= 1));
        assertTrue(c.lookupThreads.get(0).startsWith("mibsdk-wait"), c.lookupThreads.get(0));

        t0 = System.nanoTime();
        a.stop(c.context()); // the waiter is still stuck in a 5 s lookup
        ms = (System.nanoTime() - t0) / 1000000;
        assertTrue(ms <= SdkBundleActivator.STOP_WAIT_MS + 500, "stop took " + ms + " ms");
        assertTrue(eventually(5000, () -> countSince(m, "runtime stopped") == 1));
    }

    @Test
    void activatorStartIsBootedOnMibsdkMain() throws Exception {
        String m = marker();
        Ctx c = new Ctx();
        SdkBundleActivator a = new SdkBundleActivator(baseDir(), new File(dir, "persist"), new File(dir, "ram"), versionFile("HMI = " + HqcVersion.SUPPORTED));
        a.start(c.context());
        assertTrue(eventually(5000, () -> countSince(m, "runtime started") == 1));
        assertEquals(1, countSince(m, " STATUS mibsdk seam configurator="), SdkGlobals.LOG.snapshot().toString());
        Thread main = a.mainThread();
        assertNotNull(main);
        assertEquals("mibsdk-main", main.getName());
        assertTrue(main.isDaemon());
        assertEquals(Thread.NORM_PRIORITY - 1, main.getPriority());
        a.stop(c.context());
    }

    @Test
    void unsupportedFirmwareStartsNoAdapter() throws Exception {
        String m = marker();
        Ctx c = new Ctx();
        SdkBundleActivator a = new SdkBundleActivator(baseDir(), new File(dir, "persist"), new File(dir, "ram"), versionFile("HMI = HMI_SOMETHING_ELSE"));
        a.start(c.context());
        assertTrue(eventually(5000, () -> countSince(m, "runtime started adapter=none") == 1));
        Thread.sleep(300);
        assertEquals(0, c.lookups.get(), "no service is looked up for an unsupported firmware");
        assertDoesNotThrow(() -> a.stop(c.context()));
    }

    @Test
    void stopRightAfterStartLeavesNothingRunning() throws Exception {
        String m = marker();
        Ctx c = new Ctx();
        SdkBundleActivator a = new SdkBundleActivator(baseDir(), new File(dir, "persist"), new File(dir, "ram"), versionFile("HMI = " + HqcVersion.SUPPORTED));
        a.start(c.context());
        a.stop(c.context());
        Thread main = a.mainThread();
        if (main != null) {
            main.join(5000);
            assertFalse(main.isAlive());
        }
        Thread.sleep(300);
        assertEquals(countSince(m, "runtime started"), countSince(m, "runtime stopped"),
                SdkGlobals.LOG.snapshot().toString());
        int n = c.lookups.get();
        Thread.sleep(500);
        assertEquals(n, c.lookups.get(), "no service polling after stop");
    }

    @Test
    void activatorNeverThrows() throws Exception {
        // A null context with the supported firmware: the platform's lookups fail (and are retried) on its threads.
        SdkBundleActivator a = new SdkBundleActivator(baseDir(), new File(dir, "persist"), new File(dir, "ram"), versionFile("HMI = " + HqcVersion.SUPPORTED));
        assertDoesNotThrow(() -> a.stop(null), "stop before start");
        assertDoesNotThrow(() -> a.start(null));
        assertDoesNotThrow(() -> a.start(null), "second start is ignored");
        assertDoesNotThrow(() -> a.stop(null));
        assertDoesNotThrow(() -> new SdkBundleActivator(), "LSD's no-arg constructor");
    }
}
