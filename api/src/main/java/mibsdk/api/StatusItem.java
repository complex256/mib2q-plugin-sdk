package mibsdk.api;

/** A plugin's status-line item. {@link #cancel()} removes it. */
public interface StatusItem extends Subscription {

    /** Longer text is truncated by the runtime, never rejected. */
    int MAX_TEXT = 12;

    /** The largest status icon, in pixels, either way. */
    int ICON_MAX_PX = 20;

    /** {@code null} or {@code ""} hides the item. The item keeps its icon. */
    void set(String text);

    /**
     * Sets the text and the icon; a null icon means none. {@code null} or {@code ""} text hides the item, icon and all.
     * The icon must come from this plugin's {@link Ui}; an installed icon wider or taller than {@link #ICON_MAX_PX}
     * throws {@link IllegalArgumentException}, whether or not the unit draws icons ({@link Features#UI_ICONS}).
     *
     * @since api 3
     */
    void set(String text, Icon icon);
}
