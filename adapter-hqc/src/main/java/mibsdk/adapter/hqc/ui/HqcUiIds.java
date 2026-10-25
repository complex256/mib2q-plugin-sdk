package mibsdk.adapter.hqc.ui;

/**
 * Every stock HMI id the SDK UI uses, as a literal: real values from the {@code lsd-src} decompile, confirmed on the
 * bench by spike A (docs/research/2026-10-20-hmi-ui-injection.md §8). Nothing in this package may reference a stock
 * constant instead (javac would inline jxe2jar's byte-swapped value): NoStockConstantsTest enforces it.
 */
public final class HqcUiIds {

    /** Car HMI module id (the {@code HMICar=} activator's first constructor argument). */
    public static final int CAR_MODULE = 6;
    /** First free Car model id: CarModelBank has models.length=2738, so 602738 onwards are the SDK's. */
    public static final int MODEL_BASE = 602738;
    /** First SDK text id; served by the factory's {@code getText}, never by the stock string resources. */
    public static final int TEXT_BASE = 609000;
    /** Car selection drawer {@code cARSEL} (a SelectionMenuController). */
    public static final int DRAWER_ID = 600043;
    /** Menu-item type of the stock cARSEL rows. */
    public static final int DRAWER_ROW_TYPE_ACTION = 2;
    /** The stock cARSEL rows' shared selection ChoiceModel (logged only; the SDK rows use their own). */
    public static final int DRAWER_SELECTION_MODEL = 601227;
    /** SDK plugin rows in the Car drawer, after the Plugins row (slot 0). */
    public static final int PLUGIN_ROW_SLOTS = 8;
    /** Drawer slots the SDK owns: the Plugins row plus {@link #PLUGIN_ROW_SLOTS}. */
    public static final int ROW_SLOTS = 1 + PLUGIN_ROW_SLOTS;
    /** Row visibility ChoiceModel of slot 0; slots 0..8 use 602738..602746 (value 0 visible, 1 hidden). */
    public static final int ROW_VISIBILITY_MODEL_0 = MODEL_BASE;
    /** Selection ChoiceModel shared by every SDK row; a row's {@code widgetID} is its slot. */
    public static final int ROW_SELECTION_MODEL = MODEL_BASE + 9;
    /** The drawer rows' models: the 9 visibility models and the selection model ({@code MODEL_BASE + 0..9}). */
    public static final int ROW_MODEL_COUNT = ROW_SLOTS + 1;
    /**
     * The SDK page's ListModel (spike B): 3 columns (text, value, row kind; the kind is the id column). Id
     * {@code MODEL_BASE + 10} stays unused (spike B's row-label model).
     */
    public static final int PAGE_LIST_MODEL = MODEL_BASE + 11;
    /**
     * SDK status popup {@link #POPUP_SDK_STATUS}: its visibility ChoiceModel (0 hidden, 1 shown), also the stub's
     * model.
     */
    public static final int STATUS_POPUP_VISIBLE_MODEL = MODEL_BASE + 12;
    /** The popup's LabelModels: {@code +0} the indicators, {@code +1 .. +STATUS_CELLS} the cells. */
    public static final int STATUS_LABEL_MODEL_0 = MODEL_BASE + 13;
    /** The popup's ResourceLocatorModels, one per cell ({@code +0 .. +STATUS_CELLS - 1}): a file icon's path. */
    public static final int STATUS_FILE_ICON_MODEL_1 = MODEL_BASE + 20;
    /**
     * Model ids the SDK bank reserves: {@code MODEL_BASE .. MODEL_BASE + MODEL_COUNT - 1} (602738..602763).
     * Spike C proved 602738..602753 on the bench. Checked in {@code lsd-src} (Task 5 Step 0): CarModelBank is built
     * with 2738 models ({@code super(6, 2738, 1147)}), so every id from 602738 is past its {@code models[]}; no stock
     * source uses 602738..602763 as a model id, decoded or byte-swapped. The only hits are a few byte-swapped
     * {@code setTextIds} values (text ids, another namespace) and condition 602738 in CarConditionBank (condition
     * ids, another namespace).
     */
    public static final int MODEL_COUNT = STATUS_FILE_ICON_MODEL_1 + HqcUiIds.STATUS_CELLS - MODEL_BASE;
    /** SDK rows' menu-item internal ids, one per slot (as spike A wired them). */
    public static final int ROW_INTERNAL_ID_BASE = 609900;
    /**
     * Where the SDK rows' bitmaps come from: -1 none; n &gt;= 0 copies drawer child n's bitmaps (1 = the stock
     * 'Audi drive select' row, which is what spike A copied).
     */
    public static final int ROW_BITMAP_SOURCE = -1;
    /** Glass-plate insets of the stock cARSEL rows, as spike A set them on the SDK rows. */
    public static final int ROW_GLASSPLATE_INSET_TOP = 7;
    public static final int ROW_GLASSPLATE_INSET_BOTTOM = 6;
    /** How SDK pages reach the screen (spike B, S2a go): FwHMI.showScreen with a prebuilt Screen. */
    public static final int PAGE_PATH_SHOW_SCREEN = 0;
    /** The alternative (S2b go, not used): a hijacked Car state's screen ({@code createScreen}). */
    public static final int PAGE_PATH_HIJACK = 1;
    public static final int PAGE_PATH = PAGE_PATH_SHOW_SCREEN;
    /** Screen id of every SDK page (Car module, unused by the Car factory). */
    public static final int PAGE_SCREEN_ID = 609500;
    /** The Car main screen (the drawer's): where SDK pages return when no stock screen was saved. */
    public static final int CAR_MAIN_SCREEN = 600044;
    /** Factory text id of the page title (outside the drawer rows' {@code TEXT_BASE + 0..8}). */
    public static final int TEXT_PAGE_TITLE = TEXT_BASE + 20;
    /** Hard key BACK (KeyEvent code; the page's MenuController consumes its press and release). */
    public static final int KEY_BACK = 15;
    /** Menu-item type of a list row (EngineeringScreenBag2's list items). */
    public static final int LIST_ITEM_TYPE = 16;
    /** The status-bar stub's model on every stock screen. */
    public static final int STATUSBAR_STUB_MODEL = 138;
    /** Title bar bitmaps of the page (as spike B drew them). */
    public static final int PAGE_TITLE_ICON_BITMAP = 127;
    public static final int PAGE_TITLE_BAR_BITMAP = 58;
    /** EarlyApps HMI module: the parking/RVC popups (screens 2100000..) live here. */
    public static final int EARLY_APPS_MODULE = 21;
    /** Status-line popup: label model 416, visible while system ChoiceModel 1 is 1. */
    public static final int POPUP_STATUS = 65;
    public static final int STATUS_LABEL_MODEL = 416;
    public static final int STATUS_VISIBLE_CHOICE = 1;
    /** 25 characters measured on the bench; one of margin for the proportional font. */
    public static final int STATUS_MAX_CHARS = 24;

