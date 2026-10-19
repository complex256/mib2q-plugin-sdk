package mibsdk.adapter.hqc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.dreisoft.lsd.IServiceDelegateConfigurator;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.List;
import mibsdk.api.HookPoint;
import mibsdk.platform.HookTable;
import mibsdk.runtime.RingLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Partial install: LSD loads the configurator with only the adapter jar and the stock classes visible. Stock LSD
 * catches only Exception around newInstance()/initConfigurator(), so no Error may escape either.
 */
class ConfiguratorIsolationTest {

    private static final String PARKING = "org.dsi.ifc.carparkingsystem.DSICarParkingSystem";

    @TempDir
    File base;

    private File versionInfo;

    @BeforeEach
    void setUp() throws IOException {
        versionInfo = new File(base, "version_info.txt");
        try (FileOutputStream out = new FileOutputStream(versionInfo)) {
            out.write(("HMI = " + HqcVersion.SUPPORTED + "\n").getBytes(StandardCharsets.ISO_8859_1));
        }
    }

    private static URL locationOf(Class<?> c) {
        return c.getProtectionDomain().getCodeSource().getLocation();
    }

    /** Adapter classes + host stock jar (+ the given SDK modules), nothing from the test class path. */
    private static URLClassLoader isolated(Class<?>... sdkModules) {
        List<URL> urls = new ArrayList<>();
        urls.add(locationOf(ChainingConfigurator.class));
        urls.add(locationOf(IServiceDelegateConfigurator.class));
        for (Class<?> c : sdkModules) {
            urls.add(locationOf(c));
        }
        return new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
    }

    private Object construct(ClassLoader l) throws Exception {
        Class<?> cls = Class.forName(ChainingConfigurator.class.getName(), true, l);
        assertDoesNotThrow(() -> cls.getConstructor().newInstance(), "LSD's no-arg constructor");
        Constructor<?> ctor = cls.getDeclaredConstructor(File.class, File.class);
        ctor.setAccessible(true);
        return ctor.newInstance(base, versionInfo);
    }

    private static Object register(Object configurator, Object delegator, Object service) throws Exception {
        ClassLoader l = configurator.getClass().getClassLoader();
        Method m = configurator.getClass().getMethod("getServiceRegistration",
                Class.forName("de.dreisoft.lsd.IServiceDelegator", false, l), String[].class, Object.class,
                Dictionary.class);
        return m.invoke(configurator, delegator, new String[] {PARKING}, service, null);
    }

    /** A Proxy of {@code iface} as loaded by {@code l}; answers {@code answer} to every non-Object call. */
    private static Object fake(ClassLoader l, String iface, Object answer) throws Exception {
        InvocationHandler h = (proxy, m, args) -> {
            if (m.getDeclaringClass() == Object.class) {
                return m.getName().equals("equals") ? proxy == args[0]
                        : m.getName().equals("hashCode") ? (Object) System.identityHashCode(proxy) : "fake";
            }
            return answer;
        };
        return Proxy.newProxyInstance(l, new Class<?>[] {Class.forName(iface, false, l)}, h);
    }

    @Test
    void sdkClassesMissingNothingEscapesAndEverythingPassesThrough() throws Exception {
        try (URLClassLoader l = isolated()) {
            assertThrows(ClassNotFoundException.class, () -> l.loadClass(HookTable.class.getName()));
            assertThrows(ClassNotFoundException.class, () -> l.loadClass(RingLog.class.getName()));
            Object c = construct(l);
            assertDoesNotThrow(() -> c.getClass().getMethod("initConfigurator").invoke(c));
            Object registration = fake(l, "org.osgi.framework.ServiceRegistration", null);
            Object d = fake(l, "de.dreisoft.lsd.IServiceDelegator", registration);
            Object service = fake(l, PARKING, null);
            assertNull(register(c, d, service), "pure pass-through: LSD registers the original");
        }
    }

    @Test
    void activatorWithSdkClassesMissingNeverThrows() throws Exception {
        try (URLClassLoader l = isolated()) {
            assertThrows(ClassNotFoundException.class, () -> l.loadClass(RingLog.class.getName()));
            Class<?> cls = Class.forName(SdkBundleActivator.class.getName(), true, l);
            assertDoesNotThrow(() -> cls.getConstructor().newInstance(), "the kernel's no-arg constructor");
            Constructor<?> ctor = cls.getDeclaredConstructor(File.class, File.class);
            ctor.setAccessible(true);
            Object a = ctor.newInstance(base, versionInfo);
            Class<?> bc = Class.forName("org.osgi.framework.BundleContext", false, l);
            Object ctx = fake(l, "org.osgi.framework.BundleContext", null);
            long t0 = System.nanoTime();
            assertDoesNotThrow(() -> cls.getMethod("start", bc).invoke(a, ctx));
            long ms = (System.nanoTime() - t0) / 1000000;
            assertTrue(ms < 50, "start took " + ms + " ms");
            Method mainThread = cls.getDeclaredMethod("mainThread");
            mainThread.setAccessible(true);
            Thread main = (Thread) mainThread.invoke(a);
            main.join(5000);
            assertFalse(main.isAlive(), "the boot failed on mibsdk-main, caught there");
            assertDoesNotThrow(() -> cls.getMethod("stop", bc).invoke(a, ctx));
        }
    }

    @Test
    void sdkClassesPresentInTheSameSetupWraps() throws Exception {
        // Positive control: the same isolated setup plus the SDK modules does wrap, so the test above is not vacuous.
        try (URLClassLoader l = isolated(HookTable.class, RingLog.class, HookPoint.class)) {
            Object c = construct(l);
            c.getClass().getMethod("initConfigurator").invoke(c);
            Object registration = fake(l, "org.osgi.framework.ServiceRegistration", null);
            Object d = fake(l, "de.dreisoft.lsd.IServiceDelegator", registration);
            Object service = fake(l, PARKING, null);
            Object r = register(c, d, service);
            assertNotNull(r);
            assertSame(registration, r);
        }
    }
}
