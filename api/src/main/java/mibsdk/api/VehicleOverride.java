package mibsdk.api;

/**
 * A held vehicle override. Obtain one from {@link Parking#overrideSoundDirections}.
 *
 * <p>The SDK restores the previous value when the plugin is disabled, quarantined, uninstalled, or the
 * ignition goes off (for {@link OverrideScope#UNTIL_IGNITION_OFF}). Call {@link #release()} to restore it
 * earlier.
 *
 * @since api 1
 */
public interface VehicleOverride {

    /** Whether the override is still held. */
    boolean isActive();

    /** Restores the previous value and releases the override. */
    void release();
}
