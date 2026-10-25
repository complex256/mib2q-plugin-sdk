package mibsdk.adapter.hqc.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.audi.atip.base.IFrameworkAccess;
import de.audi.tghu.system.hmi.evohigh.SystemScreenFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import mibsdk.adapter.hqc.SdkGlobals;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The {@code HMISystem=} activator on the host, over the repaired stock classes. It gives exactly the stock factory in
 * swdl, with the UI off, under the forced failure "factory" and when the SDK factory cannot be built; the SDK factory
 * (and {@code mainMenu = PENDING}) otherwise. Static SdkGlobals and system properties: sequential.
 */
class SdkSystemActivatorTest {

    @BeforeEach
    void reset() {
        cleanUp();
        SdkGlobals.uiEnabled = true; // the takeover may run unless a test knocks one condition out
    }

    @AfterEach
    void cleanUp() {
        System.clearProperty("RUN_MODE");
        SdkGlobals.uiEnabled = false;
        SdkGlobals.uiForceFail = null;
        SdkGlobals.mainMenu = SdkGlobals.MAIN_MENU_OFF;
        SdkGlobals.mainMenuRow = null;
        SdkGlobals.mainMenuWizard = null;
    }

    /** A framework whose every call answers a default: a fake for interfaces, English, "" for strings, 0 / false. */
    static Object deep(ClassLoader l, Class<?> iface) {
        return Proxy.newProxyInstance(l, new Class<?>[] {iface}, (p, m, a) -> {
            Class<?> r = m.getReturnType();
            if (m.getName().equals("hashCode")) {
                return System.identityHashCode(p);
            } else if (m.getName().equals("equals")) {
                return p == a[0];
            } else if (r.getName().equals("de.audi.atip.i18n.Language")) { // as loaded by l
                return r.getConstructor(int.class, String.class, String.class, String.class, int.class,
                        java.util.Locale.class).newInstance(0, "en", "en", "English", 0, java.util.Locale.ENGLISH);
            } else if (r == String.class) {
                return "";
            } else if (r.isInterface()) {
                return deep(l, r);
            } else if (r == boolean.class) {
                return false;
            } else if (r == int.class) {
                return 0;
            } else if (r == long.class) {
                return 0L;
            }
            return null;
        });
    }

    /** The framework the OSGi start would have set ({@code AbstractActivator.framework}). */
    private static void giveFramework(Object activator, ClassLoader l) throws Exception {
        Class<?> base = Class.forName("de.audi.atip.activator.AbstractActivator", false, l);
        Field f = base.getDeclaredField("framework");
        f.setAccessible(true);
        f.set(activator, deep(l, Class.forName(IFrameworkAccess.class.getName(), false, l)));
    }

    private static SdkSystemActivator activator() throws Exception {
        SdkSystemActivator a = new SdkSystemActivator();
        giveFramework(a, SdkSystemActivatorTest.class.getClassLoader());
        return a;
    }

    private static List<String> statusLines(String containing) {
        List<String> hits = new ArrayList<String>();
        for (String l : SdkGlobals.LOG.snapshot()) {
            if (l.contains(containing)) {
                hits.add(l);
            }
        }
        return hits;
    }

    @Test
    void swdlUiOffForcedOrBrokenFactoryAllGiveExactlyTheStockFactory() throws Exception {
        Runnable[] setups = {
            () -> System.setProperty("RUN_MODE", "swdl"),
            () -> SdkGlobals.uiEnabled = false,
            () -> SdkGlobals.uiForceFail = new String("factory"), // compared with equals, not ==
        };
        String[] reasons = {"swdl", "ui-off", "forced"};
        for (int i = 0; i < setups.length; i++) {
            reset();
            setups[i].run();
            int before = statusLines("ui system=stock reason=" + reasons[i]).size();
            SdkSystemActivator a = activator();
            assertEquals(SystemScreenFactory.class, a.factoryForTests().getClass(), reasons[i]);
            assertEquals(SdkGlobals.MAIN_MENU_OFF, SdkGlobals.mainMenu, reasons[i]);
            assertTrue(SdkGlobals.drawerPluginsRowWanted(), reasons[i]);
            assertEquals(before + 1, statusLines("ui system=stock reason=" + reasons[i]).size(), reasons[i]);
            assertSame(a.factoryForTests(), a.factoryForTests(), "chosen once");
            assertEquals(before + 1, statusLines("ui system=stock reason=" + reasons[i]).size(), "logged once");
        }
    }

