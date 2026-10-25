package mibsdk.spike.ui;

/**
 * Stock ids the spike tests, as literals (real values; the decompiles hold them byte-swapped). Nothing here
 * references a stock constant: javac would inline jxe2jar's byte-swapped value.
 */
final class SpikeIds {
    static final int CAR_MODULE = 6;
    /** Model index base: CarModelBank's models[] has 2738 slots (0..2737), so the first free id is 602738. */
    static final int MODEL_BASE = 602738;
    static final int ROWS = 9;
    /** S1f: drawer row without bitmaps (the re-press row). */
    static final int ROW_NO_ICON = 2;
    /** MODEL_BASE + slot: row visibility ChoiceModel (0 visible, 1 hidden). */
    static final int SELECTION_MODEL = MODEL_BASE + ROWS;      // shared, widgetID = slot
    static final int LABEL_MODEL = MODEL_BASE + ROWS + 1;      // %1 replacement in row 0's text
    /** Spike B: the SDK page's ListModel (3 columns: text, value, row kind) and its LABEL row's LabelModel. */
    static final int PAGE_LIST_MODEL = MODEL_BASE + ROWS + 2;  // 602749
    static final int PAGE_LABEL_MODEL = MODEL_BASE + ROWS + 3; // 602750
    /** Spike C (S1/S2): popup 609600's visibility ChoiceModel, file-path ResourceLocatorModel and LabelModel. */
    static final int POPUP_VISIBLE_MODEL = MODEL_BASE + ROWS + 4;   // 602751
    static final int POPUP_FILE_MODEL = MODEL_BASE + ROWS + 5;      // 602752
    static final int POPUP_LABEL_MODEL = MODEL_BASE + ROWS + 6;     // 602753
    // ---- Spike D (native inputs) ------------------------------------------------------------------------------
    /** Type-8 checkbox row's ChoiceModel (0/1, toggled by its listener). */
    static final int ROW8_MODEL = MODEL_BASE + ROWS + 7;            // 602754
    /** Type-64 combo row's ChoiceModel (value = option index). */
    static final int COMBO_MODEL = MODEL_BASE + ROWS + 8;           // 602755
    /** Type-128 row's and the slider screen's RangeModel (-6..6 step 1, medial 0). */
    static final int RANGE_MODEL = MODEL_BASE + ROWS + 9;           // 602756
    /** Confirm screen's LabelModel (spare: the hint text is a factory text id). */
    static final int CONFIRM_LABEL_MODEL = MODEL_BASE + ROWS + 10;  // 602757
    /** Confirm screen's ListModel (rows "Yes", "No"). */
    static final int CONFIRM_LIST_MODEL = MODEL_BASE + ROWS + 11;   // 602758
    /** Speller screen's SpellerModel (stretch). */
    static final int SPELLER_MODEL = MODEL_BASE + ROWS + 12;        // 602759
    static final int MODEL_COUNT = ROWS + 13;
    static final int TEXT_BASE = 609000;
    /** Factory texts served for TEXT_BASE + 0..TEXTS-1 (rows 0..8, then page texts, then spike D texts). */
    static final int TEXTS = 48;
    static final int TEXT_PAGE_TITLE = TEXT_BASE + 20;
    static final int TEXT_PAGE_INFO = TEXT_BASE + 21;
    static final int INTERNAL_ID_BASE = 609900;
    /** Spike D texts. */
    static final int TEXT_COMBO_OPT0 = TEXT_BASE + 22;   // ..25: Off/Low/Mid/High
    static final int TEXT_ROW8 = TEXT_BASE + 26;
    static final int TEXT_CONFIRM_TITLE = TEXT_BASE + 27;
    static final int TEXT_BASS = TEXT_BASE + 28;
    static final int TEXT_SLIDER_TITLE = TEXT_BASE + 29;
    static final int TEXT_CONFIRM_TEXT = TEXT_BASE + 30;
    static final int TEXT_COMBO_LABEL = TEXT_BASE + 31;
    static final int TEXT_SLIDER_MIN = TEXT_BASE + 32;
    static final int TEXT_SLIDER_MAX = TEXT_BASE + 33;
    static final int TEXT_SPELLER_TITLE = TEXT_BASE + 34;
    static final int TEXT_SPELLER_INFO = TEXT_BASE + 35;
    static final int TEXT_SPELLER_HINT = TEXT_BASE + 36;
    /** Spike D screens, shown with FwHMI.showScreen like the page. */
    static final int SLIDER_SCREEN_ID = 609501;
    static final int CONFIRM_SCREEN_ID = 609502;
    static final int SPELLER_SCREEN_ID = 609503;
    static final int ROW_TYPE_COMBO = 64;
    static final int ROW_TYPE_ROTARY = 128;

