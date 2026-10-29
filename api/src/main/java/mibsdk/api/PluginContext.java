package mibsdk.api;

/**
 * Everything a plugin may touch. Obtained in {@link MibPlugin#onEnable(PluginContext)}.
 *
 * <p>The context belongs to one run of the plugin. Do not keep it after {@link MibPlugin#onDisable()}.
 *
 * @since api 1
 */
public interface PluginContext {

    /** This plugin's id from {@code plugin.json}. */
    String pluginId();

    /** The plugin's log, mirrored to {@code /tmp/mibsdk.log} on the unit. */
    Log log();

    /** Persistent key/value storage, capped per plugin and for all plugins. */
    Storage storage();

    /** Vehicle and hook events, delivered asynchronously on the worker. */
    Events events();

    /** Declarative parking hooks. Needs the {@code hooks.parking} permission. */
    Hooks hooks();

    /** Vehicle state. Needs the {@code vehicle.read} permission. */
    Vehicle vehicle();

    /** Delayed and periodic tasks on the plugin's worker thread. */
    Scheduler scheduler();

    /**
     * Declarative pages, Car-menu rows, the status-line item, toasts and dialogs.
     *
     * @since api 2
     */
    Ui ui();

    /**
     * Loopback TCP queries. Needs the {@code network} permission.
     *
     * @since api 2
     */
    Net net();

    /**
     * Whether the running firmware adapter supports a feature.
     *
     * @param feature one of the {@link Features} constants
     * @return true when this unit supports it at this boot
     */
    boolean has(String feature);

    /**
     * Whether the user granted a permission.
     *
     * @param permission a permission id from {@link Permissions}
     * @return true when it was granted
     */
    boolean granted(String permission);
}
