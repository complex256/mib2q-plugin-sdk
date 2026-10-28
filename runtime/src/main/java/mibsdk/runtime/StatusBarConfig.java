package mibsdk.runtime;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * SDK-owned status-bar settings. One file, written only when the user changes something in
 * Plugins -> Status bar: enabled, dwell, animation and the global item order.
 *
 * <p>The order entries are stable {@code <plugin-id>/<item-id>} keys. The user's order is global across HMI
 * contexts; a plugin's priority is only a hint for items the user has not placed yet. The list is kept in the
 * file in that order; unknown keys are harmless (a stale plugin entry is ignored by the renderer).
 *
 * <p>Deliberately on {@code /mnt/persist}: a few hundred bytes, one write per user action. An SD card may be
 * absent, and the SDK must not lose the settings (or fail to start) without it.
 */
public final class StatusBarConfig {

    public static final int DEFAULT_DWELL_MS = 5000;
    public static final int DEFAULT_ANIMATE_MS = 300;
    public static final int MIN_DWELL_MS = 1000;
    public static final int MAX_DWELL_MS = 60000;
    public static final int MIN_ANIMATE_MS = 0;
    public static final int MAX_ANIMATE_MS = 2000;

    private final boolean enabled;
    private final int dwellMs;
    private final int animateMs;
    private final List<String> order;

    public StatusBarConfig(boolean enabled, int dwellMs, int animateMs, List<String> order) {
        this.enabled = enabled;
        this.dwellMs = clamp(dwellMs, MIN_DWELL_MS, MAX_DWELL_MS);
        this.animateMs = clamp(animateMs, MIN_ANIMATE_MS, MAX_ANIMATE_MS);
        this.order = Collections.unmodifiableList(clean(order));
    }

    public static StatusBarConfig defaults() {
        return new StatusBarConfig(true, DEFAULT_DWELL_MS, DEFAULT_ANIMATE_MS, Collections.<String>emptyList());
    }

    /** Missing or unreadable file: the defaults. Invalid values are clamped, not fatal. */
    public static StatusBarConfig load(File file) {
        if (file == null || !file.isFile()) {
            return defaults();
        }
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(file)) {
            p.load(in);
        } catch (IOException e) {
            return defaults();
        }
        boolean enabled = !"false".equalsIgnoreCase(trim(p.getProperty("enabled", "true")));
        int dwell = intValue(p.getProperty("dwell.ms"), DEFAULT_DWELL_MS);
        int animate = intValue(p.getProperty("animate.ms"), DEFAULT_ANIMATE_MS);
        return new StatusBarConfig(enabled, dwell, animate, parseOrder(p.getProperty("order", "")));
    }

    /** Writes the file through a temp name + rename, so a power cut cannot leave a half line. */
    public void save(File file) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("file");
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("cannot create " + parent);
        }
        File tmp = new File(file.getPath() + ".tmp");
        StringBuilder s = new StringBuilder(160);
        s.append("# mibsdk status bar; written by the SDK, not by plugins\n");
        s.append("enabled=").append(enabled).append('\n');
        s.append("dwell.ms=").append(dwellMs).append('\n');
        s.append("animate.ms=").append(animateMs).append('\n');
        s.append("order=").append(join(order)).append('\n');
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(s.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (file.exists() && !file.delete()) {
            throw new IOException("cannot replace " + file);
        }
        if (!tmp.renameTo(file)) {
            throw new IOException("cannot rename " + tmp + " to " + file);
        }
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

    /** The global user order, as stable {@code plugin/item} keys. */
    public List<String> order() {
        return order;
    }

    /** Index of {@code key} in the user order, or {@link Integer#MAX_VALUE} when the user has not placed it. */
    public int rank(String key) {
        int i = order.indexOf(key);
        return i < 0 ? Integer.MAX_VALUE : i;
    }

    private static List<String> parseOrder(String value) {
        List<String> out = new ArrayList<String>();
        for (String part : value.split(",")) {
            String s = trim(part);
            if (s.length() > 0 && !out.contains(s)) {
                out.add(s);
            }
        }
        return out;
    }

    private static List<String> clean(List<String> in) {
        List<String> out = new ArrayList<String>();
        if (in != null) {
            for (String s : in) {
                String t = trim(s);
                if (t.length() > 0 && !out.contains(t)) {
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
