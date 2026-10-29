package mibsdk.api;

/**
 * Features the running firmware adapter may support. Read them with {@link PluginContext#has(String)}, at call time:
 * the adapter can learn a feature after this plugin was enabled, and a feature it lacks this boot may be there after a
 * restart. Never assume one; a missing feature makes the matching {@link Ui} call inert, not failing.
 */
public final class Features {

    /** The unit has the parking-sensor system, so {@code vehicle().parking()} works; without it that call throws {@link UnsupportedOperationException}. */
    public static final String PARKING = "vehicle.parking";
    /** The adapter can report ignition (terminal 15). */
    public static final String IGNITION = "vehicle.ignition";
    /** The adapter exposes the parking hook points, so {@link Hooks#veto} and {@link Hooks#observe} work; without it they throw {@link UnsupportedOperationException}. */
    public static final String HOOK_PARKING = "hook.parking";
    /** The unit has a Car menu, so {@link Ui#carMenuRow} draws its row; without it the call returns an inert row. */
    public static final String SLOT_CAR_MENU = "slot.carmenu";
    /** The unit has a status line, so {@link Ui#statusItem} shows and {@link Ui#toast} can pop up; without it both are inert. */
    public static final String SLOT_STATUS_BAR = "slot.statusbar";
    /** The unit can show plugin pages, so {@link Ui#page} is live; without it the page is inert. */
    public static final String UI_PAGES = "ui.pages";
    /**
     * The unit can draw icons on Car rows and status items. Without it icons are still checked exactly as with it, but
     * rows and items show without them.
     */
    public static final String UI_ICONS = "ui.icons";
    /**
     * The unit draws api 5 input entries (choice, dropdown, slider, progress) and shows {@link Ui#confirm},
     * {@link Ui#toast} and {@link Ui#speller}. Without it those entries show as text lines and confirm/toast/speller
     * are inert; fixed for a boot. Item icons follow {@link #UI_ICONS}, and disabled rows need neither. Needs api 5.
     *
     * @since api 5
     */
    public static final String UI_INPUTS = "ui.inputs";

    /**
     * The unit has the stock Wi-Fi service, so {@link PluginContext#wifi()} works and {@link Wifi} calls are live.
     * Without it those calls throw {@link UnsupportedOperationException}. Needs api 7.
     *
     * @since api 7
     */
    public static final String WIFI = "network.wifi";

    private Features() {
    }
}
