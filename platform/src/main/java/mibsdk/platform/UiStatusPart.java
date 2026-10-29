package mibsdk.platform;

import mibsdk.api.UiColor;

/**
 * One piece of the status line in a {@link UiSnapshot}: the runtime's indicators joined into one part
 * ({@link #indicator()} true), or one plugin item with its optional icon. Its text is already cut to its share of the
 * line, and the icon is only there when the share left room for it. Immutable.
 */
public final class UiStatusPart {

    private final String text;
    private final UiIcon icon;
    private final boolean indicator;
    private final UiColor colour;

    public UiStatusPart(String text, UiIcon icon, boolean indicator) {
        this(text, icon, indicator, null);
    }

    /**
     * @param colour the text's colour in the built-in bar; null for the bar's own colour
     */
    public UiStatusPart(String text, UiIcon icon, boolean indicator, UiColor colour) {
        this.text = text == null ? "" : text;
        this.icon = icon;
        this.indicator = indicator;
        this.colour = colour;
    }

    public String text() {
        return text;
    }

    /** Drawn before the text; null for none. */
    public UiIcon icon() {
        return icon;
    }

    /** True for the runtime's indicators ({@code SAFE}, {@code PDC R OFF}, {@code OVR ...}). */
    public boolean indicator() {
        return indicator;
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
