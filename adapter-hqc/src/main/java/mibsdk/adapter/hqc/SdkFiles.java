package mibsdk.adapter.hqc;

/** Paths on the unit. */
public final class SdkFiles {

    /**
     * Install root ({@code SdkRoots.install()}): sdk.properties, grants.properties, SAFE_MODE, plugins/, stock/.
     * {@code /mnt/app} is read-only while the HMI runs; only the installer writes here (after {@code mount -uw}).
     */
    public static final String BASE = "/mnt/app/eso/hmi/lsd/mibsdk";

    /**
     * Persistent root ({@code SdkRoots.persist()}): boot.state, ledger.properties, data/. qnx6, read-write from
     * boot (before LSD starts) and shared with the stock persistence database: the runtime caps data/.
     */
    public static final String PERSIST = "/mnt/persist/mibsdk";

    /** RAM root ({@code SdkRoots.ram()}): cache/. {@code /tmp} holds no directories, {@code /ramdisk} does. */
    public static final String RAM = "/ramdisk/mibsdk";

    /** Firmware version file; its {@code HMI = ...} line selects the adapter. */
    public static final String VERSION_INFO = "/ifs/version_info.txt";

    private SdkFiles() {
    }
}
