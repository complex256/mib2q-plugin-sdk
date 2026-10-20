package mibsdk.platform;

/** One Car-drawer row in a {@link UiSnapshot}. Selecting it opens the page {@link #pageKey()}. */
public final class UiRow {

    private final String key;
    private final String label;
    private final String pageKey;

    public UiRow(String key, String label, String pageKey) {
        this.key = key;
        this.label = label;
        this.pageKey = pageKey;
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
}
