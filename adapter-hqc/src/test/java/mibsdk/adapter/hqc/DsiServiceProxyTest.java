package mibsdk.adapter.hqc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.List;
import mibsdk.adapter.hqc.Fakes.Call;
import mibsdk.adapter.hqc.Fakes.Recorder;
import mibsdk.api.HookObserved;
import mibsdk.api.HookPoint;
import mibsdk.api.SoundDirections;
import mibsdk.platform.HookTable;
import org.dsi.ifc.base.DSIBase;
import org.dsi.ifc.base.DSIListener;
import org.dsi.ifc.carparkingsystem.DSICarParkingSystem;
import org.dsi.ifc.carparkingsystem.DSICarParkingSystemListener;
import org.dsi.ifc.carparkingsystem.PDCSoundReproduction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DsiServiceProxyTest {

    private static final Class<?>[] PARKING = {DSICarParkingSystem.class};

    private HookTable hooks;
    private Recorder service;
    private DSICarParkingSystem target;
    private DSICarParkingSystem wrapped;

    @BeforeEach
    void setUp() {
        hooks = new HookTable(256);
        service = new Recorder();
        target = Fakes.fake(DSICarParkingSystem.class, service);
        wrapped = (DSICarParkingSystem) DsiServiceProxy.wrap(target, PARKING, hooks, HookBindings.defaults());
    }

    private static HookObserved next(HookTable t) throws InterruptedException {
        return t.poll(0);
    }

    /** The listener wrapper the fake service received with the last setNotification. */
    private DSICarParkingSystemListener registeredWrapper() {
        List<Call> sets = service.named("setNotification");
        Object[] args = sets.get(sets.size() - 1).args;
        return (DSICarParkingSystemListener) args[args.length - 1];
    }

    /** Review Focus 2: the runtime never started, nobody polls the table; everything still flows unchanged. */
    @Test
    void forwardsWithNoRuntimeAttached() throws Exception {
        assertTrue(Proxy.isProxyClass(wrapped.getClass()), "service is wrapped");
        PDCSoundReproduction[] sent = new PDCSoundReproduction[1000];
        for (int i = 0; i < sent.length; i++) {
            sent[i] = new PDCSoundReproduction(i % 2 == 0, true, false, i % 3 == 0);
            wrapped.setPDCSoundReproduction(sent[i]);
        }
        List<Call> calls = service.named("setPDCSoundReproduction");
        assertEquals(1000, calls.size());
        for (int i = 0; i < sent.length; i++) {
            assertSame(sent[i], calls.get(i).args[0], "argument forwarded unchanged");
        }
        assertEquals(1000 - 256, hooks.droppedCount(), "the full ring drops oldest instead of blocking");

        // Unbound calls pass straight through too.
        wrapped.setPDCMute(true);
        assertEquals(Boolean.TRUE, service.named("setPDCMute").get(0).args[0]);

        // Listener updates: every one reaches the original listener with the same arguments.
        Recorder app = new Recorder();
        DSICarParkingSystemListener listener = Fakes.fake(DSICarParkingSystemListener.class, app);
        wrapped.setNotification(listener);
        DSICarParkingSystemListener fromService = registeredWrapper();
        for (int i = 0; i < 1000; i++) {
            fromService.updatePDCSoundReproduction(sent[i], i);
        }
        fromService.updatePDCMute(true, 1);
        fromService.asyncException(7, "boom", 9);
        List<Call> updates = app.named("updatePDCSoundReproduction");
        assertEquals(1000, updates.size());
        for (int i = 0; i < 1000; i++) {
            assertSame(sent[i], updates.get(i).args[0]);
            assertEquals(Integer.valueOf(i), updates.get(i).args[1]);
        }
        assertEquals(1, app.named("updatePDCMute").size());
        Object[] async = app.named("asyncException").get(0).args;
        assertEquals(Integer.valueOf(7), async[0]);
        assertEquals("boom", async[1]);
        assertEquals(Integer.valueOf(9), async[2]);
    }

    @Test
    void vetoBlocksVoidCall() throws Exception {
        Object token = hooks.addVeto(HookPoint.PARKING_SOUND_SET);
        hooks.setVetoActive(token, true);
        wrapped.setPDCSoundReproduction(new PDCSoundReproduction(true, false, true, false));
        assertEquals(0, service.named("setPDCSoundReproduction").size(), "vetoed void call not forwarded");
        HookObserved o = next(hooks);
        assertNotNull(o);
        assertEquals(HookPoint.PARKING_SOUND_SET, o.point());
        assertTrue(o.vetoed());
        // DSI ctor order is front, rear, right, left; SoundDirections is front, rear, left, right.
        assertEquals(new SoundDirections(true, false, false, true), o.value());

        hooks.setVetoActive(token, false);
        wrapped.setPDCSoundReproduction(new PDCSoundReproduction(false, true, false, false));
        assertEquals(1, service.named("setPDCSoundReproduction").size());
        HookObserved o2 = next(hooks);
        assertFalse(o2.vetoed());
        assertEquals(new SoundDirections(false, true, false, false), o2.value());
    }

    @Test
    void listenerWrappedAndUpdatesObserved() throws Exception {
        Recorder app = new Recorder();
        DSICarParkingSystemListener listener = Fakes.fake(DSICarParkingSystemListener.class, app);
        wrapped.setNotification(listener);
        DSICarParkingSystemListener fromService = registeredWrapper();
        assertNotSame(listener, fromService, "service receives a wrapper");
        assertTrue(Proxy.isProxyClass(fromService.getClass()));

        PDCSoundReproduction v = new PDCSoundReproduction(true, false, false, false);
        fromService.updatePDCSoundReproduction(v, 1);
        List<Call> got = app.named("updatePDCSoundReproduction");
        assertEquals(1, got.size());
        assertSame(v, got.get(0).args[0]);
        assertEquals(Integer.valueOf(1), got.get(0).args[1]);

        HookObserved o = next(hooks);
        assertNotNull(o);
        assertEquals(HookPoint.PARKING_SOUND_UPDATE, o.point());
        assertFalse(o.vetoed());
        assertEquals(new SoundDirections(true, false, false, false), o.value());
        assertNull(next(hooks), "exactly one observation");

        // A vetoed update is observed but does not reach the HMI listener.
        Object token = hooks.addVeto(HookPoint.PARKING_SOUND_UPDATE);
        hooks.setVetoActive(token, true);
        fromService.updatePDCSoundReproduction(new PDCSoundReproduction(false, false, true, true), 1);
        assertEquals(1, app.named("updatePDCSoundReproduction").size());
        assertTrue(next(hooks).vetoed());
    }

    @Test
    void clearNotificationUsesSameWrapper() {
        Recorder app = new Recorder();
        DSICarParkingSystemListener listener = Fakes.fake(DSICarParkingSystemListener.class, app);
        wrapped.setNotification(listener);
        wrapped.setNotification(new int[] {1, 2}, listener);
        wrapped.setNotification(3, listener);
        wrapped.clearNotification(listener);
        wrapped.clearNotification(new int[] {1, 2}, listener);
        wrapped.clearNotification(3, listener);

        Object first = registeredWrapper();
        assertNotSame(listener, first);
        for (Call c : service.calls) {
            assertSame(first, c.args[c.args.length - 1], c.method + " got the same wrapper");
        }
        assertEquals(6, service.calls.size());
        assertSame(first, DsiServiceProxy.wrapListener(listener, hooks, HookBindings.defaults()));

        int[] ids = {1, 2};
        wrapped.setNotification(ids, listener);
        assertSame(ids, service.calls.get(service.calls.size() - 1).args[0], "other args unchanged");
    }

    @Test
    void clearNotificationOfUnknownListenerPassesItUnchanged() {
        DSICarParkingSystemListener listener = Fakes.fake(DSICarParkingSystemListener.class, new Recorder());
        wrapped.clearNotification(listener);
        assertSame(listener, service.named("clearNotification").get(0).args[0]);
    }

    @Test
    void listenerWithoutBoundMethodsIsNotWrapped() {
        DSIListener plain = Fakes.fake(DSIListener.class, new Recorder());
        wrapped.setNotification(plain);
        assertSame(plain, service.named("setNotification").get(0).args[0]);
    }

    @Test
    void targetExceptionPropagatesUnwrapped() {
        IllegalStateException boom = new IllegalStateException("stock failure");
        service.toThrow = boom;
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> wrapped.setPDCSoundReproduction(new PDCSoundReproduction()));
        assertSame(boom, thrown);

        Recorder app = new Recorder();
        app.toThrow = new IllegalArgumentException("listener failure");
        service.toThrow = null;
        wrapped.setNotification(Fakes.fake(DSICarParkingSystemListener.class, app));
        DSICarParkingSystemListener fromService = registeredWrapper();
        assertSame(app.toThrow, assertThrows(IllegalArgumentException.class,
                () -> fromService.updatePDCSoundReproduction(new PDCSoundReproduction(), 1)));
    }

    @Test
    void objectMethodsDelegateToTarget() {
        assertEquals(target.hashCode(), wrapped.hashCode());
        assertEquals(target.toString(), wrapped.toString());
        assertTrue(wrapped.equals(wrapped));
        Object again = DsiServiceProxy.wrap(target, PARKING, hooks, HookBindings.defaults());
        assertTrue(wrapped.equals(again), "two wrappers of one target are equal");
        assertEquals(wrapped.hashCode(), again.hashCode());
        assertFalse(wrapped.equals(Fakes.fake(DSICarParkingSystem.class, new Recorder())));
        assertFalse(wrapped.equals(null));
        assertTrue(service.calls.isEmpty(), "Object methods are not DSI calls");
    }

    @Test
    void proxyImplementsTheGivenInterfacesAndSuperInterfaces() {
        assertTrue(wrapped instanceof DSIBase);
        Class<?>[] all = DsiServiceProxy.interfacesOf(target.getClass());
        assertTrue(java.util.Arrays.asList(all).contains(DSICarParkingSystem.class));
    }

    @Test
    void wrapReturnsServiceItselfOnFailure() {
        assertSame(target, DsiServiceProxy.wrap(target, new Class<?>[0], hooks, HookBindings.defaults()));
        assertSame(target, DsiServiceProxy.wrap(target, null, hooks, HookBindings.defaults()));
        assertSame(target, DsiServiceProxy.wrap(target, new Class<?>[] {String.class}, hooks, HookBindings.defaults()));
        assertNull(DsiServiceProxy.wrap(null, PARKING, hooks, HookBindings.defaults()));
        assertSame(target, DsiServiceProxy.wrap(target, PARKING, null, HookBindings.defaults()));
        Object l = new Object();
        assertSame(l, DsiServiceProxy.wrapListener(l, hooks, HookBindings.defaults()));
        assertNull(DsiServiceProxy.wrapListener(null, hooks, HookBindings.defaults()));
    }

    /** Package-private, like an HMI-internal interface. */
    interface Hidden {
        void ping();
    }

    @Test
    void nonPublicInterfaceMeansNoServiceProxy() {
        Object both = java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {DSICarParkingSystem.class, Hidden.class}, new Recorder());
        Class<?>[] ifaces = DsiServiceProxy.interfacesOf(both.getClass());
        assertSame(both, DsiServiceProxy.wrap(both, ifaces, hooks, HookBindings.defaults()));
        assertTrue(SdkGlobals.LOG.snapshot().toString().indexOf("non-public interface " + Hidden.class.getName()) >= 0);
    }

    @Test
    void listenerWithNonPublicInterfaceIsWrappedOverItsPublicOnes() throws Exception {
        Recorder app = new Recorder();
        Object listener = java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {DSICarParkingSystemListener.class, Hidden.class}, app);
        wrapped.setNotification((DSIListener) listener);
        DSICarParkingSystemListener fromService = registeredWrapper();
        assertNotSame(listener, fromService);
        assertFalse(fromService instanceof Hidden);
        PDCSoundReproduction v = new PDCSoundReproduction(false, true, false, false);
        fromService.updatePDCSoundReproduction(v, 1);
        assertSame(v, app.named("updatePDCSoundReproduction").get(0).args[0]);
        assertEquals(new SoundDirections(false, true, false, false), next(hooks).value());
    }

    @Test
    void nullSoundArgumentIsForwardedAndNotObserved() throws Exception {
        wrapped.setPDCSoundReproduction(null);
        assertEquals(1, service.named("setPDCSoundReproduction").size());
        assertNull(service.named("setPDCSoundReproduction").get(0).args[0]);
        assertNull(next(hooks));
    }
}
