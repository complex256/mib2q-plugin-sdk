package mibsdk.adapter.hqc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.dreisoft.lsd.IServiceDelegateConfigurator;
import de.dreisoft.lsd.IServiceDelegator;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;
import mibsdk.adapter.hqc.Fakes.Call;
import mibsdk.adapter.hqc.Fakes.Recorder;
import org.dsi.ifc.carparkingsystem.DSICarParkingSystem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.osgi.framework.ServiceRegistration;

class ChainingConfiguratorTest {

    private static final String PARKING = "org.dsi.ifc.carparkingsystem.DSICarParkingSystem";
    private static final String CHAIN = RecordingChain.class.getName();

    /** Stands in for TraceConfigurator; LSD-style public no-arg class. Static state: tests run sequentially. */
    public static final class RecordingChain implements IServiceDelegateConfigurator {
        static volatile boolean throwOnInit;
        static volatile boolean throwOnRegister;
        static volatile ServiceRegistration answer;
        static volatile int inits;
        static volatile String[] lastNames;
        static volatile Object lastService;
        static volatile Dictionary lastProps;
        static volatile int registrations;

        static void reset() {
            throwOnInit = false;
            throwOnRegister = false;
            answer = null;
            inits = 0;
            lastNames = null;
            lastService = null;
            lastProps = null;
            registrations = 0;
        }

        public RecordingChain() {
        }

        @Override
        public void initConfigurator() {
            inits++;
            if (throwOnInit) {
                throw new IllegalStateException("Trace deactivated.");
            }
        }

        @Override
        public ServiceRegistration getServiceRegistration(IServiceDelegator d, String[] names, Object service,
                Dictionary props) {
            registrations++;
            lastNames = names;
            lastService = service;
            lastProps = props;
            if (throwOnRegister) {
                throw new IllegalStateException("chain bug");
            }
            return answer;
        }
    }

    @TempDir
    File base;

    private File versionInfo;
    private Recorder delegator;
    private IServiceDelegator d;
    private ServiceRegistration registration;
    private DSICarParkingSystem service;

    @BeforeEach
    void setUp() throws IOException {
        RecordingChain.reset();
        versionInfo = new File(base, "version_info.txt");
        writeVersion(HqcVersion.SUPPORTED);
        registration = Fakes.fake(ServiceRegistration.class, new Recorder());
        delegator = new Recorder();
        delegator.answer = registration;
        d = Fakes.fake(IServiceDelegator.class, delegator);
        service = Fakes.fake(DSICarParkingSystem.class, new Recorder());
    }

    private void writeVersion(String hmi) throws IOException {
        write(versionInfo, "Train = MHI2Q\nHMI = " + hmi + "\nFramework = 5.51.6\n");
    }

