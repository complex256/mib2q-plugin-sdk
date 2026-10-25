package mibsdk.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import mibsdk.platform.UiIcon;
import mibsdk.platform.UiStatusPart;
import mibsdk.platform.UiText;

/**
 * The status line: the runtime's own indicators (spec section 6.1: the runtime owns them, so a plugin cannot forget
 * one), then the plugins' items. Pure functions.
 *
 * <p>Priority: {@code SAFE} first, then {@code PDC R OFF}, then the other overrides. {@code SAFE} and
 * {@code PDC R OFF} are safety indicators and must show ({@link #isMustShow}): they are never dropped for plugin items
 * and never hide each other. The other ({@code OVR ...}) indicators never crowd out the plugin items: while there are
 * items, all indicators together get at most the line minus the items' reserve (the items' joined length plus a
 * space, at most half the line), and an {@code OVR} one that would exceed that is dropped (the SDK page lists every
 * override).
 *
 * <p>Fair share: the items share what the indicators leave (if nothing is left, no item shows), max-min fair ({@link #shares}), so no plugin's item
 * crowds out another's. The caller passes them in the user's order ({@link #order}) with hidden ones left out.
 */
final class StatusLine {

    static final String SAFE = "SAFE";
    static final String REAR_PDC_OFF = "PDC R OFF";
    static final String OVERRIDE_PREFIX = "OVR ";
    /**
     * The longest line: what popup 65 fits (spike S3b). A longer line would be cut by the popup with {@code ...},
     * which defeats the fair share.
     */
    static final int MAX_LINE_CHARS = 24;
    /** The shortest line: the must-show indicators together are never cut. */
    static final int MIN_LINE_CHARS = (SAFE + " " + REAR_PDC_OFF).length();
    /** Parking keys of any other kind show as {@code OVR PDC}. */
    static final String PARKING_PREFIX = "parking.";
    /** Other keys show their last dot-separated segment, cut to this many characters. */
    static final int MAX_KEY_CHARS = 8;
    /** The narrowest share an item is cut to; below it items are dropped from the end of the order instead. */
    static final int MIN_ITEM_CHARS = 3;
    /** A cut item narrower than this is cut hard, without {@code ...}. */
    static final int ELLIPSIS_MIN_CHARS = 6;

    /** What an icon takes of its item's width on the line, in characters. */
    static final int ICON_CHARS = 2;

    /**
     * One plugin's status item as a unit: its owner, cleaned text and optional icon. The fair share is computed on
     * {@link #width}, which counts the icon as {@link #ICON_CHARS}; {@link #render} and {@link #iconAt} draw it in a
     * share. An item with an empty text is hidden, icon or not.
     */
    static final class Item {
        /** The plugin id, or null (tests, runtime-made items). */
        final String owner;
        final String text;
        /** Null for none. */
        final UiIcon icon;

        Item(String owner, String text) {
            this(owner, text, null);
        }

        Item(String owner, String text, UiIcon icon) {
            this.owner = owner;
            this.text = text == null ? "" : text;
            this.icon = icon;
        }

        /** Characters the item wants on the line, its icon included. */
        int width() {
            return text.length() + (icon != null ? ICON_CHARS : 0);
        }

        /**
         * The icon, if it is kept in {@code width} characters: only while the text beside it still gets
         * {@link #MIN_ITEM_CHARS} (or all of a shorter text); else null, and the text takes the whole share.
         */
        UiIcon iconAt(int width) {
            return icon != null && width >= ICON_CHARS + Math.min(text.length(), MIN_ITEM_CHARS) ? icon : null;
        }

        /**
         * The item's text in {@code width} characters, less {@link #ICON_CHARS} when its icon is kept
         * ({@link StatusLine#cut}).
         */
        String render(int width) {
            return cut(text, iconAt(width) != null ? width - ICON_CHARS : width);
        }

        @Override
        public String toString() {
            return owner + ":" + (icon != null ? "[" + icon + "]" : "") + text;
        }
    }

    private StatusLine() {
    }

