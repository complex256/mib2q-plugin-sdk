package mibsdk.adapter.hqc;

import static mibsdk.adapter.hqc.Fakes.eventually;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import mibsdk.adapter.hqc.Fakes.Call;
import mibsdk.adapter.hqc.Fakes.Recorder;
import mibsdk.adapter.hqc.Fakes.RecordingHost;
import mibsdk.api.HookPoint;
import mibsdk.api.ParkingSoundChanged;
import mibsdk.api.SoundDirections;
import mibsdk.api.TrailerHitchedChanged;
import mibsdk.platform.HookTable;
import org.dsi.ifc.carparkingsystem.DSICarParkingSystem;
import org.dsi.ifc.carparkingsystem.DSICarParkingSystemListener;
import org.dsi.ifc.carparkingsystem.PDCSoundReproduction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ParkingBridgeTest {

    private static final int VALID = DsiConstants.get("org.dsi.ifc.base.DSIListener", "ATTRVALIDFLAG_VALID");
    private static final int INVALID = VALID + 1;

    private RecordingHost host;
    private EventPump pump;
    private ParkingBridge bridge;
    private Recorder service;
    private DSICarParkingSystem fake;

    @BeforeEach
    void setUp() {
        host = new RecordingHost();
        pump = new EventPump("hqc-events", host, 8);
        pump.start();
        bridge = new ParkingBridge(pump);
        service = new Recorder();
        fake = Fakes.fake(DSICarParkingSystem.class, service);
    }

    @AfterEach
    void tearDown() {
        pump.stop();
    }

    private DSICarParkingSystemListener listener() {
        return (DSICarParkingSystemListener) bridge.listener();
    }

    @Test
    void applyUsesDsiArgumentOrder() throws Exception {
        bridge.attach(fake);
        bridge.apply(new SoundDirections(true, false, true, false)); // front, rear, left, right
        List<Call> calls = service.named("setPDCSoundReproduction");
        assertEquals(1, calls.size());
        PDCSoundReproduction r = (PDCSoundReproduction) calls.get(0).args[0];
        assertTrue(r.front, "front");
        assertFalse(r.rear, "rear");
        assertFalse(r.right, "right");
        assertTrue(r.left, "left");

        bridge.apply(new SoundDirections(false, true, false, true));
        r = (PDCSoundReproduction) service.named("setPDCSoundReproduction").get(1).args[0];
        assertFalse(r.front);
        assertTrue(r.rear);
        assertTrue(r.right);
        assertFalse(r.left);
    }

    @Test
    void attachRegistersTheBridgeListener() {
        bridge.attach(fake);
        List<Call> sets = service.named("setNotification");
        assertEquals(1, sets.size());
        assertSame(bridge.listener(), sets.get(0).args[0]);
    }

    @Test
    void invalidFlagIgnored() throws Exception {
        bridge.attach(fake);
        listener().updatePDCSoundReproduction(new PDCSoundReproduction(true, true, true, true), INVALID);
        listener().updatePDCTrailerHitched(true, INVALID);
        assertTrue(pump.awaitIdle(2000));
        assertEquals(0, host.snapshot().size());
        assertNull(bridge.current(), "an invalid update is not a known value");
    }

    @Test
    void validUpdatePublishesEvent() throws Exception {
        bridge.attach(fake);
        // PDCSoundReproduction(front, rear, right, left)
        listener().updatePDCSoundReproduction(new PDCSoundReproduction(true, false, true, false), VALID);
        SoundDirections expected = new SoundDirections(true, false, false, true); // front, rear, left, right
        assertTrue(eventually(2000, () -> host.snapshot().size() == 1));
        ParkingSoundChanged e = (ParkingSoundChanged) host.snapshot().get(0);
        assertEquals(expected, e.value());
        assertEquals(expected, bridge.current());

        listener().updatePDCTrailerHitched(true, VALID);
        assertTrue(eventually(2000, () -> host.snapshot().size() == 2));
        assertTrue(((TrailerHitchedChanged) host.snapshot().get(1)).isHitched());
    }

    @Test
    void publishesOnTheSdkThreadNotTheCallbackThread() throws Exception {
        bridge.attach(fake);
        listener().updatePDCSoundReproduction(new PDCSoundReproduction(true, true, true, true), VALID);
        listener().updatePDCTrailerHitched(false, VALID);
        assertTrue(eventually(2000, () -> host.threads.size() == 2));
        for (String t : host.threads) {
            assertEquals("mibsdk-hqc-events", t);
        }
    }

    @Test
    void slowSubscriberNeverBlocksTheCallbackAndDropsOldest() throws Exception {
        bridge.attach(fake);
        CountDownLatch gate = new CountDownLatch(1);
        host.gate = gate;
        long t0 = System.nanoTime();
        for (int i = 0; i < 200; i++) {
            listener().updatePDCSoundReproduction(new PDCSoundReproduction(i % 2 == 0, true, true, true), VALID);
        }
        listener().updatePDCSoundReproduction(new PDCSoundReproduction(false, false, false, false), VALID);
        long ms = (System.nanoTime() - t0) / 1000000;
        assertTrue(ms < 1000, "callbacks returned while the subscriber was stuck: " + ms + " ms");
        assertEquals(new SoundDirections(false, false, false, false), bridge.current(), "cache set on callback");
        gate.countDown();
        assertTrue(pump.awaitIdle(5000));
        List<Object> events = host.snapshot();
        assertTrue(events.size() <= 8 + 1, "bounded queue (+1 in flight): " + events.size());
        assertTrue(pump.droppedCount() >= 200 - 8, "dropped: " + pump.droppedCount());
        assertEquals(new SoundDirections(false, false, false, false),
                ((ParkingSoundChanged) events.get(events.size() - 1)).value(), "newest kept");
        assertTrue(eventually(2000, () -> host.logs.toString().indexOf("dropped") >= 0), "drops logged");
    }

    @Test
    void currentReflectsApplyImmediately() throws Exception {
        assertNull(bridge.current(), "unknown before any observation");
        assertThrows(IllegalStateException.class, () -> bridge.apply(new SoundDirections(true, true, true, true)),
                "no service yet");
        assertNull(bridge.current(), "a failed apply changes nothing");
        bridge.attach(fake);
        SoundDirections v = new SoundDirections(true, false, true, false);
        bridge.apply(v);
        assertEquals(v, bridge.current(), "before any echo from the car");
        assertTrue(pump.awaitIdle(2000));
        assertEquals(0, host.snapshot().size(), "apply itself publishes nothing; the car's echo does");
    }

    @Test
    void failedApplyKeepsTheCachedValue() throws Exception {
        bridge.attach(fake);
        SoundDirections v = new SoundDirections(true, true, true, true);
        bridge.apply(v);
        service.toThrow = new RuntimeException("dsi down");
        assertThrows(RuntimeException.class, () -> bridge.apply(new SoundDirections(false, false, false, false)));
        assertEquals(v, bridge.current());
    }

    @Test
    void sdkProxyIsBypassedForApplyAndListener() throws Exception {
        HookTable hooks = new HookTable(16);
        Object token = hooks.addVeto(HookPoint.PARKING_SOUND_SET);
        hooks.setVetoActive(token, true);
        Object proxied = DsiServiceProxy.wrap(fake, new Class<?>[] {DSICarParkingSystem.class}, hooks,
                HookBindings.defaults());
        bridge.attach(proxied);
        bridge.apply(new SoundDirections(true, true, false, false));
        assertEquals(1, service.named("setPDCSoundReproduction").size(), "a plugin veto cannot block the SDK");
        assertSame(bridge.listener(), service.named("setNotification").get(0).args[0], "listener not re-wrapped");
        listener().updatePDCSoundReproduction(new PDCSoundReproduction(true, true, true, true), VALID);
        assertNull(hooks.poll(0), "the SDK's own calls are not hook observations");
    }

    @Test
    void sdkListenerIsNeverWrappedByTheProxy() {
        assertSame(bridge.listener(),
                DsiServiceProxy.wrapListener(bridge.listener(), new HookTable(4), HookBindings.defaults()));
    }

    @Test
    void otherCallbacksAreNoOpsAndNothingThrows() throws Exception {
        bridge.attach(fake);
        DSICarParkingSystemListener l = listener();
        assertDoesNotThrow(() -> {
            l.updatePDCMute(true, VALID);
            l.updatePDCSoundReproduction(null, VALID);
            l.asyncException(1, "x", 2);
            l.toString();
            l.hashCode();
        });
        assertTrue(l.equals(l));
        assertFalse(l.equals(fake));
        assertTrue(pump.awaitIdle(2000));
        assertEquals(0, host.snapshot().size());
    }

    @Test
    void detachClearsTheListenerAndLaterAttachIsIgnored() throws Exception {
        bridge.attach(fake);
        bridge.detach();
        assertSame(bridge.listener(), service.named("clearNotification").get(0).args[0]);
        assertThrows(IllegalStateException.class, () -> bridge.apply(new SoundDirections(true, true, true, true)));
        bridge.attach(fake);
        assertEquals(1, service.named("setNotification").size(), "no attach after detach");
    }
}
