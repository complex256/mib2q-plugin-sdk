package mibsdk.platform;

/**
 * One entry of a {@link UiPage}, already cleaned and truncated by the runtime. Immutable: the {@code with*} methods
 * return copies.
 */
public final class UiEntry {

    public static final int ITEM = 0;
    public static final int TOGGLE = 1;
    public static final int INFO = 2;
    public static final int LINK = 3;
    /** A list row that opens its derived choice page ({@link #targetPageKey}). */
    public static final int CHOICE = 4;
    public static final int DROPDOWN = 5;
    public static final int SLIDER = 6;
    public static final int PROGRESS = 7;
    /** One option of a derived choice page (key {@code "0".."n-1"}, {@link #on} for the selected one). */
    public static final int RADIO = 8;

    private final int kind;
    private final String key;
    private final String label;
    private final String value;
    private final boolean on;
    private final String targetPageKey;
    private final int number;
    private final String[] options;
    private final int min;
    private final int max;
    private final int step;
    private final boolean centred;
    private final boolean enabled;
    private final UiIcon icon;

    public UiEntry(int kind, String key, String label, String value, boolean on, String targetPageKey) {
        this(kind, key, label, value, on, targetPageKey, 0, null, 0, 0, 0, false, true, null);
    }

    private UiEntry(int kind, String key, String label, String value, boolean on, String targetPageKey, int number,
            String[] options, int min, int max, int step, boolean centred, boolean enabled, UiIcon icon) {
        this.kind = kind;
        this.key = key;
        this.label = label;
        this.value = value;
        this.on = on;
        this.targetPageKey = targetPageKey;
        this.number = number;
        this.options = options;
        this.min = min;
        this.max = max;
        this.step = step;
        this.centred = centred;
        this.enabled = enabled;
        this.icon = icon;
    }

    public UiEntry withNumber(int n) {
        return new UiEntry(kind, key, label, value, on, targetPageKey, n, options, min, max, step, centred, enabled,
                icon);
    }

    /** {@code o} is copied; null for none. */
    public UiEntry withOptions(String[] o) {
        return new UiEntry(kind, key, label, value, on, targetPageKey, number, o == null ? null : (String[]) o.clone(),
                min, max, step, centred, enabled, icon);
    }

    public UiEntry withRange(int lo, int hi, int st, boolean c) {
        return new UiEntry(kind, key, label, value, on, targetPageKey, number, options, lo, hi, st, c, enabled, icon);
    }

    public UiEntry withEnabled(boolean e) {
        return new UiEntry(kind, key, label, value, on, targetPageKey, number, options, min, max, step, centred, e,
                icon);
    }

    public UiEntry withIcon(UiIcon i) {
        return new UiEntry(kind, key, label, value, on, targetPageKey, number, options, min, max, step, centred,
                enabled, i);
    }

    public int kind() {
        return kind;
    }

    public String key() {
        return key;
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

    /** For {@link #LINK}: the page it opens; for {@link #CHOICE}: its derived choice page; otherwise null. */
    public String targetPageKey() {
        return targetPageKey;
    }

    /** The selected index (CHOICE, DROPDOWN), the value (SLIDER) or the percent (PROGRESS); 0 otherwise. */
    public int number() {
        return number;
    }

    /** A copy of a CHOICE's or DROPDOWN's options (cleaned); null otherwise. */
    public String[] options() {
        return options == null ? null : (String[]) options.clone();
    }

    /** The number of options; 0 for none. */
    public int optionCount() {
        return options == null ? 0 : options.length;
    }

    /** Option {@code i}, or null when out of range or there are none. */
    public String option(int i) {
        return options == null || i < 0 || i >= options.length ? null : options[i];
    }

    /** A SLIDER's lowest value; 0 otherwise. */
    public int min() {
        return min;
    }

    /** A SLIDER's highest value; 0 otherwise. */
    public int max() {
        return max;
    }

    /** A SLIDER's step; 0 otherwise. */
    public int step() {
        return step;
    }

    /** True for a SLIDER drawn from its centre. */
    public boolean centred() {
        return centred;
    }

    /** False for a list row the plugin turned off: shown greyed, its input is dropped. */
    public boolean enabled() {
        return enabled;
    }

    /** An ITEM's icon, or null. */
    public UiIcon icon() {
        return icon;
    }
}
