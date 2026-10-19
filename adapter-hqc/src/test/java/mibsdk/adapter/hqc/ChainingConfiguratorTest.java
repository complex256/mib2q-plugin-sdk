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
        RawServices.clear();
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

    private static String lastLog() {
        List<String> lines = SdkGlobals.LOG.snapshot();
        return lines.get(lines.size() - 1);
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

    /** Package-private: Method.invoke through it from this adapter would fail on every call. */
    interface Hidden {
        void ping();
    }

    /** A stock DSITracer trace proxy (the real abstract base from the host stock jar). */
    static final class TraceProxy extends de.audi.tghu.dsitrace.AbstractTraceProxy implements Runnable {
        TraceProxy(Object dest) {
            super(null, 1, dest, 0);
        }

        @Override
        public void run() {
        }

        Object yyDestServiceForTest() {
            return yyDestService;
        }
    }

    /** Shaped like stock TraceConfigurator.getServiceRegistration (see the bytecode notes in the task report). */
    public static final class TraceShapedChain implements IServiceDelegateConfigurator {
        static volatile ChainingConfigurator reenter;
        static volatile Object traceProxy;

        public TraceShapedChain() {
        }

        @Override
        public void initConfigurator() {
        }

        @Override
        public ServiceRegistration getServiceRegistration(IServiceDelegator d, String[] names, Object service,
                Dictionary props) {
            if (props != null && props.get("service.delegate") != null) {
                return null;
            }
            if (service instanceof de.audi.tghu.dsitrace.AbstractTraceProxy) {
                return null;
            }
            if (names.length != 1 || !names[0].startsWith("org.dsi.ifc.")) {
                return null;
            }
            ServiceRegistration r = d.createServiceRegistration("delegated." + names[0], service, props);
            // DSITracer then registers its trace proxy (over delegated.X) under the real name; LSD routes that
            // registration through the configurator again.
            Hashtable<String, Object> traced = new Hashtable<>();
            for (java.util.Enumeration<?> e = props.keys(); e.hasMoreElements();) {
                Object k = e.nextElement();
                traced.put((String) k, props.get(k));
            }
            traced.put("service.delegate", "delegated." + names[0]);
            Object proxy = new TraceProxy(service);
            traceProxy = proxy;
            ServiceRegistration again = reenter.getServiceRegistration(d, names, proxy, traced);
            return again != null ? again : r;
        }
    }

    @Test
    void traceProxyServicesAreNotWrapped() {
        ChainingConfigurator c = init();
        TraceProxy traceProxy = new TraceProxy(service);
        // Wrappable (it has a public interface), but a trace proxy: passed through, even without service.delegate.
        assertTrue(DsiServiceProxy.wrap(traceProxy, new Class<?>[] {Runnable.class}, SdkGlobals.HOOKS,
                HookBindings.defaults()) != traceProxy);
        assertNull(c.getServiceRegistration(d, new String[] {PARKING}, traceProxy, props()));
        assertTrue(delegator.calls.isEmpty());
    }

    @Test
    void traceShapedChainReentryIsNotWrappedTwice() throws IOException {
        sdkProperties("chain.configurator=" + TraceShapedChain.class.getName() + "\n");
        ChainingConfigurator c = init();
        TraceShapedChain.reenter = c;
        Hashtable<String, Object> props = props();
        assertSame(registration, c.getServiceRegistration(d, new String[] {PARKING}, service, props));

        // Exactly one registration with the delegator: delegated.X with our proxy over the original service.
        assertEquals(1, delegator.calls.size());
        Call delegated = delegator.calls.get(0);
        assertEquals("delegated." + PARKING, delegated.args[0]);
        Object ours = delegated.args[1];
        assertTrue(Proxy.isProxyClass(ours.getClass()));
        assertTrue(ours instanceof DSICarParkingSystem);
        assertEquals("true", ((Dictionary) delegated.args[2]).get("mibsdk.proxy"));
        // The trace proxy (registered by LSD itself, since the re-entry returned null) wraps our proxy, unwrapped.
        assertSame(ours, ((TraceProxy) TraceShapedChain.traceProxy).yyDestServiceForTest());
        // The raw service is recorded for the SDK's bridges, which bypass both the trace proxy and ours.
        assertSame(service, RawServices.get(PARKING));
    }

    @Test
    void serviceWithNonPublicInterfaceIsNotWrapped() throws IOException {
        sdkProperties("chain.configurator=" + CHAIN + "\n");
        ChainingConfigurator c = init();
        Object hidden = Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {DSICarParkingSystem.class, Hidden.class}, new Recorder());
        Hashtable<String, Object> props = props();
        assertNull(c.getServiceRegistration(d, new String[] {PARKING}, hidden, props));
        assertSame(hidden, RecordingChain.lastService);
        assertSame(props, RecordingChain.lastProps);
        assertTrue(delegator.calls.isEmpty());
        assertTrue(logContains("non-public interface " + Hidden.class.getName()));
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
        assertNull(RawServices.get(PARKING), "nothing wrapped, nothing recorded");

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

    /** Crashes after wrapping but before plugins load are invisible to unhealthyBoots; wrapBoots catches them. */
    @Test
    void wrapLoopPassesEverythingThrough() throws IOException {
        sdkProperties("safe.mode.after.unhealthy.boots=2\n");
        File state = new File(base, "boot.state");
        init();
        assertTrue(SdkGlobals.wrappingEnabled);
        assertTrue(text(state).contains("wrapBoots=1\n"), text(state));
        init(); // the HMI died before the runtime could mark anything
        assertTrue(SdkGlobals.wrappingEnabled);
        assertTrue(text(state).contains("wrapBoots=2\n"), text(state));

        ChainingConfigurator c = init();
        assertFalse(SdkGlobals.wrappingEnabled);
        assertTrue(logContains("wrapping=off reason=wrap-boots"), SdkGlobals.LOG.snapshot().toString());
        assertTrue(text(state).contains("wrapBoots=2\n"), "a pass-through boot is not counted: " + text(state));
        assertNull(c.getServiceRegistration(d, new String[] {PARKING}, service, props()));
        assertTrue(delegator.calls.isEmpty());

        write(state, "unhealthyBoots=0\nlastShutdownClean=true\nwrapBoots=0\n"); // mibsdk-dev safe-mode off
        init();
        assertTrue(SdkGlobals.wrappingEnabled);
    }

    @Test
    void passThroughBootsAreNotCounted() throws IOException {
        write(new File(base, "SAFE_MODE"), "");
        init();
        assertFalse(SdkGlobals.wrappingEnabled);
        assertFalse(new File(base, "boot.state").exists(), "nothing wrapped, nothing counted");
    }

    private static String text(File f) throws IOException {
        return new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.ISO_8859_1);
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

        // Chain throws on the wrapped object: never asked again (it may have registered delegated.X already);
        // the original is registered directly, and no "wrapped" line is logged.
        RecordingChain.throwOnRegister = true;
        RecordingChain.registrations = 0;
        delegator.calls.clear();
        String[] names = {PARKING};
        Hashtable<String, Object> props = props();
        assertSame(registration, c.getServiceRegistration(d, names, service, props));
        assertEquals(1, RecordingChain.registrations, "chain not retried");
        assertTrue(Proxy.isProxyClass(RecordingChain.lastService.getClass()));
        Call original = delegator.calls.get(0);
        assertSame(names, original.args[0]);
        assertSame(service, original.args[1]);
        assertSame(props, original.args[2]);
        assertTrue(lastLog().indexOf("wrap of " + PARKING + " abandoned, chain failed") >= 0, lastLog());

        // ...and if that direct registration throws too: null, LSD registers the original.
        delegator.toThrow = new RuntimeException("LSD bug");
        assertNull(c.getServiceRegistration(d, names, service, props()));

        // Chain declines, our registration of the wrapped object throws: null, chain not asked again.
        RecordingChain.throwOnRegister = false;
        RecordingChain.registrations = 0;
        assertNull(c.getServiceRegistration(d, names, service, props()));
        assertEquals(1, RecordingChain.registrations);
        assertTrue(lastLog().indexOf("wrap of " + PARKING + " abandoned, registration failed") >= 0, lastLog());

        // Pass-through registration whose chain throws: null.
        RecordingChain.throwOnRegister = true;
        assertNull(c.getServiceRegistration(d, new String[] {"x.Other"}, new Object(), props()));
        RecordingChain.throwOnRegister = false;
        delegator.toThrow = null;

        ChainingConfigurator noChain = new ChainingConfigurator(base, versionInfo);
        sdkProperties("");
        noChain.initConfigurator();
        assertSame(registration, noChain.getServiceRegistration(d, new String[] {PARKING}, service, props()));
        delegator.toThrow = new RuntimeException("LSD bug");
        assertNull(noChain.getServiceRegistration(d, new String[] {PARKING}, service, props()), "no chain, d throws");
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
