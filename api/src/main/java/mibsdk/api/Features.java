package mibsdk.api;

public final class Features {

    public static final String PARKING = "vehicle.parking";
    public static final String IGNITION = "vehicle.ignition";
    public static final String HOOK_PARKING = "hook.parking";
    public static final String SLOT_CAR_MENU = "slot.carmenu";
    public static final String SLOT_STATUS_BAR = "slot.statusbar";
    public static final String UI_PAGES = "ui.pages";
    /**
     * The unit can draw icons on Car rows and status items. Without it icons are still checked exactly as with it, but
     * rows and items show without them.
     */
    public static final String UI_ICONS = "ui.icons";

    private Features() {
    }
}
