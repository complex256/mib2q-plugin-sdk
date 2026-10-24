package mibsdk.platform;

/** A user action reported by the adapter (stock thread) to the runtime through {@link UiTable#input}. */
public final class UiInput {

    public static final int ROW_SELECTED = 0;
    public static final int ENTRY_SELECTED = 1;
    public static final int TOGGLE_REQUESTED = 2;
    public static final int PAGE_SHOWN = 3;
    public static final int PAGE_CLOSED = 4;

    private final int kind;
    private final String pageKey;
    private final String entryKey;
    private final boolean on;

    /** For {@link #ROW_SELECTED}, {@code pageKey} is the row's key. */
    public UiInput(int kind, String pageKey, String entryKey, boolean on) {
        this.kind = kind;
        this.pageKey = pageKey;
        this.entryKey = entryKey;
        this.on = on;
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
}