    /**
     * The SDK status popup (spike C, Boot A S1/S2, Boot B slot): a copy of popup 65 at (514, 0, 255, 24). In slot 7
     * it shows beside popup 65 (which is in slot 5) instead of replacing it. Stub arguments as popup 65's
     * ({@code SystemScreenFactory.getPartialPopupStubs}), apart from the id, the model and the slot.
     */
    public static final int POPUP_SDK_STATUS = 609600;
    public static final int POPUP_SDK_X = 514;
    public static final int POPUP_SDK_W = 255;
    public static final int POPUP_SDK_H = 24;
    public static final int POPUP_SDK_SLOT = 7;
    public static final int POPUP_SDK_ZPM_SLOT = 12;
    public static final int POPUP_SDK_PRIORITY = 250;
    public static final int POPUP_SDK_TYPE = 7;
    /** The stub's third argument (no second model) and tenth (its style), as popup 65's stub has them. */
    public static final int POPUP_SDK_STUB_NO_MODEL = -1;
    public static final int POPUP_SDK_STUB_KIND = 2;
    /** The popup backdrop: a 1x1 bitmap scaled to the popup (SystemScreenBag3.partialPopupDebugInfo). */
    public static final int POPUP_BACKDROP_BITMAP = 251;

    /** Status popup layout: the item cells after the indicators (one per plugin item, at most). */
    public static final int STATUS_CELLS = 6;
    /** Left (and right) padding inside the popup. */
    public static final int STATUS_PAD_PX = 6;
    /**
     * One character of the status font, estimated: popup 65 fits 24 characters in 235 px. Checked at bench R1 S1.
     */
    public static final int STATUS_CHAR_PX = 10;
    /** What an icon takes of its item's width, in characters: the runtime's {@code StatusLine.ICON_CHARS}. */
    public static final int STATUS_ICON_CHARS = 2;
    /**
     * The status icon glyph, 20 px square: the built-in {@code <name>-20} lookup and the icon bounds. A literal, never
     * derived from {@link #STATUS_CHAR_PX}: retuning that must not ask for a glyph size that does not exist. The
     * built-in {@code -20} glyphs carry their own 2-px right margin as the gap before the text.
     */
    public static final int STATUS_ICON_PX = 20;
    /**
     * How far an icon moves its item's text: {@link #STATUS_ICON_CHARS} characters, as the runtime cut the text for
     * it. Layout only; follows {@link #STATUS_CHAR_PX}.
     */
    public static final int STATUS_ICON_ADVANCE_PX = STATUS_ICON_CHARS * STATUS_CHAR_PX;
    /**
     * A status icon drawn in its own colours: colour indices mapping popup vis state 1 to plate entry 0, which is
     * white ({@code -1}) in every stock palette row, so the modulate leaves the bitmap's colours as they are (the
     * popup labels use the same indices). Tinted icons have none (null). Never written to: widgets keep the reference.
     */
    public static final int[] STATUS_ICON_OWN_COLOURS = {1, 0, 4, 0};

