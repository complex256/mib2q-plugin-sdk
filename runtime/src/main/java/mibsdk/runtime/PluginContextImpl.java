package mibsdk.runtime;

import java.util.Set;
import mibsdk.api.Events;
import mibsdk.api.HookObserved;
import mibsdk.api.HookPoint;
import mibsdk.api.Hooks;
import mibsdk.api.Listener;
import mibsdk.api.Log;
import mibsdk.api.Parking;
import mibsdk.api.PluginContext;
import mibsdk.api.Scheduler;
import mibsdk.api.Storage;
import mibsdk.api.Subscription;
import mibsdk.api.Vehicle;
import mibsdk.api.Veto;

/**
 * A plugin's view of the SDK. Every {@link Subscription} handed out (events, timers) is tracked in the
 * plugin's {@link Registrations} so disable/quarantine/reload cancels it.
 *
 * <p>Locking rule: no object reachable by plugin code (this context, its Log/Events/Scheduler, the
 * storage, the returned subscriptions) is ever used as a monitor by SDK code; they lock private objects.
 * Otherwise a plugin could hold the monitor and wedge the runtime's control thread.
 *
 * <p>Until Task 10/11: {@link #hooks()} returns a stub whose methods throw
 * {@link UnsupportedOperationException}; {@link #vehicle()} returns a {@link Vehicle} whose
 * {@code parking()} throws {@link UnsupportedOperationException}; {@link #granted} is false for every
 * permission (no grants are loaded yet).
 */
final class PluginContextImpl implements PluginContext {

    /**
     * Wraps a subscription so cancelling it also forgets it in the plugin's {@link Registrations}. The
     * plugin holds this object, so its state is guarded by a private lock, never {@code this}: a plugin
     * doing {@code synchronized (subscription)} must not block the runtime's cancelAll().
     */
    private static final class Tracked implements Subscription {
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
    private final Set<String> features;

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

    private static final Hooks HOOKS_UNAVAILABLE = new Hooks() {
        @Override
        public Veto veto(HookPoint point) {
            throw new UnsupportedOperationException("hooks are not available in this SDK build");
        }

        @Override
        public Subscription observe(HookPoint point, Listener<HookObserved> l) {
            throw new UnsupportedOperationException("hooks are not available in this SDK build");
        }
    };

    private static final Vehicle VEHICLE_UNAVAILABLE = new Vehicle() {
        @Override
        public Parking parking() {
            throw new UnsupportedOperationException("parking is not available in this SDK build");
        }
    };

    PluginContextImpl(String id, RingLog ringLog, PluginStorage storage, EventBus bus, SerialExecutor exec,
            PluginScheduler pluginScheduler, Registrations registrations, Set<String> features) {
        this.id = id;
        this.ringLog = ringLog;
        this.storage = storage;
        this.bus = bus;
        this.exec = exec;
        this.pluginScheduler = pluginScheduler;
        this.registrations = registrations;
        this.features = features;
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
        return HOOKS_UNAVAILABLE;
    }

    @Override
    public Vehicle vehicle() {
        return VEHICLE_UNAVAILABLE;
    }

    @Override
    public Scheduler scheduler() {
        return scheduler;
    }

    @Override
    public boolean has(String feature) {
        return features.contains(feature);
    }

    @Override
    public boolean granted(String permission) {
        return false;
    }
}
