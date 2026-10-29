package mibsdk.platform;

import mibsdk.api.UiColor;

/**
 * One piece of the status line in a {@link UiSnapshot}: the runtime's indicators joined into one part
 * ({@link #indicator()} true), or one plugin item with its optional icon. Its text is already cut to its share of the
 * line, and the icon is only there when the share left room for it. Immutable.
 */
public final class UiStatusPart {

    private final String text;
    private final String fullText;
    private final UiIcon icon;
    private final boolean indicator;
    private final UiColor colour;
    private final long contexts;

    public UiStatusPart(String text, UiIcon icon, boolean indicator) {
        this(text, icon, indicator, null, text, 0L);
    }

    /**
     * @param colour the text's colour in the built-in bar; null for the bar's own colour
     */
    public UiStatusPart(String text, UiIcon icon, boolean indicator, UiColor colour) {
        this(text, icon, indicator, colour, text, 0L);
    }

    /**
     * @param fullText the item's whole text, before the popup's fair-share cut: the built-in bar gives each item a
     *     row, so it shows this instead of {@code text}
     */
    public UiStatusPart(String text, UiIcon icon, boolean indicator, UiColor colour, String fullText) {
        this(text, icon, indicator, colour, fullText, 0L);
    }

    /**
     * @param contexts a bitmask of {@link mibsdk.api.HmiContext} values the item shows in; 0 for every context
     */
    public UiStatusPart(String text, UiIcon icon, boolean indicator, UiColor colour, String fullText, long contexts) {
        this.text = text == null ? "" : text;
        this.fullText = fullText == null ? this.text : fullText;
        this.icon = icon;
        this.indicator = indicator;
        this.colour = colour;
        this.contexts = contexts;
    }

    public String text() {
        return text;
    }

    /** The item's text before the popup's fair-share cut (the same as {@link #text()} when it was not cut). */
    public String fullText() {
        return fullText;
    }

    /** Drawn before the text; null for none. */
    public UiIcon icon() {
        return icon;
    }

    /** True for the runtime's indicators ({@code SAFE}, {@code PDC R OFF}, {@code OVR ...}). */
    public boolean indicator() {
        return indicator;
    }

    /** The contexts the item shows in: a bitmask of {@link mibsdk.api.HmiContext} values; 0 for every context. */
    public long contexts() {
        return contexts;
    }

    /** True if this part shows in {@code context} ({@link mibsdk.api.HmiContext}); an unknown (-1) context shows all. */
    public boolean showsIn(int context) {
        return contexts == 0L || context < 0
                || (context < 64 && (contexts & (1L << context)) != 0L);
    }

    /** The text colour, or null for the bar's own colour. */
    public UiColor colour() {
        return colour;
    }

    /**
     * The text colour packed as {@code 0xAARRGGBB}, or -1 for none. Stock-thread layout code uses this instead of
     * {@link #colour()}, so it never loads an api class.
     */
    public long colourArgb() {
        return colour == null ? -1L : ((long) colour.argb()) & 0xFFFFFFFFL;
    }

    public String toString() {
        return (indicator ? "ind:" : "") + (icon != null ? "[" + icon + "] " : "") + text;
    }
}
