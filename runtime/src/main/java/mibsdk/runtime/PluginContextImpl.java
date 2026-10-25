package mibsdk.runtime;

import java.io.File;
import java.util.List;
import java.util.Set;
import mibsdk.api.Events;
import mibsdk.api.Features;
import mibsdk.api.HookObserved;
import mibsdk.api.HookPoint;
import mibsdk.api.Hooks;
import mibsdk.api.Listener;
import mibsdk.api.Log;
import mibsdk.api.Net;
import mibsdk.api.Parking;
import mibsdk.api.PermissionDeniedException;
import mibsdk.api.Permissions;
import mibsdk.api.PluginContext;
import mibsdk.api.Scheduler;
import mibsdk.api.Storage;
import mibsdk.api.Subscription;
import mibsdk.api.Ui;
import mibsdk.api.Vehicle;
import mibsdk.platform.HookTable;
import mibsdk.platform.ParkingIo;

/**
 * A plugin's view of the SDK. Every {@link Subscription} handed out (events, timers) is tracked in the
 * plugin's {@link Registrations} so disable/quarantine/reload cancels it.
 *
 * <p>Locking rule: no object reachable by plugin code (this context, its Log/Events/Scheduler, the
 * storage, the returned subscriptions) is ever used as a monitor by SDK code; they lock private objects.
 * Otherwise a plugin could hold the monitor and wedge the runtime's control thread.
 *
 * <p>Permissions: {@link #granted} and the checks in {@link #vehicle()}, {@link #hooks()},
 * {@code events().on(HookObserved.class)} and {@code parking().overrideSoundDirections} use the plugin's
 * effective permissions, fixed when it was enabled: the permissions its manifest declares that the owner
 * also granted (a granted but undeclared permission is not usable). {@code vehicle().parking()} throws
 * {@link UnsupportedOperationException} while the adapter has no {@link Features#PARKING}.
 *
 * <p>Features are {@link LiveFeatures}, read at call time: {@link #has}, hook registration and
 * {@code vehicle().parking()} see a feature the adapter learns after this plugin was enabled. The first
 * {@code vehicle().parking()} that finds {@link Features#PARKING} and the adapter's parking I/O builds the
 * plugin's {@link Parking}, which is then kept.
 */
final class PluginContextImpl implements PluginContext {

    /** The adapter's parking I/O as of now, or null (no {@link Features#PARKING}, or the adapter failed). */
    interface ParkingSource {
        ParkingIo parkingIo();
    }

    /**
     * Wraps a subscription so cancelling it also forgets it in the plugin's {@link Registrations}. The
     * plugin holds this object, so its state is guarded by a private lock, never {@code this}: a plugin
     * doing {@code synchronized (subscription)} must not block the runtime's cancelAll().
     */
    static final class Tracked implements Subscription {
        private final Registrations registrations;
        private final Object lock = new Object();
        private Subscription inner; // guarded by lock
        private boolean cancelled; // guarded by lock

        Tracked(Registrations registrations) {
            this.registrations = registrations;
        }

        void attach(Subscription s) {
            boolean c;
            synchronized (lock) {
                inner = s;
                c = cancelled;
            }
            if (c) {
                s.cancel();
            }
        }

        @Override
        public void cancel() {
            Subscription s;
            synchronized (lock) {
                cancelled = true;
                s = inner;
            }
            if (s != null) {
                s.cancel();
            }
            registrations.remove(this);
        }
    }

    private final String id;
    private final RingLog ringLog;
    private final PluginStorage storage;
    private final EventBus bus;
    private final SerialExecutor exec;
    private final PluginScheduler pluginScheduler;
    private final Registrations registrations;
    private final LiveFeatures features;
    private final Set<String> granted;
    private final Hooks hooks;
    private final ParkingSource parkingSource;
    private final OverrideLedger ledger;
    private final Ui ui;
    private final Net net;
    private final Object parkingLock = new Object();
    private Parking parking; // guarded by parkingLock; built once, then kept

    private final Log log = new Log() {
        @Override
        public void info(String m) {
            ringLog.append("INFO", id, m);
        }

        @Override
        public void warn(String m) {
            ringLog.append("WARN", id, m);
        }

        @Override
        public void error(String m, Throwable t) {
            ringLog.append("ERROR", id, t == null ? m : m + ": " + SdkRuntime.describe(t));
        }
    };

