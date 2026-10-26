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
 *
 * <p>Input entries (api 5: {@link #choice}, {@link #dropdown}, {@link #slider}, {@link #progress}, an
 * {@link #item(String, String, String, Icon) item with an icon}, {@link #enabled}) need {@code "api": 5}. Without
 * {@link Features#UI_INPUTS} the unit shows them as text lines ({@code "Level: Mid"}, {@code "Progress: 60%"}) and
 * item icons only with {@link Features#UI_ICONS}. Dropdown and slider rows are drawn after the list rows, in spec
 * order. A slider shows its position, not its number. A slider's knob may lag a fast turn near its ends until the
 * plugin's update arrives.
 */
public final class PageSpec {

    public static final int MAX_ENTRIES = 24;
    public static final int MAX_TITLE = 32;
    public static final int MAX_LABEL = 40;
    public static final int MAX_VALUE = 20;
    public static final int MAX_INFO = 80;

    /**
     * The most options of a {@link #choice} or {@link #dropdown}. Needs api 5.
     *
     * @since api 5
     */
    public static final int MAX_OPTIONS = 8;

    /**
     * The most {@link Kind#DROPDOWN} plus {@link Kind#SLIDER} rows on one page. Needs api 5.
     *
     * @since api 5
     */
    public static final int MAX_INPUT_ROWS = 4;

    /**
     * The most steps of a {@link #slider}: {@code (max - min) / step}. Needs api 5.
     *
     * @since api 5
     */
    public static final int MAX_SLIDER_STEPS = 24;

    /**
     * The largest item icon, in pixels, either way: the icon cell does not scale. Needs api 5.
     *
     * @since api 5
     */
    public static final int ITEM_ICON_MAX_PX = 24;

    static final int MAX_ID = 32;

    /** CHOICE, DROPDOWN, SLIDER and PROGRESS are {@code @since api 5} (Needs api 5). */
    public enum Kind { ITEM, TOGGLE, INFO, LINK, CHOICE, DROPDOWN, SLIDER, PROGRESS }

    /** One row of a page. {@link #itemId()} is null for INFO; {@link #label()} holds INFO's text. */
    public static final class Entry {

        private final Kind kind;
        private final String itemId;
        private final String label;
        private final String value;
        private final boolean on;
        private final Page target;
        private final String[] options;
        private final int number;
        private final int min;
        private final int max;
        private final int step;
        private final boolean centred;
        private final Icon icon;
        private final boolean enabled;

        Entry(Kind kind, String itemId, String label, String value, boolean on, Page target) {
            this(kind, itemId, label, value, on, target, null, 0, 0, 0, 0, false, null, true);
        }

        Entry(Kind kind, String itemId, String label, String value, boolean on, Page target, String[] options,
                int number, int min, int max, int step, boolean centred, Icon icon, boolean enabled) {
            this.kind = kind;
            this.itemId = itemId;
            this.label = label;
            this.value = value;
            this.on = on;
            this.target = target;
            this.options = options;
            this.number = number;
            this.min = min;
            this.max = max;
            this.step = step;
            this.centred = centred;
            this.icon = icon;
            this.enabled = enabled;
        }

        Entry withEnabled(boolean e) {
            return new Entry(kind, itemId, label, value, on, target, options, number, min, max, step, centred, icon,
                    e);
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

        /**
         * A copy of a CHOICE's or DROPDOWN's options; null for the other kinds. Needs api 5.
         *
         * @since api 5
         */
        public String[] options() {
            return options == null ? null : (String[]) options.clone();
        }

        /**
         * The selected index (CHOICE, DROPDOWN), the value (SLIDER) or the percent (PROGRESS); 0 otherwise. Needs
         * api 5.
         *
         * @since api 5
         */
        public int number() {
            return number;
        }

        /**
         * A SLIDER's lowest value; 0 otherwise. Needs api 5.
         *
         * @since api 5
         */
        public int min() {
            return min;
        }

        /**
         * A SLIDER's highest value; 0 otherwise. Needs api 5.
         *
         * @since api 5
         */
        public int max() {
            return max;
        }

        /**
         * A SLIDER's step; 0 otherwise. Needs api 5.
         *
         * @since api 5
         */
        public int step() {
            return step;
        }

        /**
         * True for a SLIDER drawn from its centre (a balance or a tone). Needs api 5.
         *
         * @since api 5
         */
        public boolean centred() {
            return centred;
        }

        /**
         * An ITEM's icon, or null. Needs api 5.
         *
         * @since api 5
         */
        public Icon icon() {
            return icon;
        }

        /**
         * False for a list row turned off with {@link PageSpec#enabled}; true otherwise. Needs api 5.
         *
         * @since api 5
         */
        public boolean enabled() {
            return enabled;
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

    /**
     * {@link #item(String, String, String)} with an icon in its first column; a null icon is
     * {@code item(itemId, label, value)}. The icon must come from this plugin's {@link Ui#icon} or
     * {@link Ui#stockIcon}, and an installed icon is at most {@link #ITEM_ICON_MAX_PX} either way: the page refuses
     * any other with {@link IllegalArgumentException}. Without {@link Features#UI_ICONS} the item shows without it.
     * Needs api 5.
     *
     * @since api 5
     */
    public PageSpec item(String itemId, String label, String value, Icon icon) {
        if (icon == null) {
            return item(itemId, label, value);
        }
        return with(new Entry(Kind.ITEM, checkedItemId(itemId), label, value, false, null, null, 0, 0, 0, 0, false,
                icon, true));
    }

    /**
     * A list row "label ... option" that opens a list of {@code options} to pick one from; the pick arrives as a
     * {@link UiAction#value()} (the index). {@code options} holds 1..{@link #MAX_OPTIONS} non-null entries and
     * {@code 0 <= selected < options.length}, else {@link IllegalArgumentException}. Needs api 5.
     *
     * @since api 5
     */
    public PageSpec choice(String itemId, String label, String[] options, int selected) {
        String checked = checkedItemId(itemId);
        String[] copy = checkedOptions(itemId, options, selected);
        return with(new Entry(Kind.CHOICE, checked, label, null, false, null, copy, selected, 0, 0, 0, false, null,
                true));
    }

    /**
     * A drop-down row with the same arguments and rules as {@link #choice}; the pick arrives as a
     * {@link UiAction#value()} (the index). Counts toward {@link #MAX_INPUT_ROWS}. Needs api 5.
     *
     * @since api 5
     */
    public PageSpec dropdown(String itemId, String label, String[] options, int selected) {
        String checked = checkedItemId(itemId);
        String[] copy = checkedOptions(itemId, options, selected);
        return withInputRow(new Entry(Kind.DROPDOWN, checked, label, null, false, null, copy, selected, 0, 0, 0,
                false, null, true));
    }

    /**
     * A slider row from {@code min} to {@code max} in steps of {@code step}; each knob step arrives as a
     * {@link UiAction#value()} (the level). Needs {@code min < max}, {@code step >= 1},
     * {@code (max - min) % step == 0}, at most {@link #MAX_SLIDER_STEPS} steps and {@code value = min + k * step}
     * within the range; {@code centred} (drawn from the middle, as a balance) needs an even step count. Anything
     * else throws {@link IllegalArgumentException}. Counts toward {@link #MAX_INPUT_ROWS}. Needs api 5.
     *
     * @since api 5
     */
    public PageSpec slider(String itemId, String label, int min, int max, int step, int value, boolean centred) {
        String checked = checkedItemId(itemId);
        if (min >= max) {
            throw new IllegalArgumentException("slider '" + itemId + "': min must be below max");
        }
        if (step < 1) {
            throw new IllegalArgumentException("slider '" + itemId + "': step must be at least 1");
        }
        long span = (long) max - (long) min;
        if (span % step != 0) {
            throw new IllegalArgumentException("slider '" + itemId + "': max - min must be a multiple of step");
        }
        long steps = span / step;
        if (steps > MAX_SLIDER_STEPS) {
            throw new IllegalArgumentException("slider '" + itemId + "': at most " + MAX_SLIDER_STEPS + " steps, not "
                    + steps);
        }
        if (value < min || value > max || ((long) value - (long) min) % step != 0) {
            throw new IllegalArgumentException("slider '" + itemId + "': value " + value + " is not min + k * step"
                    + " within " + min + ".." + max);
        }
        if (centred && steps % 2 != 0) {
            throw new IllegalArgumentException("slider '" + itemId + "': a centred slider needs an even step count");
        }
        return withInputRow(new Entry(Kind.SLIDER, checked, label, null, false, null, null, value, min, max, step,
                centred, null, true));
    }

    /**
     * A progress bar row; {@code percent} is clamped to 0..100. No input. Needs api 5.
     *
     * @since api 5
     */
    public PageSpec progress(String itemId, String label, int percent) {
        String checked = checkedItemId(itemId);
        int p = percent < 0 ? 0 : percent > 100 ? 100 : percent;
        return with(new Entry(Kind.PROGRESS, checked, label, null, false, null, null, p, 0, 0, 0, false, null, true));
    }

    /**
     * This spec with the list row {@code itemId} (an ITEM, TOGGLE, LINK or CHOICE) shown greyed and not selectable,
     * or back to normal. Any other id throws {@link IllegalArgumentException}: only list rows can be disabled.
     * Needs api 5.
     *
     * @since api 5
     */
    public PageSpec enabled(String itemId, boolean enabled) {
        for (int i = 0; i < entries.size(); i++) {
            Entry e = (Entry) entries.get(i);
            if (itemId != null && itemId.equals(e.itemId())) {
                Kind k = e.kind();
                if (k != Kind.ITEM && k != Kind.TOGGLE && k != Kind.LINK && k != Kind.CHOICE) {
                    throw new IllegalArgumentException("'" + itemId + "' is a " + k
                            + ": only list rows can be disabled");
                }
                List<Entry> next = new ArrayList<Entry>(entries);
                next.set(i, e.withEnabled(enabled));
                return new PageSpec(id, title, Collections.unmodifiableList(next));
            }
        }
        throw new IllegalArgumentException("no item '" + itemId + "' on page '" + id + "'");
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

    private static String[] checkedOptions(String itemId, String[] options, int selected) {
        if (options == null || options.length < 1 || options.length > MAX_OPTIONS) {
            throw new IllegalArgumentException("'" + itemId + "' needs 1.." + MAX_OPTIONS + " options");
        }
        String[] copy = (String[]) options.clone();
        for (int i = 0; i < copy.length; i++) {
            if (copy[i] == null) {
                throw new IllegalArgumentException("'" + itemId + "': option " + i + " is null");
            }
        }
        if (selected < 0 || selected >= copy.length) {
            throw new IllegalArgumentException("'" + itemId + "': selected " + selected + " is not 0.."
                    + (copy.length - 1));
        }
        return copy;
    }

    private PageSpec withInputRow(Entry e) {
        int n = 0;
        for (int i = 0; i < entries.size(); i++) {
            Kind k = ((Entry) entries.get(i)).kind();
            if (k == Kind.DROPDOWN || k == Kind.SLIDER) {
                n++;
            }
        }
        if (n >= MAX_INPUT_ROWS) {
            throw new IllegalStateException("page '" + id + "' already has " + MAX_INPUT_ROWS
                    + " dropdown and slider rows");
        }
        return with(e);
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
