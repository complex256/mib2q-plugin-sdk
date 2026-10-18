package mibsdk.api;

/** Base class of every plugin. The SDK instantiates the declared main class and calls the lifecycle hooks. */
public abstract class MibPlugin {

    /** Called once when the plugin is enabled. Register listeners, hooks and timers here. */
    protected abstract void onEnable(PluginContext ctx) throws Exception;

    /**
     * Called when the plugin is disabled or unloaded. By then the SDK has already cancelled every
     * subscription, timer, veto and override the plugin registered; anything registered here is cancelled
     * immediately. Storage written here is still saved.
     */
    protected void onDisable() throws Exception {
    }
}
