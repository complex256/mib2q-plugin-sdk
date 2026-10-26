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
     * The SDK page's ListModel (spike B, 5 columns since spike D): label, value, row kind, enabled, value text
     * ({@link #PAGE_COL_LABEL} .. {@link #PAGE_COL_VALUE_TEXT}). The kind is the record-set column: it picks the item
     * factory's row. Id {@code MODEL_BASE + 10} stays unused (spike B's row-label model).
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
    /** The typed rows (dropdown, slider) a page has at most ({@code PageSpec.MAX_INPUT_ROWS}): one pooled model each. */
    public static final int TYPED_ROWS = 4;
    /** The dropdown rows' pooled ChoiceModels, one per typed slot ({@code +0 .. +3}, 602764..602767): the index. */
    public static final int TYPED_CHOICE_MODEL_0 = MODEL_BASE + 26;
    /** The slider rows' pooled RangeModels, one per typed slot ({@code +0 .. +3}, 602768..602771), in index space. */
    public static final int TYPED_RANGE_MODEL_0 = MODEL_BASE + 30;
    /** Reserved with no model (F9: the confirm body is a factory text, {@link #TEXT_CONFIRM_TEXT}). */
    public static final int CONFIRM_TEXT_RESERVED = MODEL_BASE + 34;
    /** The confirm screen's Yes/No ListModel (5 columns, as the page list). */
    public static final int CONFIRM_LIST_MODEL = MODEL_BASE + 35;
    /** The speller screen's SpellerModel. */
    public static final int SPELLER_MODEL = MODEL_BASE + 36;
    /** The toast popup's visibility ChoiceModel, its LabelModel and its file icon's ResourceLocatorModel. */
    public static final int TOAST_VISIBLE_MODEL = MODEL_BASE + 37;
    public static final int TOAST_LABEL_MODEL = MODEL_BASE + 38;
    public static final int TOAST_FILE_ICON_MODEL = MODEL_BASE + 39;
    /**
     * Model ids the SDK bank reserves: {@code MODEL_BASE .. MODEL_BASE + MODEL_COUNT - 1} (602738..602779). {@code +10},
     * {@link #CONFIRM_TEXT_RESERVED} ({@code +34}), {@code +40} and {@code +41} are reserved with no model (F22).
     * Spike C proved 602738..602753 on the bench, spike D 602754..602759. Checked in {@code lsd-src} (native inputs
     * Task 5 Step 0): CarModelBank is built with 2738 models ({@code super(6, 2738, 1147)},
     * {@code CarModelBank.java:4734}), so every id from 602738 is past its {@code models[]}; no stock source uses
     * 602738..602779 as a model id, decoded or byte-swapped. The only hits are byte-swapped text ids
     * ({@code setTextIds}, {@code setInfolineTextIdsDisabled}, {@code setDefaultDisabledInfolineTextId}: another
     * namespace) and condition 602738 in CarConditionBank (condition ids, another namespace). The same check found no
     * Car text id 609040..609082 and no menu-item internal id 609960..609963, decoded or byte-swapped.
     */
    public static final int MODEL_COUNT = 42;
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
    /**
     * The page list's columns (spike D, research note 2026-10-25 §7.2): 0 {@code TextListCell} label, 1
     * {@code IntegerListCell} value (checkbox/radio 1/0, progress percent, icon index), 2 row kind (the list's id and
     * record-set column), 3 enabled ({@code > 0} enabled: the list's enabled column), 4 {@code TextListCell} value text.
     */
    public static final int PAGE_COL_LABEL = 0;
    public static final int PAGE_COL_VALUE = 1;
    public static final int PAGE_COL_KIND = 2;
    public static final int PAGE_COL_ENABLED = 3;
    public static final int PAGE_COL_VALUE_TEXT = 4;
    public static final int PAGE_COLUMNS = 5;
    /**
     * F12, [bench N4]: an item with an icon and a value is drawn with both (the 3-column icon + label + value row).
     * If bench N4 shows that row broken, this is set false and such an item drops its icon (a value row).
     */
    public static final boolean PAGE_ICON_VALUE_ROWS = true;
    /** Label alignment ({@code LabelRendererHigh.setAlignment(h, v)}): left, right, vertically centred. */
    public static final int ALIGN_LEFT = 1;
    public static final int ALIGN_RIGHT = 3;
    public static final int ALIGN_MIDDLE = 5;
    /** The row's cell bounds (checkbox, radio), as the spike set them. */
    public static final int CELL_BOUNDS_W = 100;
    public static final int CELL_BOUNDS_H = 100;
    /** Checkbox cell: model value 0 unchecked, 1 checked. Never written to: widgets keep the reference. */
    public static final int[][] CHECKBOX_MAPPING = {{0}, {1}};
    /** Progress cell: its bar bitmaps, colour indices, range and height. Never written to. */
    public static final int[] PROGRESS_BITMAPS = {40, 41, 42, 43, 44, 45};
    public static final int[] PROGRESS_COLOURS = {1, 2, 4, 0};
    public static final int PROGRESS_MIN = 0;
    public static final int PROGRESS_MAX = 100;
    public static final int PROGRESS_HEIGHT = 15;
    /** Icon cell: its preferred height (the bitmap's own), as the stock list icon cells. */
    public static final int ICON_CELL_HEIGHT = 1;
    /** Item icons: built-ins use their 20-px variant (installed item icons are at most 24 px; no scaling). */
    public static final int ITEM_ICON_PX = 20;
    /**
     * Row column constraints ({@code MenuItemColumnsConstraints} gaps, grow/shrink, min, max; -1 none), by row shape.
     * Label + cell (checkbox, radio, progress, value): the label grows. Never written to.
     */
    public static final int[] PAGE_CELL_GAPS = {0, 22, 0};
    public static final float[] PAGE_CELL_GROW = {1.0f, 0.0f};
    /** Radio: the cell at most 33 px; progress: the bar at least 300 px. */
    public static final int[] PAGE_RADIO_MAX = {-1, 33};
    public static final int[] PAGE_PROGRESS_MIN = {-1, 300};
    /** Icon + label: the icon first, at most 30 px. */
    public static final int[] PAGE_ICON_GAPS = {0, 15, 0};
    public static final float[] PAGE_ICON_GROW = {0.0f, 1.0f};
    public static final int[] PAGE_ICON_MAX = {30, -1};
    /** Icon + label + value [bench N4] (the Language list's 3 columns, {@code SettingsScreenBag1$1}). */
    public static final int[] PAGE_ICON_VALUE_GAPS = {0, 15, 22, 0};
    public static final float[] PAGE_ICON_VALUE_GROW = {0.0f, 1.0f, 0.0f};
    public static final int[] PAGE_ICON_VALUE_MAX = {30, -1, -1};
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

    /**
     * The SDK toast popup: the status popup's recipe ({@link #POPUP_SDK_STATUS}) with its own models
     * ({@link #TOAST_VISIBLE_MODEL}..{@link #TOAST_FILE_ICON_MODEL}), at ({@link #POPUP_TOAST_X}, 0,
     * {@link #POPUP_TOAST_W}, {@link #POPUP_TOAST_H}): it ends where the status popup starts. It is in partial-popup
     * <b>slot 6</b>, never the status popup's slot 7: one popup per slot is shown at a time (spike C S1), so two popups
     * in one slot would displace each other. Slot 6 is free (plan F1, from {@code lsd-src/lsd}):
     * <ul>
     * <li>every stock {@code new PartialPopupStub(...)} (19 factories) uses slots 0, 1, 2, 3, 4, 5, 9, 10, 11, 12 and
     * 13; none uses 6 or 8;</li>
     * <li>EarlyApps (parking/RVC, {@code EarlyAppsScreenFactory.java:3118}) uses 1, 2, 4 and 9; System (volume, status
     * bar, popup 65 and the rest, {@code SystemScreenFactory.java:5662}) uses 0, 3, 5, 10, 12 and 13; the Phone/eCall
     * stubs ({@code PhoneScreenFactory.java:9102}) use 0;</li>
     * <li>{@code IPartialPopupManagerEvo.PARTIAL_POPUP_SLOT_DEPTHS} gives slot 6 depth 0, as slot 7 (slot 8 has 20), so
     * the toast stacks exactly like the proven status popup, under every EarlyApps slot (depths 10, 11, 19, 21);</li>
     * <li>{@code AbstractPartialPopupManager.SLOTS} lists 6 beside 7 (both count as "a partial popup is visible").</li>
     * </ul>
     * Stub arguments as the status popup's, apart from the id, the model and the slot.
     */
    public static final int POPUP_SDK_TOAST = 609601;
    public static final int POPUP_TOAST_SLOT = 6;
    public static final int POPUP_TOAST_ZPM_SLOT = 12;
    public static final int POPUP_TOAST_PRIORITY = 250;
    public static final int POPUP_TOAST_TYPE = 7;
    /** Left of the status popup, ending where it starts: 514 - 255 = 259. */
    public static final int POPUP_TOAST_X = POPUP_SDK_X - POPUP_SDK_W;
    public static final int POPUP_TOAST_Y = 0;
    public static final int POPUP_TOAST_W = 255;
    public static final int POPUP_TOAST_H = 24;
    /**
     * How long a toast shows, in ms: {@code Ui.TOAST_MS}, as a literal for code that may not read {@code mibsdk.api}
     * (stock-thread files). A test keeps the two equal.
     */
    public static final int TOAST_MS = 3000;

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

    // ---- typed rows and the slider screen (spike D, research note 2026-10-25 §7.2) ----------------------------------

    /** The typed rows' menu-item internal ids, one per typed slot ({@code +0 .. +3}): clear of the rows' 609900..609908. */
    public static final int TYPED_INTERNAL_ID_0 = 609960;
    /**
     * The input texts ({@code 609040..609082}): typed slot {@code s} has its label at {@code TEXT_INPUT_BASE + 9s} (also
     * the slider screen's title, F10) and its options at {@code +1 .. +8}.
     */
    public static final int TEXT_INPUT_BASE = TEXT_BASE + 40;
    public static final int TEXT_TYPED_STRIDE = 9;
    /** The slider screen's min and max labels. */
    public static final int TEXT_SLIDER_MIN = 609076;
    public static final int TEXT_SLIDER_MAX = 609077;
    /** The confirm and speller screens' texts (Task 6). */
    public static final int TEXT_CONFIRM_TITLE = 609078;
    public static final int TEXT_SPELLER_TITLE = 609079;
    public static final int TEXT_CONFIRM_TEXT = 609080;
    public static final int TEXT_SPELLER_INFO = 609081;
    public static final int TEXT_SPELLER_HINT = 609082;

    /** Combo (dropdown) row: menu-item type 64 ({@code tONESOUNDEFFECTMAIN}, {@code CarScreenBag3}). */
    public static final int ROW_TYPE_COMBO = 64;
    /** The combo row's bitmaps: 42 x 127, then 129 and 332. Never written to: widgets keep the reference. */
    public static final int[] COMBO_BITMAPS = comboBitmaps();
    /** The combo row's Car font set ({@code FontFactory.getFonts(5, ...)}). */
    public static final int COMBO_FONTS = 5;
    /** The combo row's widgetID. */
    public static final int COMBO_WIDGET_ID = 1;
    /** Glass-plate insets of the typed rows (as the spike's combo and rotary rows). */
    public static final int TYPED_GLASSPLATE_INSET_TOP = 7;
    public static final int TYPED_GLASSPLATE_INSET_BOTTOM = 6;
    /** Rotary (slider) row: menu-item type 128, a knob preview on the RangeModel. */
    public static final int ROW_TYPE_ROTARY = 128;

    /** The slider edit screen (Car module, unused by the Car factory): the {@code tONESOUNDBASS} recipe. */
    public static final int SLIDER_SCREEN_ID = 609501;
    /** The last SDK sub-screen id: 609501 slider, 609502 confirm, 609503 speller (Task 6). */
    public static final int SUB_SCREEN_LAST = 609503;
    /** The screen's glassplate and the glassplate's coordinate set. */
    public static final int[] SLIDER_GLASS_BOUNDS = {59, 77, 946, 324};
    public static final int[] SLIDER_GLASS_COORDS = {1, 59, 77, 946, 324, 59, 77, 946, 324};
    /** The big rotary ({@code RotaryBigRendererHigh}). */
    public static final int[] SLIDER_ROTARY_BOUNDS = {397, 122, 100, 100};
    /** The min and max labels: bounds, Car font set 2, colours; the min label right-aligned at the top (3, 4). */
    public static final int[] SLIDER_MIN_BOUNDS = {226, 323, 202, 30};
    public static final int[] SLIDER_MAX_BOUNDS = {621, 323, 202, 30};
    public static final int SLIDER_LABEL_FONTS = 2;
    public static final int[] SLIDER_LABEL_COLOURS = {1, 0, 4, 0};
    public static final int SLIDER_MIN_ALIGN_H = 3;
    public static final int SLIDER_MIN_ALIGN_V = 4;
    /** The mirrored container around the rotary and the labels. */
    public static final int[] SLIDER_MIRROR_BOUNDS = {0, 0, 1024, 480};
    /** The screen's small-stage type (as the page). */
    public static final int SLIDER_SMALL_STAGE = 3;
    /** The rotary's widget state "focused and active" ({@code RangeModelKeyHandler} needs it; spike D: set at connect). */
    public static final int SLIDER_KNOB_STATE = 36;

    // ---- the confirm and speller screens (spike D, D7/D9; research note 2026-10-25 §7.2) ----------------------------

    /** The confirm screen (Car module, unused by the Car factory): the {@code cARPOPUPDRIVESELECTLIFTNA} recipe. */
    public static final int CONFIRM_SCREEN_ID = 609502;
    /** The speller screen (Car module, unused by the Car factory): the {@code cMOPTBLUETOOTHNAMEEDIT} recipe. */
    public static final int SPELLER_SCREEN_ID = 609503;
    /**
     * The confirm hint's {@code MultiLineLabelRendererHigh.setAutoWrap}: 0 wraps at the label's width (stock calls it
     * so); the default -1 only breaks at {@code '\n'} (spike D: one line ending in "...").
     */
    public static final int CONFIRM_AUTOWRAP = 0;
    /** The confirm screen's small-stage type ({@code setSmallStageType(1)}). */
    public static final int CONFIRM_SMALL_STAGE = 1;
    /** The dialogs' {@code InstructionTextContoller} colour indices. Never written to. */
    public static final int[] DIALOG_TEXT_COLOURS = {1, 0, 4, 0};
    /** The dialogs' hint label {@code setTextOrientationHints}. Never written to. */
    public static final int[] DIALOG_HINT_ORIENTATION = {6};
    /** The dialogs' menu content right offset (the spike's dressed menu; the page's is 32). */
    public static final int DIALOG_MENU_RIGHT_OFFSET = 25;
    /** {@code SpellerListener.commandPressed}: the band opened ({@code COMMAND_SPELLER_OPENED}) and closed. */
    public static final int SPELLER_OPENED = 4711;
    public static final int SPELLER_CLOSED = 4712;
    /** The speller screen's small-stage type (as the spike). */
    public static final int SPELLER_SMALL_STAGE = 3;
    /** The input field ({@code TouchController}): bitmaps, Connectivity font set 4, colours. Never written to. */
    public static final int[] SPELLER_FIELD_BITMAPS = {483, 484, 857};
    public static final int SPELLER_FIELD_FONTS = 4;
    public static final int[] SPELLER_FIELD_COLOURS = {2, 5, 4, 3};
    /** The field's cursor offsets, glassplate insets, height, screen level and widgetID. */
    public static final int SPELLER_CURSOR_OFFSET_BOTTOM = 1;
    public static final int SPELLER_CURSOR_OFFSET_TOP = -1;
    public static final int SPELLER_INSET_BOTTOM = 17;
    public static final int SPELLER_INSET_TOP = 5;
    public static final int SPELLER_FIELD_HEIGHT = 60;
    public static final int SPELLER_SCREEN_LEVEL = 2;
    public static final int SPELLER_WIDGET_ID = 1;
    /** The band ({@code SpellerController}): the 49 bitmaps of {@code cMOPTBLUETOOTHNAMEEDIT}. Never written to. */
    public static final int[] SPELLER_BITMAPS = {421, 84, 85, 86, 87, 88, 89, 424, 425, 90, 91, 92, 93, 94, 95, 96,
        97, 98, 99, 187, 188, 189, 190, 473, 473, 475, 476, 475, 475, 479, 480, 479, 479, 486, 487, 488, 489, 490, 491,
        492, 493, 494, 495, 496, 497, 858, 859, 961, 962};
    /** The band's Connectivity font set and colours. Never written to. */
    public static final int SPELLER_FONTS = 5;
    public static final int[] SPELLER_COLOURS = {2, 0, 4, 0};
    /** The field's model stubs (Connectivity models) and the layers they are added at. */
    public static final int SPELLER_STUB_A = 5602;
    public static final int SPELLER_STUB_A_LAYER = 16;
    public static final int SPELLER_STUB_B = 5606;
    public static final int SPELLER_STUB_B_LAYER = 8;

    private static int[] comboBitmaps() {
        int[] b = new int[44];
        for (int i = 0; i < 42; i++) {
            b[i] = 127;
        }
        b[42] = 129;
        b[43] = 332;
        return b;
    }

    private HqcUiIds() {
    }
}
