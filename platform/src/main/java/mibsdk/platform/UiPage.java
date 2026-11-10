package mibsdk.platform;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** An SDK page in a {@link UiSnapshot}; key is {@code <ownerId>/<pageId>}. Immutable. */
public final class UiPage {

    private final String key;
    private final String title;
    private final List<UiEntry> entries;
    private final boolean ring;

    public UiPage(String key, String title, List<UiEntry> entries) {
        this(key, title, entries, false);
    }

    /** {@code ring}: the page's LINK and ITEM rows are a ring (the stock 3D wizard) instead of a list. */
    public UiPage(String key, String title, List<UiEntry> entries, boolean ring) {
        this.key = key;
        this.title = title;
        this.entries = entries == null
                ? Collections.<UiEntry>emptyList()
                : Collections.unmodifiableList(new ArrayList<UiEntry>(entries));
        this.ring = ring;
    }

    public String key() {
        return key;
    }

    public String title() {
        return title;
    }

    /** Unmodifiable. */
    public List<UiEntry> entries() {
        return entries;
    }

    /**
     * True when the page is drawn as a ring: its LINK and ITEM rows are the ring's items, each with its icon.
     * A unit that cannot draw the ring shows the same rows as a list.
     */
    public boolean ring() {
        return ring;
    }
}
