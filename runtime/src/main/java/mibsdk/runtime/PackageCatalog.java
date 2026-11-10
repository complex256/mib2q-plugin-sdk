package mibsdk.runtime;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What mibpkg says about packages, read from the snapshot it writes ({@code /tmp/mibpkg.catalog}, see
 * mibpkg's {@code mibsdk::pkgcat}). The SDK's Plugins menu lists Installed, Updates and Available from
 * here; it never talks to mibpkg itself except to ask for a check or an install.
 *
 * <p>Properties-like text: {@code key=value} per line, split on the first {@code =}, {@code \\} and
 * {@code \n} escaped in values. A missing or unreadable file is {@link #EMPTY}: the pages then say so
 * instead of failing. Immutable; parsed on the control thread, no I/O after {@link #load}.
 */
final class PackageCatalog {

    static final PackageCatalog EMPTY = new PackageCatalog(0, Collections.<Pkg>emptyList(), null);

    /** One package as the snapshot describes it. */
    static final class Pkg {
        final String name;
        final String desc;
        final String kind; // "plugin", "sdk" or "plain"
        final String installed; // null when not installed
        final String blocked; // comma list, "" for none
        final String update; // null when no newer compatible version
        final String updateFrom;
        final String avail; // null when no collection offers it
        final String availFrom;
        final String availName;
        final String minSdk;
        final String perms; // comma list of the offered/installed version's permissions
        final String granted; // comma list, installed plugins
        final boolean compatible;
        final Map<String, String> permText;

        Pkg(Map<String, String> f, Map<String, String> text) {
            this.name = f.get("name");
            this.desc = value(f, "desc");
            this.kind = value(f, "kind");
            this.installed = f.get("installed");
            this.blocked = value(f, "blocked");
            this.update = f.get("update");
            this.updateFrom = f.get("updatefrom");
            this.avail = f.get("avail");
            this.availFrom = f.get("availfrom");
            this.availName = value(f, "availname");
            this.minSdk = f.get("minsdk");
            this.perms = value(f, "perms");
            this.granted = value(f, "granted");
            this.compatible = !"false".equals(f.get("compatible"));
            this.permText = Collections.unmodifiableMap(text);
        }

        private static String value(Map<String, String> f, String k) {
            String v = f.get(k);
            return v == null ? "" : v;
        }

        boolean installedPkg() {
            return installed != null;
        }

        boolean hasUpdate() {
            return installed != null && update != null;
        }

        /** The permissions as a list ({@code net,ui.slot.statusbar}); empty for none. */
        List<String> permList() {
            return split(perms);
        }
    }

    final long updated;
    final List<Pkg> packages;
    /** Why the snapshot could not be read (null: it was read, or there was no file). */
    final String error;

    private PackageCatalog(long updated, List<Pkg> packages, String error) {
        this.updated = updated;
        this.packages = Collections.unmodifiableList(packages);
        this.error = error;
    }

    /** The package named {@code name}, or null. */
    Pkg find(String name) {
        for (int i = 0; i < packages.size(); i++) {
            if (packages.get(i).name.equals(name)) {
                return packages.get(i);
            }
        }
        return null;
    }

    /** Packages with a newer compatible version, in name order. */
    List<Pkg> updates() {
        List<Pkg> out = new ArrayList<Pkg>();
        for (int i = 0; i < packages.size(); i++) {
            if (packages.get(i).hasUpdate()) {
                out.add(packages.get(i));
            }
        }
        return out;
    }

    /** Packages a collection offers that are not installed, in name order. */
    List<Pkg> available() {
        List<Pkg> out = new ArrayList<Pkg>();
        for (int i = 0; i < packages.size(); i++) {
            Pkg p = packages.get(i);
            if (!p.installedPkg() && p.avail != null) {
                out.add(p);
            }
        }
        return out;
    }

    /**
     * Reads {@code file}; a missing file is {@link #EMPTY}, a malformed one keeps what parsed and records
     * {@link #error}. Never throws.
     */
    static PackageCatalog load(File file) {
        if (file == null || !file.isFile()) {
            return EMPTY;
        }
        Map<String, Map<String, String>> fields = new HashMap<String, Map<String, String>>();
        Map<String, Map<String, String>> texts = new HashMap<String, Map<String, String>>();
        Map<String, String> top = new HashMap<String, String>();
        InputStream in = null;
        try {
            in = new BufferedInputStream(new FileInputStream(file));
            StringBuilder line = new StringBuilder(120);
            int c;
            while ((c = in.read()) != -1) {
                if (c != '\n') {
                    if (line.length() < 4096) {
                        line.append((char) c);
                    }
                    continue;
                }
                parseLine(line.toString(), top, fields, texts);
                line.setLength(0);
            }
            parseLine(line.toString(), top, fields, texts);
        } catch (Throwable t) {
            return new PackageCatalog(0, Collections.<Pkg>emptyList(), t.toString());
        } finally {
            close(in);
        }
        List<Pkg> list = new ArrayList<Pkg>(fields.size());
        for (Map.Entry<String, Map<String, String>> e : fields.entrySet()) {
            Map<String, String> t = texts.get(e.getKey());
            list.add(new Pkg(withName(e.getKey(), e.getValue()), t == null ? Collections.<String, String>emptyMap() : t));
        }
        Collections.sort(list, new java.util.Comparator<Pkg>() {
            public int compare(Pkg a, Pkg b) {
                return a.name.compareTo(b.name);
            }
        });
        long updated = 0;
        try {
            updated = Long.parseLong(top.containsKey("updated") ? top.get("updated") : "0");
        } catch (NumberFormatException ignored) {
            // 0: unknown
        }
        return new PackageCatalog(updated, list, null);
    }

    /** {@code pkg.<name>.<field>} and {@code pkg.<name>.perm.<id>}. */
    private static void parseLine(String line, Map<String, String> top,
            Map<String, Map<String, String>> fields, Map<String, Map<String, String>> texts) {
        if (line.length() == 0 || line.charAt(0) == '#') {
            return;
        }
        int eq = line.indexOf('=');
        if (eq <= 0) {
            return;
        }
        String key = line.substring(0, eq);
        String val = unescape(line.substring(eq + 1));
        if (!key.startsWith("pkg.")) {
            top.put(key, val);
            return;
        }
        String rest = key.substring(4);
        int dot = rest.indexOf('.');
        if (dot <= 0 || dot == rest.length() - 1) {
            return;
        }
        String name = rest.substring(0, dot);
        String field = rest.substring(dot + 1);
        Map<String, String> f = fields.get(name);
        if (f == null) {
            f = new HashMap<String, String>();
            fields.put(name, f);
        }
        if (field.startsWith("perm.")) {
            Map<String, String> t = texts.get(name);
            if (t == null) {
                t = new HashMap<String, String>();
                texts.put(name, t);
            }
            t.put(field.substring(5), val);
        } else {
            f.put(field, val);
        }
    }

    private static Map<String, String> withName(String name, Map<String, String> f) {
        f.put("name", name);
        return f;
    }

    static List<String> split(String csv) {
        List<String> out = new ArrayList<String>();
        if (csv == null) {
            return out;
        }
        int at = 0;
        while (at <= csv.length()) {
            int comma = csv.indexOf(',', at);
            String s = comma < 0 ? csv.substring(at) : csv.substring(at, comma);
            s = s.trim();
            if (s.length() > 0) {
                out.add(s);
            }
            if (comma < 0) {
                break;
            }
            at = comma + 1;
        }
        return out;
    }

    private static String unescape(String s) {
        if (s.indexOf('\\') < 0) {
            return s;
        }
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                b.append(n == 'n' ? '\n' : n);
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    private static void close(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (Throwable ignored) {
                // read-only
            }
        }
    }
}
