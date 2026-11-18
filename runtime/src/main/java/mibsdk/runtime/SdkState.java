package mibsdk.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import mibsdk.runtime.net.WifiView;
import mibsdk.runtime.pkg.PackageCatalog;
import mibsdk.runtime.ui.StatusBarConfig;
import mibsdk.runtime.vehicle.ActiveOverride;
import mibsdk.runtime.vehicle.OverrideLedger;

/**
 * What the SDK pages and the runtime's status-line indicators show about the SDK itself. Immutable. The runtime
 * decides whether to push by comparing what it builds from this (pages, indicators), not the state itself.
 */
public final class SdkState {

    static final String REASON_MARKER = "marker";
    static final String REASON_UNHEALTHY_BOOTS = "unhealthy-boots";
    static final String REASON_BOOT_GUARD_ERROR = "boot-guard-error";

    public final boolean safeMode;
    /** {@link #REASON_MARKER}, {@link #REASON_UNHEALTHY_BOOTS}, {@link #REASON_BOOT_GUARD_ERROR}; null when not safe. */
    public final String safeReason;
    public final boolean guardInert;
    /** The boot guard was reset from the SDK page this run; it takes effect at the next start. */
    public final boolean resetRequested;
    public final String adapterId;
    /** Held and restoring ledger entries ({@link OverrideLedger#active()}), unmodifiable. */
    public final List<ActiveOverride> overrides;
    /** The built-in bar's dwell between rows ({@link StatusBarConfig#dwellMs()}). */
    public final int dwellMs;
    /** The built-in bar's slide duration ({@link StatusBarConfig#animateMs()}). */
    public final int animateMs;
    /** The user's "Status bar" switch: false falls back to the popup ({@link StatusBarConfig#enabled()}). */
    public final boolean statusBarEnabled;
    /** The core Wi-Fi uplink; {@link WifiView#NONE} when the runtime has no manager. */
    public final WifiView wifi;
    /** What mibpkg last wrote about installed and offered packages ({@link PackageCatalog}); EMPTY until read. */
    public final PackageCatalog catalog;
    /** The package thread's last line ("Checking for updates", "Installed hello 1.1.0", "Failed: ..."), or null. */
    public final String packageStatus;

    public SdkState(boolean safeMode, String safeReason, boolean guardInert, boolean resetRequested, String adapterId,
            List<ActiveOverride> overrides) {
        this(safeMode, safeReason, guardInert, resetRequested, adapterId, overrides,
                StatusBarConfig.DEFAULT_DWELL_MS, StatusBarConfig.DEFAULT_ANIMATE_MS, true, WifiView.NONE);
    }

    public SdkState(boolean safeMode, String safeReason, boolean guardInert, boolean resetRequested, String adapterId,
            List<ActiveOverride> overrides, int dwellMs, int animateMs) {
        this(safeMode, safeReason, guardInert, resetRequested, adapterId, overrides, dwellMs, animateMs, true,
                WifiView.NONE);
    }

    public SdkState(boolean safeMode, String safeReason, boolean guardInert, boolean resetRequested, String adapterId,
            List<ActiveOverride> overrides, int dwellMs, int animateMs, boolean statusBarEnabled) {
        this(safeMode, safeReason, guardInert, resetRequested, adapterId, overrides, dwellMs, animateMs,
                statusBarEnabled, WifiView.NONE);
    }

    public SdkState(boolean safeMode, String safeReason, boolean guardInert, boolean resetRequested, String adapterId,
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
        this.catalog = PackageCatalog.EMPTY;
        this.packageStatus = null;
    }

    /** As the previous constructor, plus the package catalog and the last package action's line. */
    public SdkState(boolean safeMode, String safeReason, boolean guardInert, boolean resetRequested, String adapterId,
            List<ActiveOverride> overrides, int dwellMs, int animateMs, boolean statusBarEnabled, WifiView wifi,
            PackageCatalog catalog, String packageStatus) {
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
        this.catalog = catalog == null ? PackageCatalog.EMPTY : catalog;
        this.packageStatus = packageStatus;
    }

    /** Count-based safe mode (not forced by the SAFE_MODE marker): the SDK page may offer a boot-guard reset. */
    public boolean resetOffered() {
        return safeMode && !REASON_MARKER.equals(safeReason);
    }
}
