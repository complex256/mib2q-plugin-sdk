package mibsdk.platform;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import mibsdk.api.Ui;

/**
 * The installed icons of one package: {@code <dir>/index.properties}, written by mibpkg, with lines
 * {@code <name>=<sha16>,<w>,<h>} for files {@code <dir>/<sha16>.png}. Reading never throws: a missing, unreadable or
 * malformed file is an empty index, and a bad line (a name outside {@code [a-z0-9-]{1,32}}, a key that is not 16
 * lowercase hex characters, a size outside 1..72) is left out. No java.util.regex: the car has none.
 */
public final class IconIndex {

    public static final String FILE_NAME = "index.properties";
    private static final int MAX_PX = Ui.ROW_ICON_MAX_PX;
    private static final int MAX_NAME = 32;
    private static final int KEY_LENGTH = 16;

    private final Map<String, UiIcon> icons;

    private IconIndex(Map<String, UiIcon> icons) {
        this.icons = icons;
    }

    /**
     * Reads {@code <dir>/index.properties}.
     *
     * @param owner the plugin id, or {@code mibsdk} for the built-in set; it goes into every {@link UiIcon}
     */
    public static IconIndex read(String owner, File dir) {
        Map<String, UiIcon> out = new HashMap<String, UiIcon>();
        if (dir == null) {
            return new IconIndex(out);
        }
        Properties p = new Properties();
        InputStream in = null;
        try {
            File f = new File(dir, FILE_NAME);
            if (!f.isFile()) {
                return new IconIndex(out);
            }
            in = new FileInputStream(f);
            p.load(in);
        } catch (Throwable t) { // unreadable, or a malformed escape: no index is better than half of one
            return new IconIndex(out);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                    // nothing to do
                }
            }
        }
        for (Enumeration e = p.propertyNames(); e.hasMoreElements();) {
            String name = (String) e.nextElement();
            UiIcon icon = entry(owner, dir, name, p.getProperty(name));
            if (icon != null) {
                out.put(name, icon);
            }
        }
        return new IconIndex(out);
    }

    /** The installed icon {@code name}; null if it is not in the index. */
    public UiIcon get(String name) {
        return name == null ? null : (UiIcon) icons.get(name);
    }

    private static UiIcon entry(String owner, File dir, String name, String value) {
        if (!isName(name) || value == null) {
            return null;
        }
        int c1 = value.indexOf(',');
        int c2 = c1 < 0 ? -1 : value.indexOf(',', c1 + 1);
        if (c2 < 0 || value.indexOf(',', c2 + 1) >= 0) {
            return null;
        }
        String key = value.substring(0, c1);
        int w = size(value.substring(c1 + 1, c2));
        int h = size(value.substring(c2 + 1));
        if (!isKey(key) || w < 0 || h < 0) {
            return null;
        }
        return UiIcon.file(owner, new File(dir, key + ".png").getPath(), key, w, h);
    }

    /** Whether {@code s} is a valid icon name, {@code [a-z0-9-]{1,32}}; the manifest parser checks the same. */
    public static boolean isName(String s) {
        if (s == null || s.length() < 1 || s.length() > MAX_NAME) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-')) {
                return false;
            }
        }
        return true;
    }

    private static boolean isKey(String s) {
        if (s.length() != KEY_LENGTH) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= 'a' && c <= 'f') || (c >= '0' && c <= '9'))) {
                return false;
            }
        }
        return true;
    }

    /** 1..72 in plain decimal digits; -1 otherwise. */
    private static int size(String s) {
        if (s.length() < 1 || s.length() > 2) {
            return -1;
        }
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            n = n * 10 + (c - '0');
        }
        return n >= 1 && n <= MAX_PX ? n : -1;
    }
}
