package mibsdk.api;

/**
 * The parking-sound system. Obtained from {@link Vehicle#parking()}.
 *
 * <p>Reading needs {@code vehicle.read}; overriding needs {@code vehicle.parking.write}.
 *
 * @since api 1
 */
public interface Parking {

    /**
     * The current sound directions.
     *
     * @throws UnsupportedOperationException if the unit has no parking system
     */
    SoundDirections soundDirections();

    /**
     * Forces the parking sound directions for the given scope.
     *
     * <p>Only one plugin may hold the override. The SDK restores the previous value when the plugin is
     * disabled, quarantined or uninstalled, and when the ignition goes off for
     * {@link OverrideScope#UNTIL_IGNITION_OFF}.
     *
     * @param value the directions to apply
     * @param scope how long the override lasts
     * @return a handle that releases the override
     * @throws OverrideConflictException if another plugin already holds it
     * @throws PermissionDeniedException if the permission is not granted
     */
    VehicleOverride overrideSoundDirections(SoundDirections value, OverrideScope scope)
            throws OverrideConflictException;
}
