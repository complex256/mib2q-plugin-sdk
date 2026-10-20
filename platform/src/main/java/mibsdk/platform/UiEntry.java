package mibsdk.platform;

/** One entry of a {@link UiPage}, already cleaned and truncated by the runtime. */
public final class UiEntry {

    public static final int ITEM = 0;
    public static final int TOGGLE = 1;
    public static final int INFO = 2;
    public static final int LINK = 3;

    private final int kind;
    private final String key;
    private final String label;
    private final String value;
    private final boolean on;
    private final String targetPageKey;

    public UiEntry(int kind, String key, String label, String value, boolean on, String targetPageKey) {
        this.kind = kind;
        this.key = key;
        this.label = label;
        this.value = value;
        this.on = on;
        this.targetPageKey = targetPageKey;
    }

    public int kind() {
        return kind;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    public String value() {
        return value;
    }

    public boolean on() {
        return on;
    }

    /** For {@link #LINK}: the page it opens; otherwise null. */
    public String targetPageKey() {
        return targetPageKey;
    }
}
