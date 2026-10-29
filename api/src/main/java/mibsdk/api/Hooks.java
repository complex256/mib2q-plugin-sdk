package mibsdk.api;

/**
 * Declarative parking hooks. Obtain them from {@link PluginContext#hooks()}.
 *
 * <p>Hooks need the {@code hooks.parking} permission and {@link Features#HOOK_PARKING}. A veto is a flag
 * the stock side reads; plugin code never runs on a stock thread.
 *
 * @since api 1
 */
public interface Hooks {

    /**
     * Blocks the stock behaviour at {@code point} while active.
     *
     * @param point the hook point
     * @return a veto you can turn on and off
     * @throws UnsupportedOperationException if the unit has no parking hooks
     */
    Veto veto(HookPoint point);

    /**
     * Observes values passing {@code point}.
     *
     * @param point the hook point
     * @param l called on the plugin's worker thread
     * @return a handle that removes the observation
     * @throws UnsupportedOperationException if the unit has no parking hooks
     */
    Subscription observe(HookPoint point, Listener<HookObserved> l);
}
