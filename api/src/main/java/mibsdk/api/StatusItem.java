package mibsdk.api;

/** A plugin's status-line item. {@link #cancel()} removes it. */
public interface StatusItem extends Subscription {

    /** Longer text is truncated by the runtime, never rejected. */
    int MAX_TEXT = 12;

    /**
     * The largest status icon, in pixels, either way. 30 px matches the built-in bar's native glyph; a 20 px icon still
     * works and is upscaled there, while the popup fallback and toast draw it at 20 px.
     */
    int ICON_MAX_PX = 30;

    /**
     * {@code null} or {@code ""} hides the item. The item keeps its icon.
     *
     * @since api 2
     */
    void set(String text);

    /**
     * Sets the text and the icon; a null icon means none. {@code null} or {@code ""} text hides the item, icon and all.
     * The icon must come from this plugin's {@link Ui}; an installed icon wider or taller than {@link #ICON_MAX_PX}
     * throws {@link IllegalArgumentException}, whether or not the unit draws icons ({@link Features#UI_ICONS}).
     *
     * @since api 3
     */
    void set(String text, Icon icon);

    /**
     * Shows the icon alone, with no text (an icon-only status item); null hides the item. The built-in bar shows
     * icon-only items in one or more shared rows, right-aligned and in priority order, so several plugin icons are
     * visible at once. A text item (with or without an icon) gets its own row; {@link #set} with a text goes back to a
     * text item. The icon rules are {@link #set(String, Icon)}'s.
     *
     * @since api 6
     */
    void setIcon(Icon icon);

    /**
     * Sets the text colour in every HMI context; null restores the bar's own colour (white, or the app palette). The
     * item keeps its text and icon. In the popup fallback (where the built-in bar is unavailable) the text keeps the
     * popup's colour.
     *
     * @since api 6
     */
    void setColour(UiColor colour);

    /**
     * Shows the item only in these HMI contexts ({@link HmiContext}), e.g. {@code visibleIn(HmiContext.CAR)}. No
     * arguments restores every context, which is the default. The built-in bar applies the limit; the popup fallback
     * shows every item.
     *
     * @throws IllegalArgumentException if a context is outside 0..63 ({@link HmiContext#NONE} is not a context)
     * @since api 6
     */
    void visibleIn(int... contexts);
}
