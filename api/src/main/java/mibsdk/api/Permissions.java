package mibsdk.api;

/**
 * Permission ids a plugin declares in {@code plugin.json}. The build refuses a call whose permission is not declared,
 * and the runtime refuses it when the owner has not granted it: a permission must be both declared and granted, and a
 * plugin that misses one is not enabled at all ({@code BLOCKED_PERMISSIONS}). Revoking any single permission stops the
 * whole plugin. The owner sees the approval text from {@code api/permissions.json}.
 */
public final class Permissions {

    /** Read vehicle state through {@link PluginContext#vehicle()} (and so {@link Vehicle#parking()}). */
    public static final String VEHICLE_READ = "vehicle.read";
    /** Change parking-sensor settings: {@link Parking#overrideSoundDirections}. */
    public static final String VEHICLE_PARKING_WRITE = "vehicle.parking.write";
    /**
     * Watch and override parking-system messages: {@link PluginContext#hooks()} ({@link Hooks#veto},
     * {@link Hooks#observe}) and {@code events().on(HookObserved.class, ...)}, which travels on the event bus but is
     * gated like a hook.
     */
    public static final String HOOKS_PARKING = "hooks.parking";
    /**
     * Unrestricted: reflection, raw sockets and stock HMI classes. No api call needs it; without it the build refuses
     * those constructs. The owner sees it as a danger permission, and the SDK cannot contain a plugin that has it.
     */
    public static final String PLATFORM_UNRESTRICTED = "platform.unrestricted";
    /** Add a row to the Car menu: {@link Ui#carMenuRow}. */
    public static final String UI_SLOT_CAR_MENU = "ui.slot.carmenu";
    /** Show a status-line item or a toast: {@link Ui#statusItem}, {@link Ui#toast}. */
    public static final String UI_SLOT_STATUS_BAR = "ui.slot.statusbar";
    /** Query loopback services on the unit: {@link PluginContext#net()}. */
    public static final String NETWORK = "network";
    /**
     * Connect to hosts outside this unit through {@link PluginContext#net()}. Without it the network API
     * accepts loopback hosts only. The unit has no TLS stack, so this is plain TCP.
     */
    public static final String NETWORK_OUTBOUND = "network.outbound";

    /**
     * Use the Wi-Fi client uplink: {@link PluginContext#wifi()}.
     *
     * @since api 7
     */
    public static final String NETWORK_WIFI = "network.wifi";

    private Permissions() {
    }
}
