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
    /** MODEL_BASE + slot: row visibility ChoiceModel (0 visible, 1 hidden). */
    static final int SELECTION_MODEL = MODEL_BASE + ROWS;      // shared, widgetID = slot
    static final int LABEL_MODEL = MODEL_BASE + ROWS + 1;      // %1 replacement in row 0's text
    static final int MODEL_COUNT = ROWS + 2;
    static final int TEXT_BASE = 609000;
    static final int INTERNAL_ID_BASE = 609900;

    static final int DRAWER_ROW_TYPE_ACTION = 2;
    static final int DRAWER_SELECTION_MODEL = 601227;          // stock cARSEL rows' shared ChoiceModel

    static final int POPUP_STATUS = 65;
    static final int STATUS_LABEL_MODEL = 416;
    static final int STATUS_VISIBLE_CHOICE = 1;

    private SpikeIds() {
    }
}
