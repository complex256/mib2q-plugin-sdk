package mibsdk.adapter.hqc;

import mibsdk.platform.HookTable;
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
     * Set first thing by {@link ChainingConfigurator#initConfigurator()}. Read by the activator for the seam STATUS
     * line: false there means LSD ran no configurator of ours, or ran it in another class loader (whose
     * SdkGlobals the runtime cannot see).
     */
    public static volatile boolean configuratorSeen;

    private SdkGlobals() {
    }
}
