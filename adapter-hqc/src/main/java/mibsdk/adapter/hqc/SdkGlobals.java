package mibsdk.adapter.hqc;

import mibsdk.adapter.hqc.ui.DrawerAssignment;
import mibsdk.adapter.hqc.ui.RowModels;
import mibsdk.platform.HookTable;
import mibsdk.platform.UiTable;
import mibsdk.runtime.RingLog;

/**
 * State shared by the configurator (created by LSD before any bundle starts) and the runtime (started later by
 * the bundle activator, if at all). Everything here exists before the runtime and works without it.
 */
public final class SdkGlobals {

    /** Hook points of the wrapped DSI services; the runtime polls it once it starts. */
    public static final HookTable HOOKS = new HookTable(256);

    /** In-memory log; the runtime writes it to the log file. The configurator only appends. */
    public static final RingLog LOG = new RingLog(2000);

    /** Set by {@link ChainingConfigurator#initConfigurator()}: whether hook services get wrapped this boot. */
    public static volatile boolean wrappingEnabled;

    /**
     * Set with {@link #wrappingEnabled}: why hook services are not wrapped this boot ({@code unsupported-hmi},
     * {@code safe-mode}, {@code wrap-boots}, {@code boot-state-unwritable}, {@code init-failed}); null while
     * wrapping. Shown on the seam STATUS line.
     */
    public static volatile String wrapOffReason;

    /**
     * Set first thing by {@link ChainingConfigurator#initConfigurator()}. Read by the activator for the seam STATUS
     * line: false there means LSD ran no configurator of ours, or ran it in another class loader (whose
     * SdkGlobals the runtime cannot see).
     */
    public static volatile boolean configuratorSeen;

    /** The runtime publishes UI snapshots here; the Car HMI (stock threads) reads them and drops input in. */
    public static final UiTable UI = new UiTable(64);

    /**
     * Set by {@link ChainingConfigurator#initConfigurator()}: whether the Car activator uses the SDK bank and factory
     * this boot (supported HMI, no wrap-loop latch, the boot counted toward it, {@code ui.enabled}). Safe mode keeps
     * it on.
     */
    public static volatile boolean uiEnabled;

    /**
     * Why the SDK UI is off this boot ({@code unsupported-hmi}, {@code wrap-boots}, {@code ui-disabled},
     * {@code count-failed}, {@code init-failed}); null while it is on.
     */
    public static volatile String uiOffReason;

    /**
     * Which Car row is in which SDK drawer slot. Swapped whole by the {@code mibsdk-ui} thread; stock threads only
     * read the reference.
     */
    public static volatile DrawerAssignment uiSlots = DrawerAssignment.EMPTY;

    /** The SDK drawer rows' visibility models; null until the Car bank created them (first drawer build). */
    public static volatile RowModels uiRowModels;

    private SdkGlobals() {
    }
}
