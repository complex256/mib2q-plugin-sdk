package mibsdk.api;

/**
 * A declarative veto at a {@link HookPoint}. Obtain one from {@link Hooks#veto(HookPoint)}.
 *
 * <p>A veto is a flag the stock side reads; no plugin code runs on a stock thread. While it is active, the
 * stock behaviour at that point is blocked. The SDK releases it when the plugin is disabled.
 *
 * @since api 1
 */
public interface Veto {

    /**
     * Turns the veto on or off.
     *
     * @param active true to block the stock behaviour
     */
    void setActive(boolean active);

    /** Whether the veto is currently on. */
    boolean isActive();

    /** Turns the veto off and releases it. */
    void release();
}
