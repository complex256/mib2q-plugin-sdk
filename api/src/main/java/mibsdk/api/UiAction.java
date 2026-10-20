package mibsdk.api;

/** A user action on a plugin page entry. Immutable. */
public final class UiAction {

    private final String pageId;
    private final String itemId;
    private final Boolean requestedOn;

    public UiAction(String pageId, String itemId, Boolean requestedOn) {
        this.pageId = pageId;
        this.itemId = itemId;
        this.requestedOn = requestedOn;
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

    @Override
    public String toString() {
        return "UiAction{page=" + pageId + ", item=" + itemId + ", requestedOn=" + requestedOn + "}";
    }
}
