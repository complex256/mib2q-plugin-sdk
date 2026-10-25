package mibsdk.platform;

/**
 * One piece of the status line in a {@link UiSnapshot}: the runtime's indicators joined into one part
 * ({@link #indicator()} true), or one plugin item with its optional icon. Its text is already cut to its share of the
 * line, and the icon is only there when the share left room for it. Immutable.
 */
public final class UiStatusPart {

    private final String text;
    private final UiIcon icon;
    private final boolean indicator;

    public UiStatusPart(String text, UiIcon icon, boolean indicator) {
        this.text = text == null ? "" : text;
        this.icon = icon;
        this.indicator = indicator;
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

    public String toString() {
        return (indicator ? "ind:" : "") + (icon != null ? "[" + icon + "] " : "") + text;
    }
}
