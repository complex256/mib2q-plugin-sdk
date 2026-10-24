package mibsdk.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What the SDK pages and the runtime's status-line indicators show about the SDK itself. Immutable. The runtime
 * decides whether to push by comparing what it builds from this (pages, indicators), not the state itself.
 */
final class SdkState {

    static final String REASON_MARKER = "marker";
    static final String REASON_UNHEALTHY_BOOTS = "unhealthy-boots";
    static final String REASON_BOOT_GUARD_ERROR = "boot-guard-error";

    final boolean safeMode;
    /** {@link #REASON_MARKER}, {@link #REASON_UNHEALTHY_BOOTS}, {@link #REASON_BOOT_GUARD_ERROR}; null when not safe. */
    final String safeReason;
    final boolean guardInert;
    /** The boot guard was reset from the SDK page this run; it takes effect at the next start. */
    final boolean resetRequested;
    final String adapterId;
    /** Held and restoring ledger entries ({@link OverrideLedger#active()}), unmodifiable. */
    final List<ActiveOverride> overrides;

    SdkState(boolean safeMode, String safeReason, boolean guardInert, boolean resetRequested, String adapterId,
            List<ActiveOverride> overrides) {
        this.safeMode = safeMode;
        this.safeReason = safeReason;
        this.guardInert = guardInert;
        this.resetRequested = resetRequested;
        this.adapterId = adapterId;
        this.overrides = overrides == null ? Collections.<ActiveOverride>emptyList()
                : Collections.unmodifiableList(new ArrayList<ActiveOverride>(overrides));
    }

    /** Count-based safe mode (not forced by the SAFE_MODE marker): the SDK page may offer a boot-guard reset. */
    boolean resetOffered() {
        return safeMode && !REASON_MARKER.equals(safeReason);
    }
}
