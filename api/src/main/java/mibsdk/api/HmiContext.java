package mibsdk.api;

/**
 * The HMI context the built-in status bar is in, for {@link StatusItem#visibleIn(int...) status items that only make
 * sense on some screens}. The values are the stock status bar's {@code HMI_CONTEXT_*} values (model 138): CAR (0) and
 * MEDIA (2) are measured on the bench; the others follow the stock declaration order. {@link #NONE} is never a context
 * to show in.
 *
 * <p>An item with no {@code visibleIn} call shows in every context, which is the default. The built-in bar applies the
 * limit; the popup fallback (where the bar is unavailable) shows every item.
 */
public final class HmiContext {

    public static final int CAR = 0;
    public static final int TUNER = 1;
    public static final int MEDIA = 2;
    public static final int PHONE = 3;
    public static final int NAVI = 4;
    public static final int MAP = 5;
    public static final int ONLINE = 6;
    public static final int OFFICE = 7;
    public static final int TONE = 8;
    public static final int SETTINGS = 9;
    /** The value the stock bar reports before the screen's stub has pushed model 138. Never pass it to visibleIn. */
    public static final int NONE = -1;

    private HmiContext() {
    }
}
