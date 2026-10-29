package mibsdk.api;

/**
 * A point where a plugin may observe or veto stock behaviour.
 *
 * <p>Parking points need the {@code hooks.parking} permission and {@link Features#HOOK_PARKING}.
 *
 * @since api 1
 */
public enum HookPoint {

    /** A parking-sound value is about to be applied. */
    PARKING_SOUND_SET,

    /** A parking-sound value was observed. */
    PARKING_SOUND_UPDATE
}
