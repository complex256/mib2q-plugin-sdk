package mibsdk.platform;

/** One Car-drawer row in a {@link UiSnapshot}. Selecting it opens the page {@link #pageKey()}. */
public final class UiRow {

    private final String key;
    private final String label;
    private final String pageKey;
    private final UiIcon icon;

    public UiRow(String key, String label, String pageKey) {
        this(key, label, pageKey, null);
    }

    public UiRow(String key, String label, String pageKey, UiIcon icon) {
        this.key = key;
        this.label = label;
        this.pageKey = pageKey;
        this.icon = icon;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    public String pageKey() {
        return pageKey;
    }

    /** Drawn beside the label; null for none. */
    public UiIcon icon() {
        return icon;
    }
}
