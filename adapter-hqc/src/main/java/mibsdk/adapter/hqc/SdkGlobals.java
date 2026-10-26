package mibsdk.adapter.hqc;

import mibsdk.adapter.hqc.ui.BuiltinIcons;
import mibsdk.adapter.hqc.ui.DrawerAssignment;
import mibsdk.adapter.hqc.ui.HqcUiIds;
import mibsdk.adapter.hqc.ui.IconIds;
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

    /**
     * The session's image ids for installed icons ({@link HqcUiIds#ICON_ID_FIRST}..{@link HqcUiIds#ICON_ID_LAST}):
     * the one instance, shared by every applier the session starts, so an id never changes its file.
     */
    public static final IconIds ICON_IDS = new IconIds(HqcUiIds.ICON_ID_FIRST, HqcUiIds.ICON_ID_LAST);

    /** The SDK's built-in icons; read by {@code HqcPlatform.start} (an SDK thread), {@link BuiltinIcons#NONE} before. */
    public static volatile BuiltinIcons builtinIcons = BuiltinIcons.NONE;

    /** The SDK drawer rows' visibility models; null until the Car bank created them (first drawer build). */
    public static volatile RowModels uiRowModels;

    /**
     * Set by the {@code mibsdk-ui} thread once it took the SDK UI down (runtime stopped); cleared when a running
     * runtime publishes again. While set, a drawer built from scratch starts with the Plugins row hidden.
     */
    public static volatile boolean uiDown;

    /**
     * The SDK status popup {@link HqcUiIds#POPUP_SDK_STATUS}: 0 unknown (its stub not collected yet), 1 registered
     * (the stub was added), 2 built, -1 failed (the stub or the build threw). -1 is sticky for the boot: nothing moves
     * it back. Written by the Car factory on stock threads; read by {@code mibsdk-ui}, which shows the status line on
     * the SDK popup while it is 1 or 2 and on popup 65 otherwise.
     */
    public static volatile int statusPopup;

    /**
     * The SDK toast popup {@link HqcUiIds#POPUP_SDK_TOAST}, in its own slot: the states of {@link #statusPopup}, and
     * independent of it (the toast may show while the status line fell back to popup 65). -1 is sticky for the boot:
     * the stub or the build threw, or the status popup was hidden while a toast was up. Written by the Car factory and
     * the status popup's controller on stock threads; read by {@code mibsdk-ui}, which drops toasts while it is -1.
     */
    public static volatile int toastPopup;

    /**
     * The {@code HMISystem=} takeover's main-menu Plugins row, {@link #mainMenu}. Transitions, all on stock threads:
     * <ul>
     * <li>{@code OFF} at load; it stays OFF when {@code SdkSystemActivator} gives the stock System factory (swdl, the
     * UI off, the forced failure {@code factory}, the SDK factory failed to build), or when HMISystem= is stock.</li>
     * <li>{@code OFF -> PENDING}: the activator chose the SDK factory; screen 46 has not been built yet.</li>
     * <li>{@code PENDING -> ACTIVE}: screen 46 was built with the Plugins row ({@link #mainMenuRow} and
     * {@link #mainMenuWizard} are set first). A rebuild keeps ACTIVE and moves both refs to the new screen.</li>
     * <li>{@code PENDING or ACTIVE -> FAILED}: adding the row threw (or the forced failure {@code screen}); screen 46
     * is stock and both refs are null (cleared before the state is written). FAILED is sticky for the boot.</li>
     * </ul>
     * The Car drawer hides its own Plugins row only while the state is ACTIVE ({@link #drawerPluginsRowWanted}): a
     * PENDING that never moves on (screen 46 never built through the SDK factory) must not lock the user out of
     * Plugins.
     */
    public static final int MAIN_MENU_OFF = 0;
    public static final int MAIN_MENU_PENDING = 1;
    public static final int MAIN_MENU_ACTIVE = 2;
    public static final int MAIN_MENU_FAILED = 3;

    /** One of the {@code MAIN_MENU_*} states; written only by the System activator and factory. */
    public static volatile int mainMenu = MAIN_MENU_OFF;

    /**
     * The main menu's Plugins row (a {@code MenuItemController}) and its {@code MainWizardController}, of the newest
     * screen 46; null unless {@link #mainMenu} is ACTIVE. Typed Object, so Car-side code that reads them never loads a
     * System factory class.
     */
    public static volatile Object mainMenuRow;
    public static volatile Object mainMenuWizard;

    /**
     * The developer's forced failure for the System takeover, read by the configurator from
     * {@code <persist>/dev/ui.forcefail}: null, {@code "factory"} (the activator's SDK factory throws) or
     * {@code "screen"} (adding the Plugins row to screen 46 throws). Compare with {@code "factory".equals(..)}, never
     * {@code ==}.
     */
    public static volatile String uiForceFail;

    /**
     * Whether the Car drawer should show its Plugins row: unless the main-menu takeover is ACTIVE (OFF, PENDING and
     * FAILED all keep it), so Plugins is always reachable from one of the two.
     */
    public static boolean drawerPluginsRowWanted() {
        return mainMenu != MAIN_MENU_ACTIVE;
    }

    private SdkGlobals() {
    }
}
