package mibsdk.platform;

/** A user action reported by the adapter (stock thread) to the runtime through {@link UiTable#input}. */
public final class UiInput {

    public static final int ROW_SELECTED = 0;
    public static final int ENTRY_SELECTED = 1;
    public static final int TOGGLE_REQUESTED = 2;
    public static final int PAGE_SHOWN = 3;
    public static final int PAGE_CLOSED = 4;
    /** A DROPDOWN pick ({@link #value} = the index) or a SLIDER step ({@link #value} = the raw level). */
    public static final int VALUE_REQUESTED = 5;
    /** A confirm or speller ended: {@link #value} and {@link #text} carry its answer. */
    public static final int DIALOG_DONE = 6;

    private final int kind;
    private final String pageKey;
    private final String entryKey;
    private final boolean on;
    private final int value;
    private final String text;

    /** For {@link #ROW_SELECTED}, {@code pageKey} is the row's key. A {@link #value} of 0 and a null {@link #text}. */
    public UiInput(int kind, String pageKey, String entryKey, boolean on) {
        this(kind, pageKey, entryKey, on, 0, null);
    }

    public UiInput(int kind, String pageKey, String entryKey, boolean on, int value, String text) {
        this.kind = kind;
        this.pageKey = pageKey;
        this.entryKey = entryKey;
        this.on = on;
        this.value = value;
        this.text = text;
    }

    public int kind() {
        return kind;
    }

    public String pageKey() {
        return pageKey;
    }

    public String entryKey() {
        return entryKey;
    }

    /** For {@link #TOGGLE_REQUESTED}: the requested state. */
    public boolean on() {
        return on;
    }

    /** For {@link #VALUE_REQUESTED} and {@link #DIALOG_DONE}; 0 otherwise. */
    public int value() {
        return value;
    }

    /** For {@link #DIALOG_DONE} (a speller's text); null otherwise. */
    public String text() {
        return text;
    }
}
