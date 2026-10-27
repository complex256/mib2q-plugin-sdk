package mibsdk.api;

/**
 * Base class of every plugin. The SDK instantiates the declared main class (through its no-argument constructor) and
 * calls the lifecycle hooks, both on the plugin's own worker thread.
 *
 * <p>A plugin is constructed fresh for each run: after a reload or a re-enable it is a new instance, with no fields
 * carried over. Persistent state belongs in {@link Storage}.
 */
public abstract class MibPlugin {

    /**
     * Called once when the plugin is enabled. Register listeners, hooks and timers here; everything registered belongs
     * to this run and is cancelled when the plugin is disabled.
     *
     * <p>An exception thrown here fails the enable: the SDK logs it, releases whatever was registered, and calls
     * {@link #onDisable()}, so fields assigned after the failure point are null there.
     *
     * @param ctx this plugin's context; the only way to reach the SDK
     * @throws Exception anything the plugin cannot continue without; the plugin is then {@code FAILED}
     */
    protected abstract void onEnable(PluginContext ctx) throws Exception;

    /**
     * Called when the plugin is disabled or unloaded, including after {@link #onEnable} threw. By then the SDK has
     * already cancelled every subscription, timer, veto and override the plugin registered; anything registered here
     * is cancelled immediately. Storage written here is still saved.
     *
     * <p>Guard every field this method touches: a plugin that failed part-way through {@code onEnable} reaches it with
     * the later assignments still null. Run time is bounded like any other callback (30 s, then the plugin is
     * quarantined as hung).
     *
     * @throws Exception logged as an error; the disable goes ahead either way
     */
    protected void onDisable() throws Exception {
    }
}