    static final int DRAWER_ROW_TYPE_ACTION = 2;
    static final int DRAWER_SELECTION_MODEL = 601227;          // stock cARSEL rows' shared ChoiceModel

    /** Spike B (S2a): screen id of the page shown with FwHMI.showScreen (Car module, unused by the Car factory). */
    static final int PAGE_SCREEN_ID = 609500;
    /** Spike B (S2b): default Car SMM event to hijack: drawer row 'Auxiliary heating' (hidden on this unit). */
    static final int HIJACK_EVENT = 600023;
    /** cARSEL drawer's setHKReturnEvent value. */
    static final int DRAWER_HK_RETURN_EVENT = 600173;
    static final int STATUSBAR_STUB_MODEL = 138;
    static final int KEY_RETURN = 15;
    static final int KEY_ENTER = 17;
    static final int ROW_TYPE_LABEL = 1;
    static final int ROW_TYPE_CHECKBOX = 8;
    static final int ROW_TYPE_LIST_ITEM = 16;

    static final int POPUP_STATUS = 65;
    static final int STATUS_LABEL_MODEL = 416;
    static final int STATUS_VISIBLE_CHOICE = 1;

    // ---- Spike C ------------------------------------------------------------------------------------------------
    /** Car-module image ids served from the spike icons dir by SpikeFactory.getImagePath. */
    static final int ICON_FIRST = 609100;
    static final int ICON_LAST = 609199;
    static final String ICON_REL_DIR = "../mibsdk-spike/icons/";
    static final String ICON_ABS_DIR = "/mnt/app/eso/hmi/lsd/mibsdk-spike/icons/";
    static final int ICON_PLUG = 609100;
    static final int ICON_PLUG_REFLEX = 609101;
    static final int ICON_NEXT = 609102;
    static final int ICON_NEXT_REFLEX = 609103;
    static final int ICON_POP_A = 609110;
    static final int ICON_POP_B = 609111;
    static final int ICON_P65 = 609120;
    /** System-module stock phone icon pair (row 1, I2). */
    static final int SYS_ICON_PHONE = 422;
    static final int SYS_ICON_PHONE_REFLEX = 423;
    /** Stock System status-bar icon used beside the custom one in popup 65 (S3). */
    static final int SYS_ICON_STATUS = 230;
    /** SDK-owned partial popup registered through the Car factory's stubs (S1). */
    static final int POPUP_SDK = 609600;
    /** System ResourceLocatorModel of the status bar's provider-logo cell (S5). */
    static final int SYS_PROVIDER_LOGO_MODEL = 3838;

    /** Main menu (Boot B): System screen 46, the wizard ring; SDK rows' text ids, widgetIDs. */
    static final int SYS_SCREEN_MAINWIZARD = 46;
    static final int WIZARD_TEXT_BASE = 90000;
    static final int[] WIZARD_WIDGET_IDS = {10, 11, 12, 14};
    static final int WIZARD_INTERNAL_ID_BASE = 90100;
    /** widgetID whose atlas mapping the "wrench" command rewrites ({16, 9}: the unused red wrench cell). */
    static final int WRENCH_WIDGET_ID = 11;

    private SpikeIds() {
    }
}
