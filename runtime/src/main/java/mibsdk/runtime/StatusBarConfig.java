package mibsdk.runtime;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * SDK-owned status-bar settings: enabled, dwell, animation, the global item order and the items the user hid. One file,
 * written only when the user changes something in Plugins -> SDK / a plugin page: {@code statusbar.properties}.
 *
 * <p>This is the one source of truth (the old {@code statusline.properties} is imported once, see
 * {@link #load(File)}): the runtime orders the line and filters hidden owners from {@link #order()} and
 * {@link #hidden()}, and the bar reads dwell/animation/enabled from the same file. Order entries are plugin ids (the
 * SDK allows one status item per plugin). The user's order is global across HMI contexts; a plugin's priority is only
 * a hint for items the user has not placed yet.
 *
 * <p>Deliberately on {@code /mnt/persist}: a few hundred bytes, one write per user action. An SD card may be absent, and
 * the SDK must not lose the settings (or fail to start) without it. Writes go through {@link DurableFiles#replace}, and
 * a read recovers from a {@code .tmp} left by a power cut.
 */
public final class StatusBarConfig {

    public static final int DEFAULT_DWELL_MS = 5000;
    public static final int DEFAULT_ANIMATE_MS = 300;
    public static final int MIN_DWELL_MS = 1000;
    public static final int MAX_DWELL_MS = 60000;
    public static final int MIN_ANIMATE_MS = 0;
    public static final int MAX_ANIMATE_MS = 2000;

    /** The values the SDK page's Dwell row cycles through, in ms. */
    private static final int[] DWELL_CHOICES = {2000, 5000, 10000, 30000};
    /** The values the SDK page's Animation row cycles through, in ms (0: no animation). */
    private static final int[] ANIMATE_CHOICES = {0, 150, 300, 600};
    /** One {@code <plugin-id>.hidden=true} line per hidden owner, as the old statusline.properties used. */
    private static final String HIDDEN_SUFFIX = ".hidden";

    private final boolean enabled;
    private final int dwellMs;
    private final int animateMs;
    private final List<String> order;
    private final Set<String> hidden;

    public StatusBarConfig(boolean enabled, int dwellMs, int animateMs, List<String> order) {
        this(enabled, dwellMs, animateMs, order, Collections.<String>emptySet());
    }

    public StatusBarConfig(boolean enabled, int dwellMs, int animateMs, List<String> order, Set<String> hidden) {
        this.enabled = enabled;
        this.dwellMs = clamp(dwellMs, MIN_DWELL_MS, MAX_DWELL_MS);
        this.animateMs = clamp(animateMs, MIN_ANIMATE_MS, MAX_ANIMATE_MS);
        this.order = Collections.unmodifiableList(clean(order));
        this.hidden = Collections.unmodifiableSet(cleanIds(hidden));
    }

    public static StatusBarConfig defaults() {
        return new StatusBarConfig(true, DEFAULT_DWELL_MS, DEFAULT_ANIMATE_MS, Collections.<String>emptyList(),
                Collections.<String>emptySet());
    }

    /**
     * Missing or unreadable file: the defaults. Invalid values are clamped, not fatal. A missing target is read from
     * its {@code .tmp} (a power cut between the write and the rename). The file is also the legacy
     * {@code statusline.properties} format: {@code order=...} and {@code <id>.hidden=true} mean the same here.
     */
    public static StatusBarConfig load(File file) {
        if (file == null) {
            return defaults();
        }
        File f = file.isFile() ? file : DurableFiles.tmpFile(file); // died between delete and rename
        if (!f.isFile()) {
            return defaults();
        }
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(f)) {
            p.load(in);
        } catch (Throwable t) { // IOException, or Properties' IllegalArgumentException for a malformed escape
            return defaults();
        }
        boolean enabled = !"false".equalsIgnoreCase(trim(p.getProperty("enabled", "true")));
        int dwell = intValue(p.getProperty("dwell.ms"), DEFAULT_DWELL_MS);
        int animate = intValue(p.getProperty("animate.ms"), DEFAULT_ANIMATE_MS);
        return new StatusBarConfig(enabled, dwell, animate, parseOrder(p.getProperty("order", "")), parseHidden(p));
    }

    /** Writes the file through {@link DurableFiles#replace}, so a power cut cannot leave a half line. */
    public void save(File file) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("file");
        }
        StringBuilder s = new StringBuilder(160);
        s.append("# mibsdk status bar; written by the SDK, not by plugins\n");
        s.append("enabled=").append(enabled).append('\n');
        s.append("dwell.ms=").append(dwellMs).append('\n');
        s.append("animate.ms=").append(animateMs).append('\n');
        if (!order.isEmpty()) {
            s.append("order=").append(join(order)).append('\n');
        }
        List<String> ids = new ArrayList<String>(hidden);
        Collections.sort(ids);
        for (int i = 0; i < ids.size(); i++) {
            s.append(ids.get(i)).append(HIDDEN_SUFFIX).append("=true\n");
        }
        DurableFiles.replace(file, s.toString().getBytes("ISO-8859-1")); // ASCII only: ids and numbers
    }

    public boolean enabled() {
        return enabled;
    }

    public int dwellMs() {
        return dwellMs;
    }

    public int animateMs() {
        return animateMs;
    }

    /** The global user order, as plugin ids. */
    public List<String> order() {
        return order;
    }

    /** The plugins whose status item the user hid. */
    public Set<String> hidden() {
        return hidden;
    }

    /** Index of {@code key} in the user order, or {@link Integer#MAX_VALUE} when the user has not placed it. */
    public int rank(String key) {
        int i = order.indexOf(key);
        return i < 0 ? Integer.MAX_VALUE : i;
    }

    public StatusBarConfig withEnabled(boolean value) {
        return value == enabled ? this : new StatusBarConfig(value, dwellMs, animateMs, order, hidden);
    }

    public StatusBarConfig withDwell(int value) {
        return new StatusBarConfig(enabled, value, animateMs, order, hidden);
    }

    public StatusBarConfig withAnimate(int value) {
        return new StatusBarConfig(enabled, dwellMs, value, order, hidden);
    }

    public StatusBarConfig withOrder(List<String> value) {
        return new StatusBarConfig(enabled, dwellMs, animateMs, value, hidden);
    }

    public StatusBarConfig withHidden(Set<String> value) {
        return new StatusBarConfig(enabled, dwellMs, animateMs, order, value);
    }

    /** Hides or shows {@code id}'s item; the same config when nothing changes or the id is invalid. */
    public StatusBarConfig setHidden(String id, boolean hide) {
        if (id == null || !ManifestParser.isValidId(id)) {
            return this;
        }
        Set<String> h = new HashSet<String>(hidden);
        if (hide) {
            h.add(id);
        } else {
            h.remove(id);
        }
        return h.equals(hidden) ? this : withHidden(h);
    }

    /**
     * Moves {@code id} one place earlier ({@code delta < 0}) or later among {@code eligible} (the plugins that can show
     * an item): it swaps with the nearest eligible neighbour in the effective order of {@code eligible} plus the saved
     * ids, and the whole order is kept, so saved ids that are not eligible now keep their place. At an end, or when
     * {@code id} is not eligible, the same config.
     */
    public StatusBarConfig move(String id, int delta, Collection<String> eligible) {
        if (id == null || !ManifestParser.isValidId(id) || delta == 0 || eligible == null || !eligible.contains(id)) {
            return this;
        }
        Set<String> all = new HashSet<String>(eligible);
        all.addAll(order);
        List<String> l = StatusLine.order(order, all);
        int from = l.indexOf(id);
        int step = delta < 0 ? -1 : 1;
        int to = from + step;
        while (to >= 0 && to < l.size() && !eligible.contains(l.get(to))) {
            to += step;
        }
        if (from < 0 || to < 0 || to >= l.size()) {
            return this;
        }
        l.set(from, l.get(to));
        l.set(to, id);
        return withOrder(l);
    }

    /** The Dwell row's next value: the first choice above {@code current}, else the first choice. */
    public static int nextDwell(int current) {
        return next(DWELL_CHOICES, current);
    }

    /** The Animation row's next value: the first choice above {@code current}, else the first choice. */
    public static int nextAnimate(int current) {
        return next(ANIMATE_CHOICES, current);
    }

    /** {@code 5 s} for a whole number of seconds, else {@code 500 ms}. */
    public static String dwellLabel(int ms) {
        return ms > 0 && ms % 1000 == 0 ? (ms / 1000) + " s" : ms + " ms";
    }

    /** {@code off} for 0, else {@code 300 ms}. */
    public static String animateLabel(int ms) {
        return ms <= 0 ? "off" : ms + " ms";
    }

    private static int next(int[] choices, int current) {
        for (int i = 0; i < choices.length; i++) {
            if (choices[i] > current) {
                return choices[i];
            }
        }
        return choices[0];
    }

    private static List<String> parseOrder(String value) {
        List<String> out = new ArrayList<String>();
        if (value == null) {
            return out;
        }
        int at = 0;
        while (at <= value.length()) {
            int comma = value.indexOf(',', at);
            String part = comma < 0 ? value.substring(at) : value.substring(at, comma);
            String s = trim(part);
            if (s.length() > 0 && ManifestParser.isValidId(s) && !out.contains(s)) {
                out.add(s);
            }
            if (comma < 0) {
                break;
            }
            at = comma + 1;
        }
        return out;
    }

    private static Set<String> parseHidden(Properties p) {
        Set<String> out = new HashSet<String>();
        for (Enumeration names = p.propertyNames(); names.hasMoreElements();) {
            String key = ((String) names.nextElement()).trim();
            if (!key.endsWith(HIDDEN_SUFFIX) || !"true".equals(p.getProperty(key).trim())) {
                continue;
            }
            String id = key.substring(0, key.length() - HIDDEN_SUFFIX.length());
            if (ManifestParser.isValidId(id)) {
                out.add(id);
            }
        }
        return out;
    }

    private static List<String> clean(List<String> in) {
        List<String> out = new ArrayList<String>();
        if (in != null) {
            for (String s : in) {
                String t = trim(s);
                if (t.length() > 0 && ManifestParser.isValidId(t) && !out.contains(t)) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    private static Set<String> cleanIds(Set<String> in) {
        Set<String> out = new HashSet<String>();
        if (in != null) {
            for (String s : in) {
                String t = trim(s);
                if (t.length() > 0 && ManifestParser.isValidId(t)) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    private static String join(List<String> list) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append(list.get(i));
        }
        return b.toString();
    }

    private static int intValue(String s, int fallback) {
        try {
            return Integer.parseInt(trim(s));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : v > max ? max : v;
    }
}
