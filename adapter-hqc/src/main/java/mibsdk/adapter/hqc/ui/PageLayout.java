package mibsdk.adapter.hqc.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import mibsdk.platform.UiEntry;
import mibsdk.platform.UiIcon;
import mibsdk.platform.UiPage;

/**
 * What a page screen is built with beyond its title: the item icons' bitmaps of the list's icon cell (C7). A row's
 * icon is an index into {@link #iconIds}, so a page whose icon set changes needs a new screen. Immutable; built by
 * {@link #of}, which only reads (ids come from {@link ItemIcons}, given on {@code mibsdk-ui}).
 */
final class PageLayout {

    /** No item icons. */
    static final PageLayout EMPTY = new PageLayout(new int[0], Collections.<UiIcon, Integer>emptyMap());

    /** A page holds at most 24 entries, so at most 24 distinct icons. */
    static final int MAX_ICONS = 24;

    private final int[] iconIds;
    private final Map<UiIcon, Integer> indexByIcon;

    private PageLayout(int[] iconIds, Map<UiIcon, Integer> indexByIcon) {
        this.iconIds = iconIds;
        this.indexByIcon = indexByIcon;
    }

    /** The layout of {@code p}'s list rows: the distinct ids of its item icons that have one, in row order. */
    static PageLayout of(UiPage p, ItemIcons ids) {
        if (p == null || ids == null) {
            return EMPTY;
        }
        List<Integer> distinct = new ArrayList<Integer>();
        Map<UiIcon, Integer> index = new HashMap<UiIcon, Integer>();
        List<UiEntry> entries = p.entries();
        for (int i = 0; i < entries.size(); i++) {
            UiEntry e = entries.get(i);
            if (e == null || e.kind() != UiEntry.ITEM || e.icon() == null || index.containsKey(e.icon())) {
                continue;
            }
            int id = ids.id(e.icon());
            if (id < 0) {
                continue;
            }
            Integer boxed = new Integer(id);
            int at = distinct.indexOf(boxed);
            if (at < 0) {
                if (distinct.size() == MAX_ICONS) {
                    continue;
                }
                at = distinct.size();
                distinct.add(boxed);
            }
            index.put(e.icon(), new Integer(at));
        }
        if (distinct.isEmpty()) {
            return EMPTY;
        }
        int[] a = new int[distinct.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = distinct.get(i).intValue();
        }
        return new PageLayout(a, Collections.unmodifiableMap(index));
    }

    /** The icon cell's bitmaps (a copy): the distinct image ids of the page's item icons, in row order. */
    int[] iconIds() {
        return (int[]) iconIds.clone();
    }

    /** {@code icon}'s index in {@link #iconIds}, or -1 if it is not in this layout. */
    int iconIndex(UiIcon icon) {
        Integer i = icon == null ? null : indexByIcon.get(icon);
        return i == null ? -1 : i.intValue();
    }

    public boolean equals(Object o) {
        if (!(o instanceof PageLayout)) {
            return false;
        }
        PageLayout l = (PageLayout) o;
        return java.util.Arrays.equals(iconIds, l.iconIds) && indexByIcon.equals(l.indexByIcon);
    }

    public int hashCode() {
        int h = indexByIcon.hashCode();
        for (int i = 0; i < iconIds.length; i++) {
            h = h * 31 + iconIds[i];
        }
        return h;
    }

    public String toString() {
        StringBuffer b = new StringBuffer("PageLayout[");
        for (int i = 0; i < iconIds.length; i++) {
            b.append(i == 0 ? "" : ", ").append(iconIds[i]);
        }
        return b.append(']').toString();
    }
}
