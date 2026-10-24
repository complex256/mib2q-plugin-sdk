package mibsdk.api;

/** Everything a plugin may touch. Obtained in {@link MibPlugin#onEnable(PluginContext)}. */
public interface PluginContext {

    String pluginId();

    Log log();

    Storage storage();

    Events events();

    Hooks hooks();

    Vehicle vehicle();

    Scheduler scheduler();

    /** Declarative pages, Car-menu rows and the status-line item. */
    Ui ui();

    /** Loopback TCP queries. Throws {@link PermissionDeniedException} without {@link Permissions#NETWORK}. */
    Net net();

    /** True if the running firmware adapter supports the feature (see {@link Features}). */
    boolean has(String feature);

    /** True if the permission was granted to this plugin (see {@link Permissions}). */
    boolean granted(String permission);
}