    /**
     * {@code SAFE} in safe mode; {@code PDC R OFF} for a held or restoring {@code parking.soundDirections} entry with
     * rear off ({@code R0}); then one short {@code OVR <name>} per other entry: {@code OVR PDC} for {@code parking.*},
     * else the key's last segment (at most {@link #MAX_KEY_CHARS}). No duplicates.
     */
    static List<String> indicators(SdkState s) {
        List<String> out = new ArrayList<String>();
        if (s == null) {
            return out;
        }
        if (s.safeMode) {
            out.add(SAFE);
        }
        List<String> others = new ArrayList<String>();
        for (int i = 0; i < s.overrides.size(); i++) {
            ActiveOverride o = s.overrides.get(i);
            String key = o.key() == null ? "" : o.key();
            String value = o.activeValue();
            if (ParkingImpl.KEY.equals(key) && value != null && value.indexOf("R0") >= 0) {
                addOnce(out, REAR_PDC_OFF);
            } else if (key.startsWith(PARKING_PREFIX)) {
                addOnce(others, OVERRIDE_PREFIX + "PDC");
            } else {
                String last = key.substring(key.lastIndexOf('.') + 1);
                addOnce(others, OVERRIDE_PREFIX + (last.length() > MAX_KEY_CHARS ? last.substring(0, MAX_KEY_CHARS) : last));
            }
        }
        for (int i = 0; i < others.size(); i++) {
            addOnce(out, others.get(i));
        }
        return out;
    }

