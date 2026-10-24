package mibsdk.runtime;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * The user's status-line choices from the Plugins menu: {@code <persist>/statusline.properties}, an
 * {@code order=<id>,<id>,...} line (the saved order, see {@link StatusLine#order}) and one {@code <id>.hidden=true}
 * line per plugin whose item is hidden, written with {@link DurableFiles#replace}. A sibling of
 * {@code plugins.properties} rather than more keys in it: {@link UserToggles} and the host tools rewrite that file
 * knowing only {@code <id>.off}.
 *
 * <p>Same rules as {@link UserToggles}: a missing target is recovered from {@link DurableFiles#tmpFile}; an
 * unreadable or corrupt file reads as empty (every item shown, plugin-id order); invalid ids are skipped; ids of
 * plugins that are not installed are kept. Each change reads, modifies and replaces the file in one step. A change
 * that could not be written is kept in memory on top of the file for this run, and goes out with the next write that
 * succeeds. The control thread is the only caller.
 */
final class StatusPrefs {

    private static final String SOURCE = "mibsdk";
    private static final String ORDER_KEY = "order";
    private static final String HIDDEN_SUFFIX = ".hidden";

    private final File file;
    private final RingLog log;
    // ---- guarded by this ----
    private List<String> order; // as last read plus unsaved; null until read
    private Set<String> hidden; // as last read plus unsaved; null until read
    private final Map<String, Boolean> unsavedHidden = new HashMap<String, Boolean>(); // write failed
    private List<String> unsavedOrder; // write failed; null if none

    StatusPrefs(File persistFile, RingLog log) {
        this.file = persistFile;
        this.log = log;
    }

    /** The saved order (may name plugins that are gone), as of the last read. */
    synchronized List<String> order() {
        load();
        return new ArrayList<String>(order);
    }

    synchronized Set<String> hiddenIds() {
        load();
        return new HashSet<String>(hidden);
    }

    synchronized boolean isHidden(String id) {
        load();
        return id != null && hidden.contains(id);
    }

    /** Re-reads the file. */
    synchronized void refresh() {
        read();
    }

    /** Hides or shows {@code id}'s item. True if the file now holds it; false if invalid or not written (logged). */
    synchronized boolean setHidden(String id, boolean value) {
        if (id == null || !ManifestParser.isValidId(id)) {
            return false;
        }
        read();
        if (hidden.contains(id) == value && !unsavedHidden.containsKey(id)) {
            return true;
        }
        if (value) {
            hidden.add(id);
        } else {
            hidden.remove(id);
        }
        if (save()) {
            return true;
        }
        unsavedHidden.put(id, value ? Boolean.TRUE : Boolean.FALSE);
        return false;
    }

    /**
     * Moves {@code id} one place earlier ({@code delta < 0}) or later among {@code eligible} (the plugins that can
     * show an item): it swaps with the nearest eligible neighbour in the effective order of {@code eligible} plus the
     * saved ids, and that whole order is saved, so saved ids that are not eligible now keep their place. At an end,
     * or when {@code id} is not eligible, nothing changes (true). False if invalid or not written (logged).
     */
    synchronized boolean move(String id, int delta, Collection<String> eligible) {
        if (id == null || !ManifestParser.isValidId(id)) {
            return false;
        }
        read();
        if (delta == 0 || eligible == null || !eligible.contains(id)) {
            return true;
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
        if (to < 0 || to >= l.size()) {
            return true;
        }
        l.set(from, l.get(to));
        l.set(to, id);
        order = l;
        if (save()) {
            return true;
        }
        unsavedOrder = new ArrayList<String>(l);
        return false;
    }

    private void load() {
        if (order == null) {
            read();
        }
    }

    /** Writes {@link #order} and {@link #hidden}; on success nothing is unsaved any more. */
    private boolean save() {
        StringBuffer text = new StringBuffer();
        if (!order.isEmpty()) {
            text.append(ORDER_KEY).append('=');
            for (int i = 0; i < order.size(); i++) {
                text.append(i > 0 ? "," : "").append(order.get(i));
            }
            text.append('\n');
        }
        List<String> ids = new ArrayList<String>(hidden);
        Collections.sort(ids);
        for (int i = 0; i < ids.size(); i++) {
            text.append(ids.get(i)).append(HIDDEN_SUFFIX).append("=true\n");
        }
        try {
            if (!DurableFiles.replace(file, text.toString().getBytes("ISO-8859-1"))) {
                log.append("WARN", SOURCE, file.getName()
                        + " written, but its final sync failed: it may not survive a power cut");
            }
            unsavedHidden.clear(); // every unsaved choice was in what was just written
            unsavedOrder = null;
            return true;
        } catch (Throwable t) {
            log.append("ERROR", SOURCE, "cannot write " + file.getName() + ": " + SdkRuntime.describe(t)
                    + "; the choice holds until the next start");
            return false;
        }
    }

    /** The file, with the unsaved choices applied on top. */
    private void read() {
        order = new ArrayList<String>();
        hidden = new HashSet<String>();
        readFile();
        if (unsavedOrder != null) {
            order = new ArrayList<String>(unsavedOrder);
        }
        for (Iterator it = unsavedHidden.entrySet().iterator(); it.hasNext();) {
            Map.Entry e = (Map.Entry) it.next();
            String id = (String) e.getKey();
            if (((Boolean) e.getValue()).booleanValue()) {
                hidden.add(id);
            } else {
                hidden.remove(id);
            }
        }
    }

    private void readFile() {
        File f = file.isFile() ? file : DurableFiles.tmpFile(file); // died between delete and rename
        if (!f.isFile()) {
            return;
        }
        Properties p = new Properties();
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            p.load(in);
        } catch (Throwable t) {
            log.append("WARN", SOURCE, f.getName() + " unreadable, every status item is shown: "
                    + SdkRuntime.describe(t));
            return;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                    // read-only stream
                }
            }
        }
        String saved = p.getProperty(ORDER_KEY);
        if (saved != null) {
            String rest = saved;
            while (rest != null) {
                int comma = rest.indexOf(',');
                String id = (comma < 0 ? rest : rest.substring(0, comma)).trim();
                rest = comma < 0 ? null : rest.substring(comma + 1);
                if (ManifestParser.isValidId(id) && !order.contains(id)) {
                    order.add(id);
                }
            }
        }
        for (Enumeration names = p.propertyNames(); names.hasMoreElements();) {
            String key = ((String) names.nextElement()).trim();
            if (!key.endsWith(HIDDEN_SUFFIX) || !"true".equals(p.getProperty(key).trim())) {
                continue;
            }
            String id = key.substring(0, key.length() - HIDDEN_SUFFIX.length());
            if (ManifestParser.isValidId(id)) {
                hidden.add(id);
            }
        }
    }
}
