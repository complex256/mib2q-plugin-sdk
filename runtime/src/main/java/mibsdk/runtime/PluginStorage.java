package mibsdk.runtime;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import mibsdk.api.Storage;

/**
 * A plugin's key/value store backed by one {@code .properties} file. Reads and writes hit memory; the
 * file is written (temp file, then rename) at most {@code debounceMs} after the first unsaved change.
 * A shared daemon thread (priority NORM-1) drives the debounce while any store is dirty.
 */
public final class PluginStorage implements Storage {

    public static final long DEFAULT_DEBOUNCE_MS = 2000;
    private static final long POLL_MS = 250;

    private static final List DIRTY = new ArrayList(); // PluginStorage with unsaved changes
    private static Thread flusher;

    private final File file;
    private final Clock clock;
    private final long debounceMs;
    private final Properties props = new Properties(); // guarded by this
    private final Object writeLock = new Object();
    private boolean dirty; // guarded by this
    private long dirtySince; // guarded by this

    public PluginStorage(File file, Clock clock) {
        this(file, clock, DEFAULT_DEBOUNCE_MS);
    }

    public PluginStorage(File file, Clock clock, long debounceMs) {
        this.file = file;
        this.clock = clock;
        this.debounceMs = debounceMs;
        load();
    }

    @Override
    public synchronized String get(String key, String def) {
        String v = props.getProperty(key);
        return v == null ? def : v;
    }

    @Override
    public void put(String key, String value) {
        if (value == null) {
            remove(key);
            return;
        }
        synchronized (this) {
            props.setProperty(key, value);
        }
        markDirty();
    }

    @Override
    public void remove(String key) {
        synchronized (this) {
            props.remove(key);
        }
        markDirty();
    }

    /** Writes pending changes immediately. A failed write keeps the changes pending for the next attempt. */
    public void flushNow() {
        synchronized (writeLock) {
            Properties snapshot;
            synchronized (this) {
                if (!dirty) {
                    return;
                }
                dirty = false;
                snapshot = (Properties) props.clone();
            }
            if (!write(snapshot)) {
                synchronized (this) {
                    if (!dirty) {
                        dirty = true;
                        dirtySince = clock.uptimeMs();
                    }
                }
                track(this);
            }
        }
    }

    /** Writes pending changes if the debounce interval has elapsed on the clock. Also the test hook. */
    void flushIfDue() {
        synchronized (this) {
            if (!dirty || clock.uptimeMs() - dirtySince < debounceMs) {
                return;
            }
        }
        flushNow();
    }

    private synchronized boolean isDirty() {
        return dirty;
    }

    private void markDirty() {
        synchronized (this) {
            if (!dirty) {
                dirty = true;
                dirtySince = clock.uptimeMs();
            }
        }
        track(this);
    }

    private void load() {
        if (!file.isFile()) {
            return;
        }
        Properties loaded = new Properties();
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            loaded.load(in);
            synchronized (this) {
                props.putAll(loaded);
            }
        } catch (Throwable corrupt) {
            // A damaged file reads as empty; the next flush replaces it.
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // nothing to do
                }
            }
        }
    }

    private boolean write(Properties snapshot) {
        File dir = file.getAbsoluteFile().getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            return false;
        }
        File tmp = new File(file.getPath() + ".tmp");
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(tmp);
            snapshot.store(out, null);
            out.getFD().sync();
            out.close();
            out = null;
            if (!tmp.renameTo(file)) {
                file.delete();
                if (!tmp.renameTo(file)) {
                    return false;
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // nothing to do
                }
            }
        }
    }

    private static void track(PluginStorage s) {
        synchronized (DIRTY) {
            if (!DIRTY.contains(s)) {
                DIRTY.add(s);
            }
            if (flusher == null) {
                Thread t = new Thread("mibsdk-storage") {
                    public void run() {
                        flushLoop();
                    }
                };
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                flusher = t;
                t.start();
            }
        }
    }

    private static void flushLoop() {
        try {
            while (true) {
                Object[] snapshot;
                synchronized (DIRTY) {
                    for (int i = DIRTY.size() - 1; i >= 0; i--) {
                        if (!((PluginStorage) DIRTY.get(i)).isDirty()) {
                            DIRTY.remove(i);
                        }
                    }
                    if (DIRTY.isEmpty()) {
                        flusher = null; // restarted by the next track()
                        return;
                    }
                    snapshot = DIRTY.toArray();
                }
                for (int i = 0; i < snapshot.length; i++) {
                    try {
                        ((PluginStorage) snapshot[i]).flushIfDue();
                    } catch (Throwable ignored) {
                        // keep flushing the others
                    }
                }
                try {
                    Thread.sleep(POLL_MS);
                } catch (InterruptedException ignored) {
                    // keep polling
                }
            }
        } finally {
            synchronized (DIRTY) {
                if (flusher == Thread.currentThread()) {
                    flusher = null;
                }
            }
        }
    }
}
