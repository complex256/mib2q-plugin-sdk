package mibsdk.platform;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the adapter draws, published whole by the runtime. Immutable: lists and maps are
 * unmodifiable copies. {@code carRows.get(0)} is the Plugins row (except in {@link #EMPTY}).
 * {@code openSeq} increases each time the runtime asks the adapter to open {@code openPageKey}.
 */
public final class UiSnapshot {

    public static final UiSnapshot EMPTY =
            new UiSnapshot(0L, null, "", null, 0L, null);

    private final long version;
    private final List<UiRow> carRows;
    private final String statusLine;
    private final Map<String, UiPage> pages;
    private final long openSeq;
    private final String openPageKey;

    public UiSnapshot(long version, List<UiRow> carRows, String statusLine,
                      Map<String, UiPage> pages, long openSeq, String openPageKey) {
        this.version = version;
        this.carRows = carRows == null
                ? Collections.<UiRow>emptyList()
                : Collections.unmodifiableList(new ArrayList<UiRow>(carRows));
        this.statusLine = statusLine == null ? "" : statusLine;
        this.pages = pages == null
                ? Collections.<String, UiPage>emptyMap()
                : Collections.unmodifiableMap(new HashMap<String, UiPage>(pages));
        this.openSeq = openSeq;
        this.openPageKey = openPageKey;
    }

    public long version() {
        return version;
    }

    /** Unmodifiable. */
    public List<UiRow> carRows() {
        return carRows;
    }

    public String statusLine() {
        return statusLine;
    }

    /** Unmodifiable; keyed by page key. */
    public Map<String, UiPage> pages() {
        return pages;
    }

    public long openSeq() {
        return openSeq;
    }

    public String openPageKey() {
        return openPageKey;
    }
}
