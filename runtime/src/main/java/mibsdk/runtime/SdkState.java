package mibsdk.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What the SDK pages and the runtime's status-line indicators show about the SDK itself. Immutable; equal
 * snapshots (overrides compared by their {@link ActiveOverride#toString()}, which names every field) mean
 * nothing changed.
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

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof SdkState)) {
            return false;
        }
        SdkState s = (SdkState) o;
        if (safeMode != s.safeMode || guardInert != s.guardInert || resetRequested != s.resetRequested
                || !same(safeReason, s.safeReason) || !same(adapterId, s.adapterId)
                || overrides.size() != s.overrides.size()) {
            return false;
        }
        for (int i = 0; i < overrides.size(); i++) {
            if (!overrides.get(i).toString().equals(s.overrides.get(i).toString())) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int h = (safeMode ? 1 : 0) + (guardInert ? 2 : 0) + (resetRequested ? 4 : 0);
        h = 31 * h + (safeReason == null ? 0 : safeReason.hashCode());
        h = 31 * h + (adapterId == null ? 0 : adapterId.hashCode());
        for (int i = 0; i < overrides.size(); i++) {
            h = 31 * h + overrides.get(i).toString().hashCode();
        }
        return h;
    }

    private static boolean same(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
