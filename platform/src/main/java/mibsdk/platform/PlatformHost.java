package mibsdk.platform;

/** What the runtime offers to a started {@link PlatformAdapter}. */
public interface PlatformHost {

    /** Publishes an API event (for example {@code ParkingSoundChanged}) to subscribed plugins. */
    void publish(Object apiEvent);

    HookTable hooks();

    /**
     * The adapter's {@link PlatformAdapter#features()} may have changed (for example a DSI service arrived after
     * the plugin load). The runtime reads them again on its own thread. Never blocks; callable from any adapter
     * thread (never a stock one: the runtime runs no plugin code here, but takes short locks).
     */
    void featuresChanged();

    void log(String msg, Throwable t);
}
