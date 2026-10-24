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
    static final int MODEL_COUNT = ROWS + 4;
    static final int TEXT_BASE = 609000;
    /** Factory texts served for TEXT_BASE + 0..TEXTS-1 (rows 0..8, then page texts). */
    static final int TEXTS = 32;
    static final int TEXT_PAGE_TITLE = TEXT_BASE + 20;
    static final int TEXT_PAGE_INFO = TEXT_BASE + 21;
    static final int INTERNAL_ID_BASE = 609900;

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

    private SpikeIds() {
    }
}
