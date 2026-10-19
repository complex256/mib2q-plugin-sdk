package mibsdk.adapter.hqc;

/** Paths on the unit. */
public final class SdkFiles {

    /** SDK base directory: sdk.properties, boot.state, SAFE_MODE, plugins/, ... */
    public static final String BASE = "/mnt/app/eso/hmi/lsd/mibsdk";

    /** Firmware version file; its {@code HMI = ...} line selects the adapter. */
    public static final String VERSION_INFO = "/ifs/version_info.txt";

    private SdkFiles() {
    }
}