    @Test
    void sdkFactoryThatCannotBeBuiltGivesTheStockFactory() throws Exception {
        // The first getHMIService call (the SDK factory's SystemScreenFactory constructor) throws; the stock fallback's
        // works.
        ClassLoader l = SdkSystemActivatorTest.class.getClassLoader();
        Object good = deep(l, IFrameworkAccess.class);
        int[] calls = {0};
        Object flaky = Proxy.newProxyInstance(l, new Class<?>[] {IFrameworkAccess.class}, (p, m, a) -> {
            if (m.getName().equals("getHMIService") && calls[0]++ == 0) {
                throw new IllegalStateException("first build fails");
            }
            return m.invoke(good, a);
        });
        SdkSystemActivator act = new SdkSystemActivator();
        Field f = Class.forName("de.audi.atip.activator.AbstractActivator").getDeclaredField("framework");
        f.setAccessible(true);
        f.set(act, flaky);
        int before = statusLines("ui system=stock reason=factory-failed").size();
        assertEquals(SystemScreenFactory.class, act.factoryForTests().getClass());
        assertEquals(2, calls[0], "the SDK factory was tried, then the stock one built");
        assertEquals(before + 1, statusLines("ui system=stock reason=factory-failed").size());
        assertEquals(SdkGlobals.MAIN_MENU_OFF, SdkGlobals.mainMenu);
        assertTrue(SdkGlobals.drawerPluginsRowWanted());
    }

    @Test
    void logsUiSystemSdkOnceWhenTheSdkFactoryIsChosen() throws Exception {
        int before = statusLines("ui system=sdk").size();
        SdkSystemActivator a = activator();
        assertEquals(SdkSystemScreenFactory.class, a.factoryForTests().getClass());
        assertEquals(SdkGlobals.MAIN_MENU_PENDING, SdkGlobals.mainMenu);
        assertTrue(SdkGlobals.drawerPluginsRowWanted(), "PENDING: the Car drawer keeps Plugins until screen 46 has it");
        assertSame(a.factoryForTests(), a.factoryForTests());
        List<String> lines = statusLines("ui system=sdk");
        assertEquals(before + 1, lines.size());
        assertTrue(lines.get(lines.size() - 1).contains("STATUS"), lines.toString());
        assertTrue(a.getConditionBank() != null);
    }

    /**
     * The values Task 7 reads: only ACTIVE hides the Car drawer's Plugins row. A PENDING that never moves on (screen
     * 46 never built through the SDK factory) must not lock the user out of Plugins.
     */
    @Test
    void drawerPluginsRowHiddenOnlyWhileTheMainMenuIsActive() {
        assertEquals(0, SdkGlobals.MAIN_MENU_OFF);
        assertEquals(1, SdkGlobals.MAIN_MENU_PENDING);
        assertEquals(2, SdkGlobals.MAIN_MENU_ACTIVE);
        assertEquals(3, SdkGlobals.MAIN_MENU_FAILED);
        int[] states = {SdkGlobals.MAIN_MENU_OFF, SdkGlobals.MAIN_MENU_PENDING, SdkGlobals.MAIN_MENU_ACTIVE,
            SdkGlobals.MAIN_MENU_FAILED};
        boolean[] wanted = {true, true, false, true};
        for (int i = 0; i < states.length; i++) {
            SdkGlobals.mainMenu = states[i];
            assertEquals(wanted[i], SdkGlobals.drawerPluginsRowWanted(), "state " + states[i]);
        }
    }

    private static URL locationOf(Class<?> c) {
        return c.getProtectionDomain().getCodeSource().getLocation();
    }

    /**
     * {@code HMISystem=} names our class, but the SDK's other jars are missing or broken: SdkGlobals cannot be loaded
     * (it needs the platform and runtime modules). The activator still gives the stock factory and never throws.
     */
    @Test
    void sdkClassesMissingGiveTheStockFactory() throws Exception {
        URL[] urls = {locationOf(SdkSystemActivator.class), locationOf(SystemScreenFactory.class)};
        try (URLClassLoader l = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
            assertThrows(ClassNotFoundException.class, () -> l.loadClass("mibsdk.runtime.RingLog"));
            Class<?> cls = Class.forName(SdkSystemActivator.class.getName(), true, l);
            Object a = cls.getConstructor().newInstance();
            giveFramework(a, l);
            Method m = cls.getDeclaredMethod("factoryForTests");
            m.setAccessible(true);
            Object f = m.invoke(a);
            assertEquals(SystemScreenFactory.class.getName(), f.getClass().getName());
            assertSame(Class.forName(SystemScreenFactory.class.getName(), false, l), f.getClass());
        }
    }
}
