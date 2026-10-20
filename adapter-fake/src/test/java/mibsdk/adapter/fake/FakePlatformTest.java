package mibsdk.adapter.fake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import mibsdk.api.Features;
import mibsdk.api.HookObserved;
import mibsdk.api.HookPoint;
import mibsdk.api.IgnitionChanged;
import mibsdk.api.ParkingSoundChanged;
import mibsdk.api.SoundDirections;
import mibsdk.platform.HookTable;
import mibsdk.platform.PlatformHost;
import org.junit.jupiter.api.Test;

class FakePlatformTest {

    private static final class RecordingHost implements PlatformHost {
        final List<Object> events = Collections.synchronizedList(new ArrayList<Object>());
        final HookTable table = new HookTable(16);

        @Override
        public void publish(Object apiEvent) {
            events.add(apiEvent);
        }

        @Override
        public HookTable hooks() {
            return table;
        }

        @Override
        public void log(String msg, Throwable t) {
        }
    }

    private static FakePlatform started(RecordingHost host) throws Exception {
        Set<String> features = new HashSet<String>();
        features.add(Features.PARKING);
        features.add(Features.IGNITION);
        FakePlatform p = new FakePlatform("FAKE_HMI", features);
        p.start(host);
        return p;
    }

    @Test
    void ecuChangePublishesEvent() throws Exception {
        RecordingHost host = new RecordingHost();
        FakePlatform p = started(host);
        SoundDirections v = new SoundDirections(true, false, true, false);

        p.ecuSetSoundDirections(v);
        p.setIgnition(true);

        assertEquals(v, p.ecuSoundDirections());
        assertEquals(2, host.events.size());
        assertEquals(v, ((ParkingSoundChanged) host.events.get(0)).value());
        assertTrue(((IgnitionChanged) host.events.get(1)).isOn());
        assertTrue(p.appliedLog().isEmpty());
    }

    @Test
    void applyPersistsAndIsLogged() throws Exception {
        FakePlatform p = started(new RecordingHost());
        SoundDirections v = new SoundDirections(false, true, false, true);

        p.parking().apply(v);

        assertEquals(v, p.parking().current());
        assertEquals(v, p.ecuSoundDirections());
        assertEquals(1, p.appliedLog().size());
        assertEquals(v, p.appliedLog().get(0));
    }

    @Test
    void identityAndSupport() {
        FakePlatform p = new FakePlatform("FAKE_HMI", Collections.singleton(Features.PARKING));
        assertNotNull(p.id());
        assertTrue(p.supports("FAKE_HMI"));
        assertFalse(p.supports("OTHER"));
        assertTrue(p.features().contains(Features.PARKING));
    }

    @Test
    void simulatedVetoedCallNotForwarded() throws Exception {
        RecordingHost host = new RecordingHost();
        FakePlatform p = started(host);
        SoundDirections v = new SoundDirections(true, true, true, true);

        assertTrue(p.simulateHookCall(HookPoint.PARKING_SOUND_SET, v));
        Object token = host.table.addVeto(HookPoint.PARKING_SOUND_SET);
        host.table.setVetoActive(token, true);
        assertFalse(p.simulateHookCall(HookPoint.PARKING_SOUND_SET, v));

        HookObserved first = host.table.poll(100);
        HookObserved second = host.table.poll(100);
        assertFalse(first.vetoed());
        assertTrue(second.vetoed());
        assertEquals(v, second.value());
        assertEquals(HookPoint.PARKING_SOUND_SET, second.point());
        assertTrue(p.appliedLog().isEmpty());
    }

    private static final SoundDirections OLD = new SoundDirections(true, true, true, true);
    private static final SoundDirections F1R0L1X1 = new SoundDirections(true, false, true, true);

    @Test
    void acceptedWriteIsReportedBeforeApplyReturns() throws Exception {
        RecordingHost host = new RecordingHost();
        FakePlatform p = started(host);

        p.parking().apply(F1R0L1X1);

        assertEquals(1, host.events.size());
        assertEquals(F1R0L1X1, ((ParkingSoundChanged) host.events.get(0)).value());
        assertEquals(F1R0L1X1, p.parking().current());
    }

    @Test
    void droppedWriteLeavesTheEcuAndReportsNothing() throws Exception {
        RecordingHost host = new RecordingHost();
        FakePlatform p = started(host);
        p.ecuDropWrites(1);

        p.parking().apply(F1R0L1X1);

        assertEquals(OLD, p.ecuSoundDirections());
        assertTrue(host.events.isEmpty());
        assertEquals(1, p.droppedWrites());

        p.parking().apply(F1R0L1X1);

        assertEquals(F1R0L1X1, p.ecuSoundDirections());
        assertEquals(1, host.events.size());
        assertEquals(1, p.droppedWrites());
        assertEquals(2, p.appliedLog().size());
    }

    @Test
    void failedWriteThrowsAndChangesNothing() throws Exception {
        RecordingHost host = new RecordingHost();
        FakePlatform p = started(host);
        p.ecuFailWrites(1);

        IllegalStateException e = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> p.parking().apply(F1R0L1X1));

        assertEquals("ECU rejected the write (fake)", e.getMessage());
        assertEquals(OLD, p.ecuSoundDirections());
        assertTrue(host.events.isEmpty());
        assertEquals(1, p.appliedLog().size());
    }

    @Test
    void silentEcuAcceptsWithoutReporting() throws Exception {
        RecordingHost host = new RecordingHost();
        FakePlatform p = started(host);
        p.ecuReportWrites(false);

        p.parking().apply(F1R0L1X1);

        assertEquals(F1R0L1X1, p.ecuSoundDirections());
        assertTrue(host.events.isEmpty());
    }
}