    /**
     * SDK image ids (Car module, {@code id / 100000 == CAR_MODULE}), served by {@code SdkCarScreenFactory.getImagePath}
     * from a precomputed path: one per installed icon {@code <owner>/<sha16>} per HMI session (spike C, Boot A I1).
     */
    public static final int ICON_ID_FIRST = 609100;
    public static final int ICON_ID_LAST = 609399;
    /** The SDK install root on the unit: image paths are computed from it (its sibling {@code images} is ImageRoot). */
    public static final String INSTALL_ROOT = "/mnt/app/eso/hmi/lsd/mibsdk";

    /**
     * The {@code HMISystem=} takeover (spike C, Boot B C1/C2 and the wrench): the System HMI module, its main-menu
     * wizard screen (the MENU ring, {@code SystemScreenBag2.mAINWIZARD}) and the SDK's Plugins row in it.
     */
    public static final int SYSTEM_MODULE = 0;
    public static final int SYS_SCREEN_MAINWIZARD = 46;
    /** The Plugins row's text id (System module), served by the SDK System factory's {@code getText}. */
    public static final int SYS_TEXT_PLUGINS = 90000;
    /** The row's widgetID: a free one; its atlas entry (stock {11, 11}, night vision) is re-pointed to the wrench. */
    public static final int MAIN_MENU_WIDGET_ID = 11;
    /** The wrench: {@code mapping[MAIN_MENU_WIDGET_ID] := {WRENCH_ATLAS_CELL, WRENCH_DECORATOR}} (cell 16 is unused). */
    public static final int WRENCH_ATLAS_CELL = 16;
    public static final int WRENCH_DECORATOR = 9;
    /** The row's menu-item internal id: none of screen 46's stock rows uses it. */
    public static final int MAIN_MENU_INTERNAL_ID = 90100;
    /** Hard key ENTER / knob SELECT (KeyEvent code), as the spike's wizard key handler consumed it. */
    public static final int KEY_ENTER = 17;

    private HqcUiIds() {
    }
}
