package mibsdk.api;

/**
 * Vehicle services. Obtain it from {@link PluginContext#vehicle()}.
 *
 * <p>Reading needs the {@code vehicle.read} permission. A unit without the parking system reports
 * {@link Features#PARKING} as false; {@link #parking()} then throws {@link UnsupportedOperationException}.
 *
 * @since api 1
 */
public interface Vehicle {

    /**
     * The parking-sound system.
     *
     * @throws UnsupportedOperationException if the unit has no parking system
     */
    Parking parking();
}
