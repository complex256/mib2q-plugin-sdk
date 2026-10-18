package mibsdk.api;

public interface Parking {

    SoundDirections soundDirections();

    /**
     * Forces the parking sound directions for the given scope. Needs {@link Permissions#VEHICLE_PARKING_WRITE}.
     *
     * @throws OverrideConflictException if another plugin already holds the override
     * @throws PermissionDeniedException if the permission is not granted
     */
    VehicleOverride overrideSoundDirections(SoundDirections value, OverrideScope scope)
            throws OverrideConflictException;
}
