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
    /** The built-in bar's dwell between rows ({@link StatusBarConfig#dwellMs()}). */
    final int dwellMs;
    /** The built-in bar's slide duration ({@link StatusBarConfig#animateMs()}). */
    final int animateMs;
    /** The user's "Status bar" switch: false falls back to the popup ({@link StatusBarConfig#enabled()}). */
    final boolean statusBarEnabled;
    /** The core Wi-Fi uplink; {@link WifiView#NONE} when the runtime has no manager. */
    final WifiView wifi;

    SdkState(boolean safeMode, String safeReason, boolean guardInert, boolean resetRequested, String adapterId,
            List<ActiveOverride> overrides) {
        this(safeMode, safeReason, guardInert, resetRequested, adapterId, overrides,
                StatusBarConfig.DEFAULT_DWELL_MS, StatusBarConfig.DEFAULT_ANIMATE_MS, true, WifiView.NONE);
    }

    SdkState(boolean safeMode, String safeReason, boolean guardInert, boolean resetRequested, String adapterId,
            List<ActiveOverride> overrides, int dwellMs, int animateMs) {
        this(safeMode, safeReason, guardInert, resetRequested, adapterId, overrides, dwellMs, animateMs, true,
                WifiView.NONE);
    }

    SdkState(boolean safeMode, String safeReason, boolean guardInert, boolean resetRequested, String adapterId,
            List<ActiveOverride> overrides, int dwellMs, int animateMs, boolean statusBarEnabled) {
        this(safeMode, safeReason, guardInert, resetRequested, adapterId, overrides, dwellMs, animateMs,
                statusBarEnabled, WifiView.NONE);
    }

    SdkState(boolean safeMode, String safeReason, boolean guardInert, boolean resetRequested, String adapterId,
            List<ActiveOverride> overrides, int dwellMs, int animateMs, boolean statusBarEnabled, WifiView wifi) {
        this.safeMode = safeMode;
        this.safeReason = safeReason;
        this.guardInert = guardInert;
        this.resetRequested = resetRequested;
        this.adapterId = adapterId;
        this.overrides = overrides == null ? Collections.<ActiveOverride>emptyList()
                : Collections.unmodifiableList(new ArrayList<ActiveOverride>(overrides));
        this.dwellMs = dwellMs;
        this.animateMs = animateMs;
        this.statusBarEnabled = statusBarEnabled;
        this.wifi = wifi == null ? WifiView.NONE : wifi;
    }

    /** Count-based safe mode (not forced by the SAFE_MODE marker): the SDK page may offer a boot-guard reset. */
    boolean resetOffered() {
        return safeMode && !REASON_MARKER.equals(safeReason);
    }
}
