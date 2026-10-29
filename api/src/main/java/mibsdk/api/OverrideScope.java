package mibsdk.api;

/**
 * How long a vehicle override lasts.
 *
 * @since api 1
 */
public enum OverrideScope {

    /** Restored when the ignition goes off. */
    UNTIL_IGNITION_OFF,

    /** Survives a reboot and is re-claimed by the plugin when it starts again. */
    PERSISTENT
}