    private final Events events = new Events() {
        @Override
        public <E> Subscription on(Class<E> type, Listener<? super E> listener) {
            if (type == null || listener == null) {
                throw new IllegalArgumentException("type and listener are required");
            }
            if (type.isAssignableFrom(HookObserved.class)) {
                // Hook observations travel on this bus; they are only for plugins allowed to hook.
                requirePermission(HooksImpl.permissionFor(HookPoint.PARKING_SOUND_SET));
            }
            Tracked t = new Tracked(registrations);
            registrations.add(t);
            t.attach(bus.subscribe(id, type, listener, exec));
            return t;
        }
    };

    private final Scheduler scheduler = new Scheduler() {
        @Override
        public Subscription after(long delayMs, final Runnable task) {
            if (task == null) {
                throw new IllegalArgumentException("task is required");
            }
            final Tracked t = new Tracked(registrations);
            registrations.add(t);
            t.attach(pluginScheduler.after(delayMs, new Runnable() {
                public void run() {
                    try {
                        task.run();
                    } finally {
                        registrations.remove(t); // a finished one-shot needs no cancelling
                    }
                }
            }));
            return t;
        }

        @Override
        public Subscription every(long periodMs, Runnable task) {
            if (task == null) {
                throw new IllegalArgumentException("task is required");
            }
            Subscription inner = pluginScheduler.every(periodMs, task); // validates periodMs first
            Tracked t = new Tracked(registrations);
            registrations.add(t);
            t.attach(inner);
            return t;
        }
    };

    private final Vehicle vehicle;

    /**
     * @param granted the effective permissions (manifest permissions that are also granted)
     * @param parking asked for the adapter's parking I/O on each {@code vehicle().parking()} until it has one
     * @param declaredIcons the manifest's {@code icons}
     * @param iconDir where the plugin's icons are installed ({@link RuntimeConfig#pluginIconDir})
     */
    PluginContextImpl(String id, RingLog ringLog, PluginStorage storage, EventBus bus, SerialExecutor exec,
            PluginScheduler pluginScheduler, Registrations registrations, LiveFeatures features,
            Set<String> granted, HookTable hookTable, ParkingSource parking, OverrideLedger ledger, UiHub uiHub,
            Clock clock, List<String> declaredIcons, File iconDir) {
        this.id = id;
        this.ringLog = ringLog;
        this.storage = storage;
        this.bus = bus;
        this.exec = exec;
        this.pluginScheduler = pluginScheduler;
        this.registrations = registrations;
        this.features = features;
        this.granted = granted;
        this.hooks = new HooksImpl(hookTable, bus, id, exec, registrations, features, granted);
        this.parkingSource = parking;
        this.ledger = ledger;
        this.net = new NetImpl(granted, clock);
        this.ui = new UiImpl(id, uiHub, features, granted, exec, registrations, declaredIcons, iconDir);
        this.vehicle = new Vehicle() {
            @Override
            public Parking parking() {
                return parkingNow();
            }
        };
    }

    private Parking parkingNow() {
        synchronized (parkingLock) {
            if (parking == null && features.has(Features.PARKING)) {
                ParkingIo io = parkingSource.parkingIo();
                if (io != null) {
                    parking = new ParkingImpl(id, io, ledger, registrations, granted);
                }
            }
            if (parking == null) {
                throw new UnsupportedOperationException("parking is not available on this platform");
            }
            return parking;
        }
    }

    @Override
    public String pluginId() {
        return id;
    }

    @Override
    public Log log() {
        return log;
    }

    @Override
    public Storage storage() {
        return storage;
    }

    @Override
    public Events events() {
        return events;
    }

    @Override
    public Hooks hooks() {
        return hooks;
    }

    @Override
    public Vehicle vehicle() {
        requirePermission(Permissions.VEHICLE_READ);
        return vehicle;
    }

    @Override
    public Scheduler scheduler() {
        return scheduler;
    }

    @Override
    public Ui ui() {
        return ui;
    }

    @Override
    public Net net() {
        requirePermission(Permissions.NETWORK);
        return net;
    }

    @Override
    public boolean has(String feature) {
        return features.has(feature);
    }

    @Override
    public boolean granted(String permission) {
        return permission != null && granted.contains(permission);
    }

    private void requirePermission(String permission) {
        if (!granted.contains(permission)) {
            throw new PermissionDeniedException(permission);
        }
    }
}
