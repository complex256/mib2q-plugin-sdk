package mibsdk.adapter.hqc;

import static mibsdk.adapter.hqc.Fakes.eventually;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import mibsdk.adapter.hqc.Fakes.Recorder;
import mibsdk.adapter.hqc.Fakes.RecordingHost;
import mibsdk.api.IgnitionChanged;
import org.dsi.ifc.powermanagement.ClampSignal;
import org.dsi.ifc.powermanagement.DSIPowerManagement;
import org.dsi.ifc.powermanagement.DSIPowerManagementListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IgnitionBridgeTest {

    private static final int VALID = DsiConstants.get("org.dsi.ifc.base.DSIListener", "ATTRVALIDFLAG_VALID");
    private static final int INVALID = VALID + 1;

    private RecordingHost host;
    private EventPump pump;
    private IgnitionBridge bridge;
    private Recorder service;

    @BeforeEach
    void setUp() {
        host = new RecordingHost();
        pump = new EventPump("hqc-events", host, 64);
        pump.start();
        bridge = new IgnitionBridge(pump);
        service = new Recorder();
        bridge.attach(Fakes.fake(DSIPowerManagement.class, service));
    }

    @AfterEach
    void tearDown() {
        pump.stop();
    }

    private void clamp15(boolean on, int flag) {
        ((DSIPowerManagementListener) bridge.listener()).updateClampSignal(new ClampSignal(true, on, true, false),
                flag);
    }

    private List<Boolean> published() throws InterruptedException {
        assertTrue(pump.awaitIdle(2000));
        List<Boolean> out = new ArrayList<>();
        for (Object e : host.snapshot()) {
            out.add(((IgnitionChanged) e).isOn());
        }
        return out;
    }

    @Test
    void attachRegistersTheListener() {
        assertSame(bridge.listener(), service.named("setNotification").get(0).args[0]);
    }

    @Test
    void clampSignalCtorOrderSanity() {
        // ClampSignal(clampS, clamp15, clampX, clamp50): the bridge reads isClamp15().
        assertTrue(new ClampSignal(false, true, false, false).isClamp15());
    }

    @Test
    void publishesOnlyOnChange() throws Exception {
        clamp15(true, VALID);
        clamp15(true, VALID);
        clamp15(true, VALID);
        clamp15(false, VALID);
        clamp15(false, VALID);
        clamp15(true, VALID);
        assertEquals(Arrays.asList(true, false, true), published());
    }

    @Test
    void initialOffPublishesNothing() throws Exception {
        clamp15(false, VALID);
        clamp15(false, VALID);
        assertEquals(Arrays.asList(), published(), "the first callback only establishes the state");
    }

    @Test
    void initialOffThenOnThenOff() throws Exception {
        clamp15(false, VALID);
        clamp15(true, VALID);
        clamp15(false, VALID);
        assertEquals(Arrays.asList(true, false), published(), "off only after a real on-to-off transition");
    }

    @Test
    void onThenOffPublishesTrueThenFalse() throws Exception {
        clamp15(true, VALID);
        clamp15(false, VALID);
        assertEquals(Arrays.asList(true, false), published());
    }

    @Test
    void invalidUpdatesAreIgnored() throws Exception {
        clamp15(true, INVALID);
        clamp15(true, VALID);
        clamp15(false, INVALID);
        assertEquals(Arrays.asList(true), published(), "neither the invalid on nor the invalid off counts");
        clamp15(false, VALID);
        assertEquals(Arrays.asList(true, false), published());
    }

    @Test
    void publishesOnTheSdkThread() throws Exception {
        clamp15(true, VALID);
        assertTrue(eventually(2000, () -> host.threads.size() == 1));
        assertEquals("mibsdk-hqc-events", host.threads.get(0));
    }

    @Test
    void callbacksNeverThrow() throws Exception {
        DSIPowerManagementListener l = (DSIPowerManagementListener) bridge.listener();
        assertDoesNotThrow(() -> {
            l.updateClampSignal(null, VALID);
            l.updateBEMState(1, VALID);
            l.asyncException(1, "x", 2);
            l.toString();
            l.hashCode();
        });
        assertEquals(Arrays.asList(), published());
    }

    @Test
    void detachClearsTheListener() {
        bridge.detach();
        assertSame(bridge.listener(), service.named("clearNotification").get(0).args[0]);
    }
}
