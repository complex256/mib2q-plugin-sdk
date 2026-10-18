package mibsdk.runtime;

import java.util.Set;
import mibsdk.api.Features;
import mibsdk.api.HookObserved;
import mibsdk.api.HookPoint;
import mibsdk.api.Hooks;
import mibsdk.api.Listener;
import mibsdk.api.PermissionDeniedException;
import mibsdk.api.Permissions;
import mibsdk.api.Subscription;
import mibsdk.api.Veto;
import mibsdk.platform.HookTable;

/**
 * One plugin's {@link Hooks}. A veto is only a flag in the {@link HookTable}; stock threads read it
 * without running any plugin code. Observation callbacks are queued on the plugin's executor by the bus.
 * Every veto and observer is tracked in the plugin's {@link Registrations}, so disable or quarantine
 * releases them. Objects handed to the plugin lock only private monitors, never themselves.
 */
final class HooksImpl implements Hooks {

    /** The permission a hook point needs. */
    static String permissionFor(HookPoint point) {
        return Permissions.HOOKS_PARKING; // both points are parking points
    }

    private static String featureFor(HookPoint point) {
        return Features.HOOK_PARKING;
    }

    private final HookTable table;
    private final EventBus bus;
    private final String pluginId;
    private final SerialExecutor exec;
    private final Registrations registrations;
    private final Set<String> features;
    private final Set<String> granted;

    HooksImpl(HookTable table, EventBus bus, String pluginId, SerialExecutor exec, Registrations registrations,
            Set<String> features, Set<String> granted) {
        this.table = table;
        this.bus = bus;
        this.pluginId = pluginId;
        this.exec = exec;
        this.registrations = registrations;
        this.features = features;
        this.granted = granted;
    }

    @Override
    public Veto veto(HookPoint point) {
        check(point);
        VetoHandle v = new VetoHandle(table, table.addVeto(point), registrations);
        registrations.add(v.releaser()); // closed registrations release at once
        return v;
    }

    @Override
    public Subscription observe(final HookPoint point, final Listener<HookObserved> l) {
        check(point);
        if (l == null) {
            throw new IllegalArgumentException("listener is required");
        }
        PluginContextImpl.Tracked t = new PluginContextImpl.Tracked(registrations);
        registrations.add(t);
        t.attach(bus.subscribe(pluginId, HookObserved.class, new Listener<HookObserved>() {
            public void on(HookObserved o) throws Exception {
                if (o.point() == point) {
                    l.on(o);
                }
            }
        }, exec));
        return t;
    }

    private void check(HookPoint point) {
        if (point == null) {
            throw new IllegalArgumentException("point is required");
        }
        String permission = permissionFor(point);
        if (!granted.contains(permission)) {
            throw new PermissionDeniedException(permission);
        }
        if (!features.contains(featureFor(point))) {
            throw new UnsupportedOperationException("hook point " + point + " is not available on this platform");
        }
    }

    /** The plugin's handle on one veto; state is guarded by a private lock. */
    private static final class VetoHandle implements Veto {
        private final HookTable table;
        private final Object token;
        private final Registrations registrations;
        private final Object lock = new Object();
        private boolean active; // guarded by lock
        private boolean released; // guarded by lock
        private final Subscription releaser = new Subscription() {
            public void cancel() {
                releaseNow();
            }
        };

        VetoHandle(HookTable table, Object token, Registrations registrations) {
            this.table = table;
            this.token = token;
            this.registrations = registrations;
        }

        Subscription releaser() {
            return releaser;
        }

        @Override
        public void setActive(boolean on) {
            synchronized (lock) {
                if (released) {
                    return;
                }
                active = on;
                table.setVetoActive(token, on);
            }
        }

        @Override
        public boolean isActive() {
            synchronized (lock) {
                return active && !released;
            }
        }

        @Override
        public void release() {
            releaseNow();
            registrations.remove(releaser);
        }

        private void releaseNow() {
            synchronized (lock) {
                if (released) {
                    return;
                }
                released = true;
                active = false;
                table.removeVeto(token);
            }
        }
    }
}
