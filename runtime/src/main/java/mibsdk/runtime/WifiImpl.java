package mibsdk.runtime;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import mibsdk.api.Listener;
import mibsdk.api.PermissionDeniedException;
import mibsdk.api.Permissions;
import mibsdk.api.Subscription;
import mibsdk.api.Wifi;
import mibsdk.api.WifiChanged;
import mibsdk.api.WifiNetwork;
import mibsdk.api.WifiStatus;
import mibsdk.platform.WifiIo;

/**
 * One plugin's view of the Wi-Fi uplink. Calls delegate to the adapter's {@link WifiIo}; {@link #onChanged}
 * subscribes to {@link WifiChanged} on the plugin's executor, tracked in the plugin's {@link Registrations}.
 */
final class WifiImpl implements Wifi {

    private final String pluginId;
    private final WifiIo io;
    private final EventBus bus;
    private final SerialExecutor exec;
    private final Registrations registrations;
    private final Set<String> granted;

    WifiImpl(String pluginId, WifiIo io, EventBus bus, SerialExecutor exec, Registrations registrations,
            Set<String> granted) {
        this.pluginId = pluginId;
        this.io = io;
        this.bus = bus;
        this.exec = exec;
        this.registrations = registrations;
        this.granted = granted;
    }

    private void check() {
        if (!granted.contains(Permissions.NETWORK_WIFI)) {
            throw new PermissionDeniedException(Permissions.NETWORK_WIFI);
        }
    }

    @Override
    public WifiStatus status() {
        check();
        return io.status();
    }

    @Override
    public List<WifiNetwork> scan(int timeoutMs) {
        check();
        List<WifiNetwork> found = io.scan(timeoutMs);
        return found == null ? Collections.<WifiNetwork>emptyList() : found;
    }

    @Override
    public void connect(String ssid, String password) {
        check();
        if (ssid == null || ssid.length() == 0) {
            throw new IllegalArgumentException("ssid is required");
        }
        io.connect(ssid, null, password, password != null && password.length() > 0);
    }

    @Override
    public void disconnect() {
        check();
        io.disconnect();
    }

    @Override
    public void forget(String ssid) {
        check();
        if (ssid == null || ssid.length() == 0) {
            return;
        }
        io.forget(ssid);
    }

    @Override
    public Subscription onChanged(Listener<WifiChanged> listener) {
        check();
        if (listener == null) {
            throw new IllegalArgumentException("listener is required");
        }
        PluginContextImpl.Tracked t = new PluginContextImpl.Tracked(registrations);
        registrations.add(t);
        t.attach(bus.subscribe(pluginId, WifiChanged.class, listener, exec));
        return t;
    }
}
