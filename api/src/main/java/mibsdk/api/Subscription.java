package mibsdk.api;

/**
 * A registration that can be cancelled: a page, a Car row, a status item, a timer, an event listener, a
 * veto or an override.
 *
 * <p>Cancelling twice, or after the plugin is disabled, is harmless.
 *
 * @since api 1
 */
public interface Subscription {

    /** Removes the registration. */
    void cancel();
}
