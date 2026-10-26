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
 *
 * <p>The status line comes twice: {@link #statusParts} (indicators, then each item with its icon, each already cut to
 * its share) for a popup that draws icons, and {@link #statusLine}, the same items composed as text only, without
 * icons, for the text-only popup-65 fallback.
 *
 * <p>{@link #dialog} is the confirm or speller to show over the plugin's open page, {@link #toast} the latest toast;
 * each is null for none.
 */
public final class UiSnapshot {

    public static final UiSnapshot EMPTY =
            new UiSnapshot(0L, null, "", null, 0L, null);

    private final long version;
    private final List<UiRow> carRows;
    private final String statusLine;
    private final List<UiStatusPart> statusParts;
    private final Map<String, UiPage> pages;
    private final long openSeq;
    private final String openPageKey;
    private final boolean down;
    private final UiDialog dialog;
    private final UiToast toast;

    public UiSnapshot(long version, List<UiRow> carRows, String statusLine,
                      Map<String, UiPage> pages, long openSeq, String openPageKey) {
        this(version, carRows, statusLine, pages, openSeq, openPageKey, textPart(statusLine), null, null, false);
    }

    /** @param statusParts the status line with icons; null is none. {@code statusLine} is the text-only line. */
    public UiSnapshot(long version, List<UiRow> carRows, String statusLine,
                      Map<String, UiPage> pages, long openSeq, String openPageKey, List<UiStatusPart> statusParts) {
        this(version, carRows, statusLine, pages, openSeq, openPageKey, statusParts, null, null, false);
    }

    /** As above, with the dialog and the toast to show; null is none. */
    public UiSnapshot(long version, List<UiRow> carRows, String statusLine, Map<String, UiPage> pages,
                      long openSeq, String openPageKey, List<UiStatusPart> statusParts, UiDialog dialog,
                      UiToast toast) {
        this(version, carRows, statusLine, pages, openSeq, openPageKey, statusParts, dialog, toast, false);
    }

    private UiSnapshot(long version, List<UiRow> carRows, String statusLine, Map<String, UiPage> pages,
                       long openSeq, String openPageKey, List<UiStatusPart> statusParts, UiDialog dialog,
                       UiToast toast, boolean down) {
        this.version = version;
        this.carRows = carRows == null
                ? Collections.<UiRow>emptyList()
                : Collections.unmodifiableList(new ArrayList<UiRow>(carRows));
        this.statusLine = statusLine == null ? "" : statusLine;
        this.statusParts = statusParts == null
                ? Collections.<UiStatusPart>emptyList()
                : Collections.unmodifiableList(new ArrayList<UiStatusPart>(statusParts));
        this.pages = pages == null
                ? Collections.<String, UiPage>emptyMap()
                : Collections.unmodifiableMap(new HashMap<String, UiPage>(pages));
        this.openSeq = openSeq;
        this.openPageKey = openPageKey;
        this.down = down;
        this.dialog = dialog;
        this.toast = toast;
    }

    /**
     * The runtime is stopping: no rows, no status line, no pages, and the adapter takes every piece of SDK UI off
     * screen (an open SDK page returns to the stock screen it came from, not to the stopped notice).
     */
    public static UiSnapshot down(long version) {
        return new UiSnapshot(version, null, "", null, 0L, null, null, null, null, true);
    }

    /** One text part, not marked as indicators, for a non-empty line; none otherwise. */
    private static List<UiStatusPart> textPart(String statusLine) {
        return statusLine == null || statusLine.length() == 0
                ? null
                : Collections.singletonList(new UiStatusPart(statusLine, null, false));
    }

    /** True for {@link #down}. */
    public boolean isDown() {
        return down;
    }

    public long version() {
        return version;
    }

    /** Unmodifiable. */
    public List<UiRow> carRows() {
        return carRows;
    }

    /** The text-only line, composed without icons (the popup-65 fallback). */
    public String statusLine() {
        return statusLine;
    }

    /** Unmodifiable. The status line with icons: the indicators as one part, then one part per item shown. */
    public List<UiStatusPart> statusParts() {
        return statusParts;
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

    /** The confirm or speller to show over its owner's page; null for none. */
    public UiDialog dialog() {
        return dialog;
    }

    /** The latest toast; null for none. */
    public UiToast toast() {
        return toast;
    }
}