    /**
     * Indicators (the must-show ones always; the others while they fit their budget, see the class comment), then
     * the items in the given order (empty ones skipped), each cut to its fair share (see {@link #shares}), joined by
     * one space and cleaned to {@code maxChars}. Text only: composed as if no item had an icon (the popup-65 line).
     */
    static String line(List<String> indicators, List<Item> items, int maxChars) {
        List<Item> plain = new ArrayList<Item>();
        if (items != null) {
            for (int i = 0; i < items.size(); i++) {
                Item s = items.get(i);
                plain.add(s == null ? null : new Item(s.owner, s.text));
            }
        }
        List<UiStatusPart> parts = layout(indicators, plain, maxChars);
        StringBuffer b = new StringBuffer();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                b.append(' ');
            }
            b.append(parts.get(i).text());
        }
        return UiText.clean(b.toString(), maxChars);
    }

    /**
     * The same line as parts, icons kept: the indicators {@link #line} keeps, joined by one space into one part
     * marked as indicators (none if no indicator shows), then one part per item shown, its text cut to its share and
     * its icon kept only where {@link Item#iconAt} allows.
     */
    static List<UiStatusPart> parts(List<String> indicators, List<Item> items, int maxChars) {
        return layout(indicators, items, maxChars);
    }

    /** {@link #line} and {@link #parts}: the indicator budget, then each shown item's fair share. */
    private static List<UiStatusPart> layout(List<String> indicators, List<Item> items, int maxChars) {
        List<Item> it = new ArrayList<Item>();
        if (items != null) {
            for (int i = 0; i < items.size(); i++) {
                Item s = items.get(i);
                if (s != null && s.text.length() > 0) {
                    it.add(s);
                }
            }
        }
        int joined = 0;
        for (int i = 0; i < it.size(); i++) {
            joined += (i > 0 ? 1 : 0) + it.get(i).width();
        }
        int reserve = it.isEmpty() ? 0 : Math.min(joined + 1, maxChars / 2);
        int budget = maxChars - reserve;
        StringBuffer b = new StringBuffer();
        if (indicators != null) {
            for (int i = 0; i < indicators.size(); i++) {
                String s = indicators.get(i);
                if (s == null || s.length() == 0) {
                    continue;
                }
                int len = b.length() + (b.length() > 0 ? 1 : 0) + s.length();
                if (!isMustShow(s) && len > budget) {
                    continue; // an OVR indicator over budget; a later must-show one still shows
                }
                if (b.length() > 0) {
                    b.append(' ');
                }
                b.append(s);
            }
        }
        List<UiStatusPart> out = new ArrayList<UiStatusPart>();
        if (b.length() > 0) {
            out.add(new UiStatusPart(b.toString(), null, true));
        }
        int room = maxChars - b.length() - (b.length() > 0 ? 1 : 0);
        int[] w = shares(it, room);
        for (int i = 0; i < w.length; i++) {
            Item s = it.get(i);
            out.add(new UiStatusPart(s.render(w[i]), s.iconAt(w[i]), false));
        }
        return out;
    }

    /** {@link #line} for plain texts without owners. */
    static String compose(List<String> indicators, List<String> texts, int maxChars) {
        List<Item> items = new ArrayList<Item>();
        if (texts != null) {
            for (int i = 0; i < texts.size(); i++) {
                items.add(new Item(null, texts.get(i)));
            }
        }
        return line(indicators, items, maxChars);
    }

    /**
     * Max-min fair ("water-filling") widths for {@code items} in {@code room} characters, one space between items.
     * First, while the items left cannot all get {@link #MIN_ITEM_CHARS} (or their own length, if shorter), the last
     * one is dropped (the result is shorter than {@code items}; empty if not even the first fits). Then an item no longer than
     * an equal share of the text room keeps its full length and the rest is shared again among the longer ones; each
     * of those gets the final equal share, the odd characters going to the earliest ones.
     */
    static int[] shares(List<Item> items, int room) {
        int n = items.size();
        while (n > 0) {
            int need = n - 1;
            for (int i = 0; i < n; i++) {
                need += Math.min(items.get(i).width(), MIN_ITEM_CHARS);
            }
            if (need <= room) {
                break;
            }
            n--;
        }
        int[] w = new int[n];
        int left = room - (n > 0 ? n - 1 : 0);
        if (n == 0 || left <= 0) {
            return new int[0];
        }
        boolean[] done = new boolean[n];
        int open = n;
        boolean settled = false;
        while (open > 0 && !settled) {
            int share = left / open;
            settled = true;
            for (int i = 0; i < n; i++) {
                int len = items.get(i).width();
                if (!done[i] && len <= share) {
                    w[i] = len;
                    done[i] = true;
                    left -= len;
                    open--;
                    settled = false;
                }
            }
        }
        if (open > 0) {
            int share = left / open;
            int odd = left % open;
            for (int i = 0; i < n; i++) {
                if (!done[i]) {
                    w[i] = share + (odd > 0 ? 1 : 0);
                    odd--;
                }
            }
        }
        return w;
    }

    /**
     * Saved ids first, in saved order (once each, only those in {@code ids}), then the other {@code ids} sorted by
     * plugin id. A total order, so any subset keeps its relative order.
     */
    static List<String> order(List<String> saved, Collection<String> ids) {
        List<String> out = new ArrayList<String>();
        if (saved != null) {
            for (int i = 0; i < saved.size(); i++) {
                String id = saved.get(i);
                if (ids.contains(id) && !out.contains(id)) {
                    out.add(id);
                }
            }
        }
        List<String> rest = new ArrayList<String>();
        for (Iterator<String> it = ids.iterator(); it.hasNext();) {
            String id = it.next();
            if (!out.contains(id) && !rest.contains(id)) {
                rest.add(id);
            }
        }
        Collections.sort(rest);
        out.addAll(rest);
        return out;
    }

    /**
     * {@code s} in at most {@code width} chars: whole if it fits; else, from {@link #ELLIPSIS_MIN_CHARS} on, the head
     * plus {@code ...}; narrower, a hard cut (an ellipsis would leave almost nothing). Never ends in a space.
     */
    static String cut(String s, int width) {
        if (s.length() <= width) {
            return s;
        }
        if (width >= ELLIPSIS_MIN_CHARS) {
            return trimEnd(s.substring(0, width - 3)) + "...";
        }
        return trimEnd(s.substring(0, Math.max(0, width)));
    }

    private static String trimEnd(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == ' ') {
            end--;
        }
        return s.substring(0, end);
    }

    /** {@code SAFE} and {@code PDC R OFF}: shown whatever the items need. */
    static boolean isMustShow(String indicator) {
        return SAFE.equals(indicator) || REAR_PDC_OFF.equals(indicator);
    }

    private static void addOnce(List<String> list, String s) {
        if (!list.contains(s)) {
            list.add(s);
        }
    }
}
