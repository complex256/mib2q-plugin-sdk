package mibsdk.api;

/**
 * The HMI context of the built-in status bar, for {@link StatusItem#visibleIn(int...)}.
 *
 * <p>The values are the stock status bar's {@code HMI_CONTEXT_*} values. Car and Media are verified on
 * the bench; the others follow the stock declaration order. An item with no {@code visibleIn} call shows
 * in every context.
 *
 */
public final class HmiContext {

    /** The Car screen. */
    public static final int CAR = 0;
    /** Radio / tuner. */
    public static final int TUNER = 1;
    /** Media. */
    public static final int MEDIA = 2;
    /** Phone. */
    public static final int PHONE = 3;
    /** Navigation. */
    public static final int NAVI = 4;
    /** The map. */
    public static final int MAP = 5;
    /** Online services. */
    public static final int ONLINE = 6;
    /** Office. */
    public static final int OFFICE = 7;
    /** Tone settings. */
    public static final int TONE = 8;
    /** Settings. */
    public static final int SETTINGS = 9;
    /** The value reported before the screen has pushed its context. Never pass it to {@code visibleIn}. */
    public static final int NONE = -1;

    private HmiContext() {
    }
}
