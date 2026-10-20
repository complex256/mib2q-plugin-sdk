package mibsdk.runtime;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;

/**
 * The user's on/off choices from the Plugins menu: {@code <persist>/plugins.properties}, one {@code <id>.off=true}
 * line per plugin switched off, written with {@link DurableFiles#replace}. A missing target is recovered from
 * {@link DurableFiles#tmpFile}; an unreadable or corrupt file reads as empty (every plugin on). Keys of plugins that
 * are not installed are kept but never asked about. Read once, on first use; the control thread is the only caller.
 */
final class UserToggles {

    private static final String SOURCE = "mibsdk";
    private static final String SUFFIX = ".off";

    private final File file;
    private final RingLog log;
    private List<String> off; // ids, sorted; null until loaded; guarded by this

    UserToggles(File persistFile, RingLog log) {
        this.file = persistFile;
        this.log = log;
    }

    synchronized boolean isOff(String id) {
        return id != null && loaded().contains(id);
    }

    /**
     * Records the choice and writes the file. True if the file now holds it; false (logged) if it could not be
     * written: the choice then holds for this run only.
     */
    synchronized boolean setOff(String id, boolean value) {
        if (!ManifestParser.isValidId(id)) {
            return false;
        }
        List<String> ids = loaded();
        if (ids.contains(id) == value) {
            return true;
        }
        if (value) {
            ids.add(id);
            Collections.sort(ids);
        } else {
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
            return true;
        } catch (Throwable t) {
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

    private List<String> read() {
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
