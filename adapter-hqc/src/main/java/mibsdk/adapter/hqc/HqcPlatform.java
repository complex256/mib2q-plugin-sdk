package mibsdk.adapter.hqc;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import mibsdk.api.Features;
import mibsdk.platform.ParkingIo;
import mibsdk.platform.PlatformAdapter;
import mibsdk.platform.PlatformHost;
import mibsdk.runtime.Clock;
import org.dsi.ifc.carparkingsystem.DSICarParkingSystem;
import org.dsi.ifc.powermanagement.DSIPowerManagement;
import org.osgi.framework.BundleContext;

/**
 * The adapter for {@link #SUPPORTED}: parking sound directions and trailer state ({@link ParkingBridge}), ignition
 * ({@link IgnitionBridge}), and the parking hook points (recorded by {@link DsiServiceProxy}, which
 * {@link ChainingConfigurator} installs). {@link #start} returns at once: a {@link ServiceWaiter} finds the DSI
 * services on its own threads and attaches the bridges when they appear; their events reach the runtime through
 * one {@link EventPump} thread, never from a stock thread. Once the parking bridge is attached the waiter thread
 * calls {@link PlatformHost#featuresChanged()}: the service may have arrived after the plugin load.
 */
public final class HqcPlatform implements PlatformAdapter {

    public static final String SUPPORTED = HqcVersion.SUPPORTED;
    static final String ID = "hqc-nar-g22-c10";
    /** Fast retry period of the service wait. */
    static final long RETRY_MS = 200;
    private static final int EVENT_QUEUE = 64;

    private static final Set<String> FEATURES = Collections.unmodifiableSet(new HashSet<String>(
            Arrays.asList(new String[] {Features.PARKING, Features.IGNITION})));
    private static final Set<String> FEATURES_HOOKED = Collections.unmodifiableSet(new HashSet<String>(
            Arrays.asList(new String[] {Features.PARKING, Features.IGNITION, Features.HOOK_PARKING})));

    private final BundleContext ctx;
    private final Clock clock;
    private EventPump pump; // guarded by this
    private IgnitionBridge ignition; // guarded by this
    private ServiceWaiter waiter; // guarded by this
    private volatile ParkingBridge parking;

    public HqcPlatform(BundleContext ctx) {
        this(ctx, null);
    }

    /** {@code clock} only times the service waits for the log; null is fine. */
    HqcPlatform(BundleContext ctx, Clock clock) {
        this.ctx = ctx;
        this.clock = clock;
    }

    public String id() {
        return ID;
    }

    public boolean supports(String hmiVersion) {
        return SUPPORTED.equals(hmiVersion);
    }

    /**
     * PARKING and IGNITION, plus HOOK_PARKING only if the configurator wrapped the parking service this boot
     * (wrapping enabled and its raw object recorded): otherwise no parking hook point ever fires, and plugins must
     * see {@code has(HOOK_PARKING) == false}. The runtime reads this again before it loads plugins and on every
     * {@link PlatformHost#featuresChanged()}.
     */
    public Set<String> features() {
        return SdkGlobals.wrappingEnabled && RawServices.get(DSICarParkingSystem.class.getName()) != null
                ? FEATURES_HOOKED : FEATURES;
    }

    /** Starts the event pump and the service waits; does not block. A second start is ignored. */
    public synchronized void start(final PlatformHost host) {
        if (pump != null) {
            return;
        }
        EventPump p = new EventPump("hqc-events", host, EVENT_QUEUE);
        final ParkingBridge pb = new ParkingBridge(p);
        final IgnitionBridge ib = new IgnitionBridge(p);
        ServiceWaiter w = new ServiceWaiter(ctx, clock);
        pump = p;
        parking = pb;
        ignition = ib;
        waiter = w;
        p.start();
        w.await(DSICarParkingSystem.class.getName(), RETRY_MS, new ServiceWaiter.Callback() {
            public void onAvailable(Object service) {
                pb.attach(service);
                SdkGlobals.LOG.append("STATUS", "mibsdk", SdkBoot.seamLine()); // raw.parking is final from here
                host.featuresChanged(); // features() may now include HOOK_PARKING; plugins may already run
            }
        });
        w.await(DSIPowerManagement.class.getName(), RETRY_MS, new ServiceWaiter.Callback() {
            public void onAvailable(Object service) {
                ib.attach(service);
            }
        });
    }

    /** Ends the waits, unregisters the listeners, stops the pump. Never blocks on the car. Idempotent. */
    public void stop() {
        ServiceWaiter w;
        ParkingBridge pb;
        IgnitionBridge ib;
        EventPump p;
        synchronized (this) {
            w = waiter;
            pb = parking;
            ib = ignition;
            p = pump;
            waiter = null;
            ignition = null;
        }
        if (w != null) {
            w.stop();
        }
        if (pb != null) {
            pb.detach(); // parking() stays: current() keeps the last value, apply() fails
        }
        if (ib != null) {
            ib.detach();
        }
        if (p != null) {
            p.stop();
        }
    }

    /** The parking bridge once started; null before. */
    public ParkingIo parking() {
        return parking;
    }
}
