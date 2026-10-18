package mibsdk.api;

/** Base class of every plugin. The SDK instantiates the declared main class and calls the lifecycle hooks. */
public abstract class MibPlugin {

    /** Called once when the plugin is enabled. Register listeners, hooks and timers here. */
    protected abstract void onEnable(PluginContext ctx) throws Exception;

    /** Called when the plugin is disabled or unloaded. Subscriptions are cancelled by the SDK afterwards. */
    protected void onDisable() throws Exception {
    }
}
