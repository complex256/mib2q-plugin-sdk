package mibsdk.platform;

/**
 * The latest toast in a {@link UiSnapshot}: the adapter shows each new {@link #seq} once, for {@code Ui.TOAST_MS}.
 * Immutable; its text is already clean.
 */
public final class UiToast {

    private final long seq;
    private final String text;
    private final UiIcon icon;

    public UiToast(long seq, String text, UiIcon icon) {
        this.seq = seq;
        this.text = text == null ? "" : text;
        this.icon = icon;
    }

    /** Increases with every toast; a newer one replaces the one showing. */
    public long seq() {
        return seq;
    }

    public String text() {
        return text;
    }

    /** Drawn before the text; null for none. */
    public UiIcon icon() {
        return icon;
    }
}
