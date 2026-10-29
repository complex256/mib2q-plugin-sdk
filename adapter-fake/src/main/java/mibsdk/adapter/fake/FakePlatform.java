package mibsdk.adapter.fake;

import java.io.File;
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
import mibsdk.platform.SdkRoots;

/** Host-only in-memory {@link PlatformAdapter} for tests and the dev harness. */
public final class FakePlatform implements PlatformAdapter {

    private final String supportedVersion;
    private volatile Set<String> features;
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
            applied.add(value);
            boolean report;
            synchronized (ecuLock) {
                if (failWrites > 0) {
                    if (failWrites != Integer.MAX_VALUE) {
                        failWrites--;
                    }
                    throw new IllegalStateException("ECU rejected the write (fake)");
                }
                if (dropWrites > 0) {
                    if (dropWrites != Integer.MAX_VALUE) {
                        dropWrites--;
                    }
                    dropped++;
                    return;
                }
                report = reportWrites && (reportUnchanged || !value.equals(ecu));
                ecu = value;
            }
            if (report) {
                host().publish(new ParkingSoundChanged(value));
            }
        }
    };

    private final Object ecuLock = new Object();
    private int failWrites;
    private int dropWrites;
    private int dropped;
    private boolean reportWrites = true;
    private boolean reportUnchanged = true;

    /**
     * The SDK roots for a test: {@code <tmp>/mibsdk} (install, as on the unit), {@code <tmp>/persist} and
     * {@code <tmp>/ram}, three separate directories so a test sees which root each file lands in. Nothing is
     * created.
     */
    public static SdkRoots roots(File tmp) {
        return new SdkRoots(new File(tmp, "mibsdk"), new File(tmp, "persist"), new File(tmp, "ram"));
    }

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

    @Override
    public mibsdk.platform.WifiIo wifi() {
        return null;
    }

    /**
     * Simulates the adapter learning (or losing) features after start, e.g. a DSI service that arrived late:
     * replaces {@link #features()} and, if started, calls {@link PlatformHost#featuresChanged()}.
     */
    public void setFeatures(Set<String> features) {
        this.features = Collections.unmodifiableSet(new HashSet<String>(features));
        PlatformHost h = host;
        if (h != null) {
            h.featuresChanged();
        }
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

    /** The next {@code n} writes return normally but the ECU keeps its value and reports nothing. {@code Integer.MAX_VALUE} = until reset with 0. */
    public void ecuDropWrites(int n) {
        synchronized (ecuLock) {
            dropWrites = n;
        }
    }

    /** The next {@code n} writes throw {@link IllegalStateException}; checked before drop. */
    public void ecuFailWrites(int n) {
        synchronized (ecuLock) {
            failWrites = n;
        }
    }

    /** Whether an accepted write publishes {@link ParkingSoundChanged} before {@code apply} returns (default true). */
    public void ecuReportWrites(boolean on) {
        synchronized (ecuLock) {
            reportWrites = on;
        }
    }

    /**
     * Whether an accepted write that leaves the ECU's value unchanged is reported too (default true). A real
     * ECU may stay silent then: the value it last reported is still its value.
     */
    public void ecuReportUnchanged(boolean on) {
        synchronized (ecuLock) {
            reportUnchanged = on;
        }
    }

    /** How many writes were dropped so far. */
    public int droppedWrites() {
        synchronized (ecuLock) {
            return dropped;
        }
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
