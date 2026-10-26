package mibsdk.adapter.hqc.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import mibsdk.platform.UiEntry;
import mibsdk.platform.UiIcon;
import mibsdk.platform.UiPage;
import mibsdk.platform.UiPageText;

/**
 * What a page screen is built with beyond its title: the item icons' bitmaps of the list's icon cell (C7), and the
 * typed rows (dropdown, slider) after the list, in spec order ({@link #typed}: slot = position). A row's icon is an
 * index into {@link #iconIds}, so a page whose icon set changes needs a new screen; so does any change to a typed
 * row's kind, key, label, options or range (its value is the pooled model's, not the layout's). Immutable; built by
 * {@link #of}, which only reads (ids come from {@link ItemIcons}, given on {@code mibsdk-ui}).
 */
final class PageLayout {

    /** No item icons, no typed rows. */
    static final PageLayout EMPTY = new PageLayout(new int[0], Collections.<UiIcon, Integer>emptyMap(),
            new TypedRow[0]);

    /** A page holds at most 24 entries, so at most 24 distinct icons. */
    static final int MAX_ICONS = 24;

    private final int[] iconIds;
    private final Map<UiIcon, Integer> indexByIcon;
    private final TypedRow[] typed;

    /**
     * One typed row: a DROPDOWN ({@code options}) or a SLIDER ({@code min}, {@code step}, {@code steps} =
     * {@code (max - min) / step}, {@code centred}). Immutable.
     */
    static final class TypedRow {
        /** {@code UiEntry.DROPDOWN} or {@code UiEntry.SLIDER}. */
        final int kind;
        final String entryKey;
        final String label;
        private final String[] options;
        final int min;
        final int step;
        final int steps;
        final boolean centred;

        TypedRow(int kind, String entryKey, String label, String[] options, int min, int step, int steps,
                 boolean centred) {
            this.kind = kind;
            this.entryKey = entryKey;
            this.label = label == null ? "" : label;
            this.options = options == null ? new String[0] : options;
            this.min = min;
            this.step = step;
            this.steps = steps;
            this.centred = centred;
        }

        static TypedRow of(UiEntry e) {
            if (e.kind() == UiEntry.DROPDOWN) {
                return new TypedRow(e.kind(), e.key(), UiPageText.label(e), e.options(), 0, 0, 0, false);
            }
            int step = e.step() < 1 ? 1 : e.step();
            int steps = e.max() > e.min() ? (e.max() - e.min()) / step : 0;
            return new TypedRow(e.kind(), e.key(), UiPageText.label(e), null, e.min(), step, steps, e.centred());
        }

        /** The number of options (a dropdown's; 0 for a slider). */
        int optionCount() {
            return options.length;
        }

        /** Option {@code i}, or null when out of range. */
        String option(int i) {
            return i < 0 || i >= options.length ? null : options[i];
        }

        /** A slider's highest value. */
        int max() {
            return min + steps * step;
        }

        public boolean equals(Object o) {
            if (!(o instanceof TypedRow)) {
                return false;
            }
            TypedRow r = (TypedRow) o;
            return kind == r.kind && min == r.min && step == r.step && steps == r.steps && centred == r.centred
                    && entryKey.equals(r.entryKey) && label.equals(r.label)
                    && java.util.Arrays.equals(options, r.options);
        }

        public int hashCode() {
            return ((kind * 31 + entryKey.hashCode()) * 31 + label.hashCode()) * 31 + steps;
        }

        public String toString() {
            return (kind == UiEntry.DROPDOWN ? "dropdown " : "slider ") + entryKey;
        }
    }

    private PageLayout(int[] iconIds, Map<UiIcon, Integer> indexByIcon, TypedRow[] typed) {
        this.iconIds = iconIds;
        this.indexByIcon = indexByIcon;
        this.typed = typed;
    }

    /**
     * The layout of {@code p}: the distinct ids of its item icons that have one, in row order, and its typed rows (at
     * most {@link HqcUiIds#TYPED_ROWS}), in spec order.
     */
    static PageLayout of(UiPage p, ItemIcons ids) {
        if (p == null) {
            return EMPTY;
        }
        if (ids == null) {
            ids = ItemIcons.NONE;
        }
        List<Integer> distinct = new ArrayList<Integer>();
        Map<UiIcon, Integer> index = new HashMap<UiIcon, Integer>();
        List<UiEntry> entries = p.entries();
        List<TypedRow> typed = new ArrayList<TypedRow>();
        for (int i = 0; i < entries.size(); i++) {
            UiEntry e = entries.get(i);
            if (e != null && (e.kind() == UiEntry.DROPDOWN || e.kind() == UiEntry.SLIDER)
                    && typed.size() < HqcUiIds.TYPED_ROWS) {
                typed.add(TypedRow.of(e));
                continue;
            }
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
        if (distinct.isEmpty() && typed.isEmpty()) {
            return EMPTY;
        }
        int[] a = new int[distinct.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = distinct.get(i).intValue();
        }
        return new PageLayout(a, Collections.unmodifiableMap(index), typed.toArray(new TypedRow[typed.size()]));
    }

    /** The typed rows (a copy), in spec order: row {@code s} is typed slot {@code s}. */
    TypedRow[] typed() {
        return (TypedRow[]) typed.clone();
    }

    /** Typed slot {@code slot}'s row, or null. */
    TypedRow typedAt(int slot) {
        return slot < 0 || slot >= typed.length ? null : typed[slot];
    }

    /** The number of typed rows. */
    int typedCount() {
        return typed.length;
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
        return java.util.Arrays.equals(iconIds, l.iconIds) && indexByIcon.equals(l.indexByIcon)
                && java.util.Arrays.equals(typed, l.typed);
    }

    public int hashCode() {
        int h = indexByIcon.hashCode();
        for (int i = 0; i < iconIds.length; i++) {
            h = h * 31 + iconIds[i];
        }
        for (int i = 0; i < typed.length; i++) {
            h = h * 31 + typed[i].hashCode();
        }
        return h;
    }

    public String toString() {
        StringBuffer b = new StringBuffer("PageLayout[");
        for (int i = 0; i < iconIds.length; i++) {
            b.append(i == 0 ? "" : ", ").append(iconIds[i]);
        }
        for (int i = 0; i < typed.length; i++) {
            b.append(i == 0 ? "; " : ", ").append(typed[i]);
        }
        return b.append(']').toString();
    }
}
