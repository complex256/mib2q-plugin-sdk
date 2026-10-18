package mibsdk.runtime;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;

/**
 * Which permissions the owner granted each plugin: {@code grants.properties}, one line per plugin,
 * {@code <pluginId>=perm1,perm2}. Immutable. A missing, unreadable or corrupt file yields no grants at
 * all (fail closed): every plugin that asks for permissions is then blocked.
 */
public final class Grants {

    private static final Grants NONE = new Grants(new HashMap());

    private final Map byPlugin; // String -> Set (unmodifiable); never mutated after construction

    private Grants(Map byPlugin) {
        this.byPlugin = byPlugin;
    }

    /** Reads {@code file}; never throws. */
    public static Grants load(File file) {
        if (file == null || !file.isFile()) {
            return NONE;
        }
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            Properties p = new Properties();
            p.load(in); // IllegalArgumentException on a malformed \\u escape
            Map map = new HashMap();
            for (Enumeration names = p.propertyNames(); names.hasMoreElements();) {
                String name = ((String) names.nextElement()).trim();
                map.put(name, parse(p.getProperty(name)));
            }
            return new Grants(map);
        } catch (Throwable t) {
            return NONE;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                    // read-only stream
                }
            }
        }
    }

    private static Set parse(String csv) {
        Set out = new HashSet();
        if (csv != null) {
            StringTokenizer st = new StringTokenizer(csv, ","); // String.split is not in CDC Foundation
            while (st.hasMoreTokens()) {
                String s = st.nextToken().trim();
                if (s.length() > 0) {
                    out.add(s);
                }
            }
        }
        return Collections.unmodifiableSet(out);
    }

    /** The permissions granted to {@code pluginId}; empty (never null) when there are none. Read-only. */
    public Set<String> granted(String pluginId) {
        Set s = pluginId == null ? null : (Set) byPlugin.get(pluginId);
        return s == null ? Collections.<String>emptySet() : s;
    }
}
