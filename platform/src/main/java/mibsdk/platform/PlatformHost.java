package mibsdk.platform;

/** What the runtime offers to a started {@link PlatformAdapter}. */
public interface PlatformHost {

    /** Publishes an API event (for example {@code ParkingSoundChanged}) to subscribed plugins. */
    void publish(Object apiEvent);

    HookTable hooks();

    void log(String msg, Throwable t);
}
