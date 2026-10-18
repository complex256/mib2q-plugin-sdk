package mibsdk.adapter.fake;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import mibsdk.api.HookPoint;
import mibsdk.api.IgnitionChanged;
import mibsdk.api.ParkingSoundChanged;
import mibsdk.api.SoundDirections;
import mibsdk.platform.ParkingIo;
import mibsdk.platform.PlatformAdapter;
import mibsdk.platform.PlatformHost;

/** Host-only in-memory {@link PlatformAdapter} for tests and the dev harness. */
public final class FakePlatform implements PlatformAdapter {

    private final String supportedVersion;
    private final Set<String> features;
    private final List<SoundDirections> applied = Collections.synchronizedList(new ArrayList<SoundDirections>());
    private volatile PlatformHost host;
    private volatile SoundDirections ecu = new SoundDirections(true, true, true, true);

    private final ParkingIo parking = new ParkingIo() {
        @Override
        public SoundDirections current() {
            return ecu;
        }

        @Override
        public void apply(SoundDirections value) {
            ecu = value;
            applied.add(value);
        }
    };

    public FakePlatform(String supportedVersion, Set<String> features) {
        this.supportedVersion = supportedVersion;
        this.features = Collections.unmodifiableSet(new HashSet<String>(features));
    }

    @Override
    public String id() {
        return "fake";
    }

    @Override
    public boolean supports(String hmiVersion) {
        return supportedVersion.equals(hmiVersion);
    }

    @Override
    public Set<String> features() {
        return features;
    }

    @Override
    public void start(PlatformHost host) {
        this.host = host;
    }

    @Override
    public void stop() {
        this.host = null;
    }

    @Override
    public ParkingIo parking() {
        return parking;
    }

    /** Simulates the ignition changing; publishes {@link IgnitionChanged}. */
    public void setIgnition(boolean on) {
        host().publish(new IgnitionChanged(on));
    }

    /** Simulates the car (not the SDK) changing the persisted value; publishes {@link ParkingSoundChanged}. */
    public void ecuSetSoundDirections(SoundDirections v) {
        ecu = v;
        host().publish(new ParkingSoundChanged(v));
    }

    /** The persisted ECU value. */
    public SoundDirections ecuSoundDirections() {
        return ecu;
    }

    /** Every {@link ParkingIo#apply} call, in order. */
    public List<SoundDirections> appliedLog() {
        synchronized (applied) {
            return new ArrayList<SoundDirections>(applied);
        }
    }

    /**
     * Does what the real DSI proxy does for a stock call: asks the hook table whether the point is vetoed,
     * records the observation, and reports whether the call would be forwarded to the stock service.
     */
    public boolean simulateHookCall(HookPoint p, SoundDirections v) {
        PlatformHost h = host();
        boolean vetoed = h.hooks().isVetoed(p);
        h.hooks().observed(p, v, vetoed);
        return !vetoed;
    }

    private PlatformHost host() {
        PlatformHost h = host;
        if (h == null) {
            throw new IllegalStateException("FakePlatform not started");
        }
        return h;
    }
}
