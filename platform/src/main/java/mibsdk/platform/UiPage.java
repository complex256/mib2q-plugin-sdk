package mibsdk.platform;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** An SDK page in a {@link UiSnapshot}; key is {@code <ownerId>/<pageId>}. Immutable. */
public final class UiPage {

    private final String key;
    private final String title;
    private final List<UiEntry> entries;

    public UiPage(String key, String title, List<UiEntry> entries) {
        this.key = key;
        this.title = title;
        this.entries = entries == null
                ? Collections.<UiEntry>emptyList()
                : Collections.unmodifiableList(new ArrayList<UiEntry>(entries));
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
}
