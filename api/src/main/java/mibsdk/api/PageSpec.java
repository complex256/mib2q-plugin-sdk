package mibsdk.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The content of a plugin page. Immutable; every builder call returns a new PageSpec.
 *
 * <p>Ids (page and item) must match {@code [A-Za-z0-9._-]{1,32}} and item ids must be unique within a page,
 * else {@link IllegalArgumentException}. Text is never rejected: the runtime truncates it to the MAX_* lengths.
 * Adding entry {@code MAX_ENTRIES + 1} throws {@link IllegalStateException}.
 */
public final class PageSpec {

    public static final int MAX_ENTRIES = 24;
    public static final int MAX_TITLE = 32;
    public static final int MAX_LABEL = 40;
    public static final int MAX_VALUE = 20;
    public static final int MAX_INFO = 80;

    static final int MAX_ID = 32;

    public enum Kind { ITEM, TOGGLE, INFO, LINK }

    /** One row of a page. {@link #itemId()} is null for INFO; {@link #label()} holds INFO's text. */
    public static final class Entry {

        private final Kind kind;
        private final String itemId;
        private final String label;
        private final String value;
        private final boolean on;
        private final Page target;

        Entry(Kind kind, String itemId, String label, String value, boolean on, Page target) {
            this.kind = kind;
            this.itemId = itemId;
            this.label = label;
            this.value = value;
            this.on = on;
            this.target = target;
        }

        public Kind kind() {
            return kind;
        }

        public String itemId() {
            return itemId;
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

        public Page target() {
            return target;
        }
    }

    private final String id;
    private final String title;
    private final List<Entry> entries;

    private PageSpec(String id, String title, List<Entry> entries) {
        this.id = id;
        this.title = title;
        this.entries = entries;
    }

    public static PageSpec of(String id, String title) {
        requireId(id, "page id");
        return new PageSpec(id, title, Collections.<Entry>emptyList());
    }

    /** {@code value} may be null. */
    public PageSpec item(String itemId, String label, String value) {
        return with(new Entry(Kind.ITEM, checkedItemId(itemId), label, value, false, null));
    }

    public PageSpec toggle(String itemId, String label, boolean on) {
        return with(new Entry(Kind.TOGGLE, checkedItemId(itemId), label, null, on, null));
    }

    public PageSpec info(String text) {
        return with(new Entry(Kind.INFO, null, text, null, false, null));
    }

    public PageSpec link(String itemId, String label, Page target) {
        String checked = checkedItemId(itemId);
        if (target == null) {
            throw new IllegalArgumentException("link '" + itemId + "' needs a target page");
        }
        return with(new Entry(Kind.LINK, checked, label, null, false, target));
    }

    public String id() {
        return id;
    }

    public String title() {
        return title;
    }

    /** Unmodifiable, in insertion order. */
    public List<Entry> entries() {
        return entries;
    }

    private String checkedItemId(String itemId) {
        requireId(itemId, "item id");
        for (int i = 0; i < entries.size(); i++) {
            if (itemId.equals(entries.get(i).itemId())) {
                throw new IllegalArgumentException("duplicate item id '" + itemId + "' on page '" + id + "'");
            }
        }
        return itemId;
    }

    private PageSpec with(Entry e) {
        if (entries.size() >= MAX_ENTRIES) {
            throw new IllegalStateException("page '" + id + "' already has " + MAX_ENTRIES + " entries");
        }
        List<Entry> next = new ArrayList<Entry>(entries.size() + 1);
        next.addAll(entries);
        next.add(e);
        return new PageSpec(id, title, Collections.unmodifiableList(next));
    }

    private static void requireId(String s, String what) {
        if (!isValidId(s)) {
            throw new IllegalArgumentException("invalid " + what + " '" + s + "': must match [A-Za-z0-9._-]{1,32}");
        }
    }

    /** Equivalent of ^[A-Za-z0-9._-]{1,32}$. No java.util.regex: the car has none. */
    static boolean isValidId(String s) {
        if (s == null) {
            return false;
        }
        int n = s.length();
        if (n < 1 || n > MAX_ID) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
