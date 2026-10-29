package mibsdk.api;

/**
 * Vehicle and hook events. Obtain it from {@link PluginContext#events()}.
 *
 * <p>Events are delivered after the fact, asynchronously, on the plugin's worker thread. They are not a
 * way to veto or change stock behaviour; use {@link Hooks} for that.
 *
 * <p>A listener stays registered until the plugin is disabled or its {@link Subscription} is cancelled.
 */
public interface Events {

    /**
     * Subscribes to events of {@code type}.
     *
     * @param type the event class, for example {@code IgnitionChanged.class}
     * @param listener called on the plugin's worker thread
     * @return a handle that removes the listener
     * @since api 1
     */
    <E> Subscription on(Class<E> type, Listener<? super E> listener);
}
