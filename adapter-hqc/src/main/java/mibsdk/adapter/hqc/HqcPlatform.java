package mibsdk.adapter.hqc;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import mibsdk.adapter.hqc.ui.BuiltinIcons;
import mibsdk.adapter.hqc.ui.HqcUiIds;
import mibsdk.adapter.hqc.ui.UiApplier;
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
    /** The OSGi service the UI waits for: the HMI framework (system models; {@code getHmiServiceApp()}). */
    static final String FRAMEWORK_ACCESS = "de.audi.atip.base.IFrameworkAccess";

    private static final Set<String> FEATURES = Collections.unmodifiableSet(new HashSet<String>(
            Arrays.asList(new String[] {Features.PARKING, Features.IGNITION})));
    private static final Set<String> FEATURES_HOOKED = Collections.unmodifiableSet(new HashSet<String>(
            Arrays.asList(new String[] {Features.PARKING, Features.IGNITION, Features.HOOK_PARKING})));
    private static final Set<String> FEATURES_UI = Collections.unmodifiableSet(new HashSet<String>(
            Arrays.asList(new String[] {Features.PARKING, Features.IGNITION, Features.SLOT_CAR_MENU, Features.UI_PAGES,
                Features.SLOT_STATUS_BAR, Features.UI_ICONS, Features.UI_INPUTS})));
    private static final Set<String> FEATURES_HOOKED_UI = Collections.unmodifiableSet(new HashSet<String>(
            Arrays.asList(new String[] {Features.PARKING, Features.IGNITION, Features.HOOK_PARKING,
                Features.SLOT_CAR_MENU, Features.UI_PAGES, Features.SLOT_STATUS_BAR, Features.UI_ICONS,
                Features.UI_INPUTS})));

    private final BundleContext ctx;
    private final Clock clock;
    private final int statusMaxChars;
    private final File sdkIconDir;
    private EventPump pump; // guarded by this
    private IgnitionBridge ignition; // guarded by this
    private ServiceWaiter waiter; // guarded by this
    private UiApplier ui; // guarded by this
    private volatile ParkingBridge parking;

    public HqcPlatform(BundleContext ctx) {
        this(ctx, null);
    }

    /** {@code clock} only times the service waits for the log; null is fine. */
    HqcPlatform(BundleContext ctx, Clock clock) {
        this(ctx, clock, HqcUiIds.STATUS_MAX_CHARS);
    }

    /** {@code statusMaxChars}: {@code ui.status.max.chars} for the popup-65 status line. No built-in icons. */
    HqcPlatform(BundleContext ctx, Clock clock, int statusMaxChars) {
        this(ctx, clock, statusMaxChars, null);
    }

    /** {@code sdkIconDir}: the installed built-in icon set ({@code RuntimeConfig.sdkIconDir()}); null for none. */
    HqcPlatform(BundleContext ctx, Clock clock, int statusMaxChars, File sdkIconDir) {
        this.ctx = ctx;
        this.clock = clock;
        this.statusMaxChars = statusMaxChars;
        this.sdkIconDir = sdkIconDir;
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
     * {@link PlatformHost#featuresChanged()}. With {@code SdkGlobals.uiEnabled} (fixed for the boot) also
     * SLOT_CAR_MENU, UI_PAGES, SLOT_STATUS_BAR, UI_ICONS and UI_INPUTS (every native input widget exists, Task 7).
     */
    public Set<String> features() {
        boolean hooked = SdkGlobals.wrappingEnabled && RawServices.get(DSICarParkingSystem.class.getName()) != null;
        if (SdkGlobals.uiEnabled) {
            return hooked ? FEATURES_HOOKED_UI : FEATURES_UI;
        }
        return hooked ? FEATURES_HOOKED : FEATURES;
    }

    /**
     * Starts the event pump and the service waits; does not block. A second start is ignored. With
     * {@code SdkGlobals.uiEnabled} (never under the wrap-loop latch) it also waits for {@link #FRAMEWORK_ACCESS} and
     * then starts the {@code mibsdk-ui} applier; otherwise no SDK UI is ever drawn and popup 65 is never touched.
     * Reads the built-in icon index here, on the calling SDK thread, into {@code SdkGlobals.builtinIcons}.
     */
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
        if (SdkGlobals.uiEnabled) {
            SdkGlobals.builtinIcons = BuiltinIcons.read(sdkIconDir);
            if (clock == null) {
                SdkGlobals.LOG.append("WARN", "mibsdk.hqc", "no clock: the SDK UI applier does not start");
                return;
            }
            final ServiceWaiter mine = w;
            w.await(FRAMEWORK_ACCESS, RETRY_MS, new ServiceWaiter.Callback() {
                public void onAvailable(Object service) {
                    startUi(mine, service);
                }
            });
        }
    }

    /** On the waiter thread: creates and starts the applier unless this start was already stopped. */
    private void startUi(ServiceWaiter startedBy, Object frameworkAccess) {
        synchronized (this) {
            if (waiter != startedBy) {
                return; // stopped meanwhile (ServiceWaiter's documented late-delivery residual)
            }
            UiApplier a = UiApplier.create(frameworkAccess, clock, SdkGlobals.LOG, statusMaxChars);
            ui = a;
            a.start();
        }
    }

    /**
     * Ends the waits, stops the UI applier (waits briefly for its teardown), unregisters the listeners, stops the
     * pump. Never blocks on the car. Idempotent.
     */
    public void stop() {
        ServiceWaiter w;
        ParkingBridge pb;
        IgnitionBridge ib;
        EventPump p;
        UiApplier u;
        synchronized (this) {
            w = waiter;
            pb = parking;
            ib = ignition;
            p = pump;
            u = ui;
            waiter = null;
            ignition = null;
            ui = null;
        }
        if (w != null) {
            w.stop();
        }
        if (u != null) {
            u.stop(); // tears down the SDK rows, status line and pages: nothing stale stays on screen
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
