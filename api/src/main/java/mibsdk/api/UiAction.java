package mibsdk.api;

/** A user action on a plugin page entry, or the answer to a confirm or a speller. Immutable. */
public final class UiAction {

    private final String pageId;
    private final String itemId;
    private final Boolean requestedOn;
    private final Integer value;
    private final String text;

    /** A null {@link #value()} and {@link #text()}. */
    public UiAction(String pageId, String itemId, Boolean requestedOn) {
        this(pageId, itemId, requestedOn, null, null);
    }

    /**
     * Needs api 5.
     *
     * @since api 5
     */
    public UiAction(String pageId, String itemId, Boolean requestedOn, Integer value, String text) {
        this.pageId = pageId;
        this.itemId = itemId;
        this.requestedOn = requestedOn;
        this.value = value;
        this.text = text;
    }

    public String pageId() {
        return pageId;
    }

    public String itemId() {
        return itemId;
    }

    /** Null for ITEM, requested state for TOGGLE. */
    public Boolean requestedOn() {
        return requestedOn;
    }

    /**
     * The picked index (CHOICE, DROPDOWN), the level (SLIDER), or 1 for yes and 0 for no ({@link Ui#confirm});
     * null otherwise. Needs api 5.
     *
     * @since api 5
     */
    public Integer value() {
        return value;
    }

    /**
     * The speller's text ({@link Ui#speller}); null otherwise, and null when the speller never showed. Needs api 5.
     *
     * @since api 5
     */
    public String text() {
        return text;
    }

    @Override
    public String toString() {
        return "UiAction{page=" + pageId + ", item=" + itemId + ", requestedOn=" + requestedOn + ", value=" + value
                + ", text=" + text + "}";
    }
}
