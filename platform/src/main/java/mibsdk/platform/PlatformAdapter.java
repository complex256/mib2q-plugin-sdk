package mibsdk.platform;

import java.util.Set;

/** A firmware-specific implementation of the platform features. One adapter is selected per HMI version. */
public interface PlatformAdapter {

    String id();

    /** True if this adapter knows the given HMI version string (compared exactly). */
    boolean supports(String hmiVersion);

    /** The {@code mibsdk.api.Features} constants this adapter provides. */
    Set<String> features();

    void start(PlatformHost host) throws Exception;

    void stop();

    /** Null if the adapter does not provide {@code Features.PARKING}. */
    ParkingIo parking();
}
