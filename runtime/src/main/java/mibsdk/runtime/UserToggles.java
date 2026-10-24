package mibsdk.runtime;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
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
 * The user's on/off choices from the Plugins menu: {@code <persist>/plugins.properties}, one {@code <id>.off=true}
 * line per plugin switched off, written with {@link DurableFiles#replace}. A missing target is recovered from
 * {@link DurableFiles#tmpFile}; an unreadable or corrupt file reads as empty (every plugin on). Keys of plugins that
 * are not installed are kept but never asked about.
 *
 * <p>Another writer ({@code mibsdk-dev remove}) may edit the file during the run, so it is never cached across a
 * change: {@link #refresh} re-reads it (the runtime calls it at load and reload), and {@link #setOff} reads, modifies
 * and replaces it in one step. A choice that could not be written is kept in memory on top of the file for this
 * run. The control thread is the only caller.
 */
final class UserToggles {

    private static final String SOURCE = "mibsdk";
    private static final String SUFFIX = ".off";

    private final File file;
    private final RingLog log;
    private List<String> off; // ids, sorted, as last read plus unsaved; null until read; guarded by this
    private final Map<String, Boolean> unsaved = new HashMap<String, Boolean>(); // write failed; guarded by this

    UserToggles(File persistFile, RingLog log) {
        this.file = persistFile;
        this.log = log;
    }

    /** As of the last {@link #refresh} or {@link #setOff} (read on first use). */
    synchronized boolean isOff(String id) {
        return id != null && loaded().contains(id);
    }

    /** Every id switched off, as of the last read. */
    synchronized Set<String> offIds() {
        return new HashSet<String>(loaded());
    }

    /** Re-reads the file (another writer may have changed it). */
    synchronized void refresh() {
        off = read();
    }

    /**
     * Records the choice and writes the file. True if the file now holds it; false (logged) if it could not be
     * written: the choice then holds for this run only.
     */
    synchronized boolean setOff(String id, boolean value) {
        if (!ManifestParser.isValidId(id)) {
            return false;
        }
        List<String> ids = read(); // read-modify-replace: keep another writer's edits
        off = ids;
        if (ids.contains(id) == value && !unsaved.containsKey(id)) {
            return true;
        }
        if (value && !ids.contains(id)) {
            ids.add(id);
            Collections.sort(ids);
        } else if (!value) {
            ids.remove(id);
        }
        StringBuffer text = new StringBuffer();
        for (int i = 0; i < ids.size(); i++) {
            text.append(ids.get(i)).append(SUFFIX).append("=true\n");
        }
        try {
            if (!DurableFiles.replace(file, text.toString().getBytes("ISO-8859-1"))) {
                log.append("WARN", SOURCE, file.getName()
                        + " written, but its final sync failed: it may not survive a power cut");
            }
            unsaved.clear(); // every unsaved choice was in what was just written
            return true;
        } catch (Throwable t) {
            unsaved.put(id, value ? Boolean.TRUE : Boolean.FALSE);
            log.append("ERROR", SOURCE, "cannot write " + file.getName() + ": " + SdkRuntime.describe(t)
                    + "; the choice holds until the next start");
            return false;
        }
    }

    private List<String> loaded() {
        if (off == null) {
            off = read();
        }
        return off;
    }

    /** The file's ids with the unsaved choices applied on top. */
    private List<String> read() {
        List<String> ids = readFile();
        for (Iterator it = unsaved.entrySet().iterator(); it.hasNext();) {
            Map.Entry e = (Map.Entry) it.next();
            String id = (String) e.getKey();
            ids.remove(id);
            if (((Boolean) e.getValue()).booleanValue()) {
                ids.add(id);
            }
        }
        Collections.sort(ids);
        return ids;
    }

    private List<String> readFile() {
        List<String> ids = new ArrayList<String>();
        File f = file.isFile() ? file : DurableFiles.tmpFile(file); // died between delete and rename
        if (!f.isFile()) {
            return ids;
        }
        Properties p = new Properties();
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            p.load(in);
        } catch (Throwable t) {
            log.append("WARN", SOURCE, f.getName() + " unreadable, every plugin is on: " + SdkRuntime.describe(t));
            return ids;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                    // read-only stream
                }
            }
        }
        for (Enumeration names = p.propertyNames(); names.hasMoreElements();) {
            String key = ((String) names.nextElement()).trim();
            if (!key.endsWith(SUFFIX) || !"true".equals(p.getProperty(key).trim())) {
                continue;
            }
            String id = key.substring(0, key.length() - SUFFIX.length());
            if (ManifestParser.isValidId(id) && !ids.contains(id)) {
                ids.add(id);
            }
        }
        Collections.sort(ids);
        return ids;
    }
}
