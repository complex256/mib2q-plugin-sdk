package mibsdk.api;

/**
 * A callback for an event or a hook observation.
 *
 * <p>Listeners run on the plugin's worker thread, one at a time. An exception is logged and counts toward
 * quarantine.
 *
 * @param <E> the event type
 * @since api 1
 */
public interface Listener<E> {

    /**
     * Handles one event.
     *
     * @param event the event
     * @throws Exception logged and counted as a callback failure
     */
    void on(E event) throws Exception;
}