    private static void write(File f, String s) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(s.getBytes(StandardCharsets.ISO_8859_1));
        }
    }

    private void sdkProperties(String s) throws IOException {
        write(new File(base, "sdk.properties"), s);
    }

    private ChainingConfigurator init() {
        ChainingConfigurator c = new ChainingConfigurator(base, versionInfo);
        c.initConfigurator();
        return c;
    }

    private static Hashtable<String, Object> props() {
        Hashtable<String, Object> p = new Hashtable<>();
        p.put("service.pid", "parking");
        return p;
    }

    private static boolean logContains(String s) {
        List<String> lines = SdkGlobals.LOG.snapshot();
        for (String line : lines) {
            if (line.indexOf(s) >= 0) {
                return true;
            }
        }
        return false;
    }

    @Test
    void wrapsAllowListedAndChains() throws IOException {
        sdkProperties("chain.configurator=" + CHAIN + "\n");
        ChainingConfigurator c = init();
        assertTrue(SdkGlobals.wrappingEnabled);
        assertEquals(1, RecordingChain.inits);

        Hashtable<String, Object> props = props();
        String[] names = {PARKING};
        ServiceRegistration r = c.getServiceRegistration(d, names, service, props);

        Object chained = RecordingChain.lastService;
        assertNotSame(service, chained, "chain got the wrapped object");
        assertTrue(Proxy.isProxyClass(chained.getClass()));
        assertTrue(chained instanceof DSICarParkingSystem);
        assertEquals("true", RecordingChain.lastProps.get("mibsdk.proxy"));
        assertEquals("parking", RecordingChain.lastProps.get("service.pid"));
        assertSame(names, RecordingChain.lastNames);
        assertNull(props.get("mibsdk.proxy"), "caller's props are not modified");

        // The chain returned null: we register the wrapped object ourselves (null would register the original).
        assertSame(registration, r);
        Call created = delegator.calls.get(0);
        assertSame(names, created.args[0]);
        assertSame(chained, created.args[1]);
        assertSame(RecordingChain.lastProps, created.args[2]);
        assertTrue(logContains("wrapped " + PARKING + " chain=ChainingConfiguratorTest$RecordingChain"));

        // A chain answer wins.
        ServiceRegistration chainReg = Fakes.fake(ServiceRegistration.class, new Recorder());
        RecordingChain.answer = chainReg;
        assertSame(chainReg, c.getServiceRegistration(d, names, service, props()));
        assertEquals(1, delegator.calls.size());
    }

    @Test
    void wrapsWithoutChain() {
        ChainingConfigurator c = init();
        String[] names = {"some.Other", PARKING};
        ServiceRegistration r = c.getServiceRegistration(d, names, service, props());
        assertSame(registration, r);
        Call created = delegator.calls.get(0);
        assertSame(names, created.args[0]);
        assertTrue(Proxy.isProxyClass(created.args[1].getClass()));
        assertEquals("true", ((Dictionary) created.args[2]).get("mibsdk.proxy"));
        assertTrue(logContains("wrapped " + PARKING + " chain=none"));

        // null props: still a copy with the marker.
        delegator.calls.clear();
        c.getServiceRegistration(d, names, service, null);
        assertEquals("true", ((Dictionary) delegator.calls.get(0).args[2]).get("mibsdk.proxy"));
    }

    @Test
    void notAllowListedPassesThroughUntouched() throws IOException {
        sdkProperties("chain.configurator=" + CHAIN + "\n");
        ChainingConfigurator c = init();
        Hashtable<String, Object> props = props();
        Object other = new Object();
        String[] names = {"org.dsi.ifc.media.DSIMediaPlayer"};
        assertNull(c.getServiceRegistration(d, names, other, props));
        assertSame(other, RecordingChain.lastService);
        assertSame(props, RecordingChain.lastProps);
        assertTrue(delegator.calls.isEmpty());

        RecordingChain.reset();
        ChainingConfigurator noChain = new ChainingConfigurator(base, versionInfo);
        sdkProperties("");
        noChain.initConfigurator();
        assertNull(noChain.getServiceRegistration(d, names, other, props));
        assertEquals(0, RecordingChain.registrations);
    }

    @Test
    void hookServicesComeFromSdkProperties() throws IOException {
        sdkProperties("hook.services=org.dsi.ifc.Foo\n");
        ChainingConfigurator c = init();
        assertNull(c.getServiceRegistration(d, new String[] {PARKING}, service, props()));
        assertTrue(delegator.calls.isEmpty());
    }

    @Test
    void alreadyMarkedOrTraceDelegatesAreNotWrapped() {
        ChainingConfigurator c = init();
        Hashtable<String, Object> marked = props();
        marked.put("mibsdk.proxy", "true");
        assertNull(c.getServiceRegistration(d, new String[] {PARKING}, service, marked));
        Hashtable<String, Object> traced = props();
        traced.put("service.delegate", "delegated." + PARKING);
        assertNull(c.getServiceRegistration(d, new String[] {PARKING}, service, traced));
        assertTrue(delegator.calls.isEmpty());
    }

    @Test
    void unwrappableServicePassesThroughAndIsLogged() throws IOException {
        sdkProperties("chain.configurator=" + CHAIN + "\n");
        ChainingConfigurator c = init();
        Object noInterfaces = new Object();
        Hashtable<String, Object> props = props();
        assertNull(c.getServiceRegistration(d, new String[] {PARKING}, noInterfaces, props));
        assertSame(noInterfaces, RecordingChain.lastService);
        assertSame(props, RecordingChain.lastProps);
        assertTrue(delegator.calls.isEmpty());
        assertTrue(logContains("not wrapped " + PARKING));
    }

    @Test
    void passthroughWhenVersionMismatch() throws IOException {
        sdkProperties("chain.configurator=" + CHAIN + "\n");
        writeVersion("HMI_AU_MIB2_HQC_NAR_G22_C10_180804");
        ChainingConfigurator c = init();
        assertFalse(SdkGlobals.wrappingEnabled);
        Hashtable<String, Object> props = props();
        assertNull(c.getServiceRegistration(d, new String[] {PARKING}, service, props));
        assertSame(service, RecordingChain.lastService, "the chain still runs, with the original");
        assertSame(props, RecordingChain.lastProps);
        assertTrue(delegator.calls.isEmpty());

        versionInfo.delete();
        ChainingConfigurator missing = init();
        assertFalse(SdkGlobals.wrappingEnabled);
        assertNull(missing.getServiceRegistration(d, new String[] {PARKING}, service, props()));
        assertTrue(delegator.calls.isEmpty());
    }

    @Test
    void passthroughWhenSafeModeMarker() throws IOException {
        write(new File(base, "SAFE_MODE"), "");
        ChainingConfigurator c = init();
        assertFalse(SdkGlobals.wrappingEnabled);
        assertNull(c.getServiceRegistration(d, new String[] {PARKING}, service, props()));
        assertTrue(delegator.calls.isEmpty());
    }

    @Test
    void passthroughWhenBootGuardThresholdReached() throws IOException {
        // BootGuard's predicate with the configured threshold, not a hard-coded 3.
        sdkProperties("safe.mode.after.unhealthy.boots=2\n");
        write(new File(base, "boot.state"), "unhealthyBoots=2\nlastShutdownClean=true\n");
        init();
        assertFalse(SdkGlobals.wrappingEnabled);

        write(new File(base, "boot.state"), "unhealthyBoots=1\nlastShutdownClean=true\n");
        init();
        assertTrue(SdkGlobals.wrappingEnabled);
    }

    @Test
    void chainThatThrowsOnInitIsDropped() throws IOException {
        sdkProperties("chain.configurator=" + CHAIN + "\n");
        RecordingChain.throwOnInit = true;
        ChainingConfigurator c = init();
        assertEquals(1, RecordingChain.inits);
        assertTrue(logContains("Trace deactivated."));

        assertNull(c.getServiceRegistration(d, new String[] {"x.Other"}, new Object(), props()));
        assertEquals(0, RecordingChain.registrations, "dropped chain is never called");
        // Wrapping still works without the chain.
        assertSame(registration, c.getServiceRegistration(d, new String[] {PARKING}, service, props()));
        assertEquals(0, RecordingChain.registrations);
        assertTrue(logContains("chain=none"));
    }

    @Test
    void unloadableOrSelfChainIsDropped() throws IOException {
        sdkProperties("chain.configurator=no.such.Configurator\n");
        ChainingConfigurator c = init();
        assertNull(c.getServiceRegistration(d, new String[] {"x.Other"}, new Object(), props()));

        sdkProperties("chain.configurator=" + ChainingConfigurator.class.getName() + "\n");
        ChainingConfigurator self = init();
        assertNull(self.getServiceRegistration(d, new String[] {"x.Other"}, new Object(), props()));

        sdkProperties("chain.configurator=java.lang.Object\n");
        ChainingConfigurator notOne = init();
        assertNull(notOne.getServiceRegistration(d, new String[] {"x.Other"}, new Object(), props()));
    }

    @Test
    void neverThrows() throws IOException {
        sdkProperties("chain.configurator=" + CHAIN + "\n");
        ChainingConfigurator c = init();
        assertDoesNotThrow(() -> c.getServiceRegistration(d, null, null, null));
        assertDoesNotThrow(() -> c.getServiceRegistration(d, new String[] {PARKING}, null, null));
        assertDoesNotThrow(() -> c.getServiceRegistration(null, new String[] {PARKING}, service, props()));
        assertDoesNotThrow(() -> c.getServiceRegistration(d, new String[] {null}, service, props()));

        // Chain throws: we retry it with the original object, then give up with null (stock registration).
        RecordingChain.throwOnRegister = true;
        assertNull(c.getServiceRegistration(d, new String[] {PARKING}, service, props()));
        assertSame(service, RecordingChain.lastService);

        // Delegator throws after a wrap: falls back to the chain with the original.
        RecordingChain.throwOnRegister = false;
        delegator.toThrow = new RuntimeException("LSD bug");
        assertNull(c.getServiceRegistration(d, new String[] {PARKING}, service, props()));
        assertSame(service, RecordingChain.lastService);

        ChainingConfigurator noChain = new ChainingConfigurator(base, versionInfo);
        sdkProperties("");
        noChain.initConfigurator();
        assertNull(noChain.getServiceRegistration(d, new String[] {PARKING}, service, props()));
        assertDoesNotThrow(() -> new ChainingConfigurator(null, null).initConfigurator());
        assertDoesNotThrow(() -> new ChainingConfigurator(null, null)
                .getServiceRegistration(d, new String[] {PARKING}, service, props()));
    }

    @Test
    void lsdConstructorUsesUnitPaths() {
        // On the host the unit's version file is absent: the configurator stays a pass-through.
        ChainingConfigurator c = new ChainingConfigurator();
        c.initConfigurator();
        assertFalse(SdkGlobals.wrappingEnabled);
        assertNull(c.getServiceRegistration(d, new String[] {PARKING}, service, props()));
    }
}
