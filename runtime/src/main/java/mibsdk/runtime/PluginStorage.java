package mibsdk.runtime;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import mibsdk.api.Storage;

/**
 * A plugin's key/value store backed by one {@code .properties} file. Reads and writes hit memory; the
 * file is written (temp file, then rename) at most {@code debounceMs} after the first unsaved change.
 * A shared daemon thread (priority NORM-1) drives the debounce while any store is dirty.
 *
 * <p>With a {@link StorageQuota}, a {@code put} that would grow the file past the plugin's or the total cap throws
 * {@code IllegalStateException} and stores nothing. The size counted is {@link #estimatedBytes()}, an upper bound of
 * what {@code Properties.store} writes, kept up to date on every change.
 */
public final class PluginStorage implements Storage {

    public static final long DEFAULT_DEBOUNCE_MS = 2000;
    private static final long POLL_MS = 250;
    /** Properties.store's date comment line, with room to spare. */
    private static final long HEADER_BYTES = 48;

    private static final List DIRTY = new ArrayList(); // PluginStorage with unsaved changes
    private static Thread flusher;

    private final File file;
    private final Clock clock;
    private final long debounceMs;
    private final StorageQuota quota; // null: unlimited
    /**
     * Guards props/dirty/dirtySince. Private on purpose: plugins hold this object (ctx.storage()), so a
     * plugin doing {@code synchronized (ctx.storage())} must never block the SDK's flushes.
     */
    private final Object lock = new Object();
    private final Properties props = new Properties(); // guarded by lock
    private final Object writeLock = new Object();
    private boolean dirty; // guarded by lock
    private long dirtySince; // guarded by lock
    private long estimate = HEADER_BYTES; // guarded by lock
    /**
     * Guarded by lock. Length on disk of a file that failed to load: it still takes that space until a write
     * replaces it, so the quota counts at least this much (0 once replaced, or when the load succeeded).
     */
    private long unreadOnDisk;

    public PluginStorage(File file, Clock clock) {
        this(file, clock, DEFAULT_DEBOUNCE_MS);
    }

    public PluginStorage(File file, Clock clock, long debounceMs) {
        this(file, clock, debounceMs, null);
    }

    PluginStorage(File file, Clock clock, long debounceMs, StorageQuota quota) {
        this.file = file;
        this.clock = clock;
        this.debounceMs = debounceMs;
        this.quota = quota;
        load();
        if (quota != null) {
            synchronized (lock) {
                quota.set(file, share(estimate));
            }
        }
    }

    @Override
    public String get(String key, String def) {
        String v;
        synchronized (lock) {
            v = props.getProperty(key);
        }
        return v == null ? def : v;
    }

    @Override
    public void put(String key, String value) {
        if (value == null) {
            remove(key);
            return;
        }
        synchronized (lock) {
            long next = estimate - entryBytes(key, props.getProperty(key)) + entryBytes(key, value);
            if (quota != null && share(next) > share(estimate)) {
                String refused = quota.resize(file, share(next));
                if (refused != null) {
                    throw new IllegalStateException(refused + "; " + key + " not stored");
                }
            }
            props.setProperty(key, value);
            estimate = next;
        }
        markDirty();
    }

    @Override
    public void remove(String key) {
        synchronized (lock) {
            estimate -= entryBytes(key, props.getProperty(key));
            props.remove(key);
            if (quota != null) {
                quota.resize(file, share(estimate)); // shrinking: never refused
            }
        }
        markDirty();
    }

    /** What the quota counts for an estimate of {@code bytes}: never less than an unreadable file on disk. */
    private long share(long bytes) {
        return Math.max(bytes, unreadOnDisk);
    }

    /** Upper bound of the file {@code Properties.store} would write now. */
    long estimatedBytes() {
        synchronized (lock) {
            return estimate;
        }
    }

    /** {@code key=value\n} as Properties.store escapes it, worst case; 0 for a missing value. */
    private static long entryBytes(String key, String value) {
        return value == null ? 0 : escapedBytes(key) + 1 + escapedBytes(value) + 1;
    }

    private static long escapedBytes(String s) {
        long n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c > 0x7e) {
                n += 6; // a six-byte unicode escape (tab, newline, CR and form feed take 2)
            } else if (c == ' ' || c == '\\' || c == '=' || c == ':' || c == '#' || c == '!') {
                n += 2;
            } else {
                n++;
            }
        }
        return n;
    }

    /** Writes pending changes immediately. A failed write keeps the changes pending for the next attempt. */
    public void flushNow() {
        synchronized (writeLock) {
            Properties snapshot;
            synchronized (lock) {
                if (!dirty) {
                    return;
                }
                dirty = false;
                snapshot = (Properties) props.clone();
            }
            if (write(snapshot)) {
                synchronized (lock) {
                    if (unreadOnDisk > 0) {
                        unreadOnDisk = 0; // the damaged file is gone: only the store's content counts now
                        if (quota != null) {
                            quota.resize(file, estimate); // shrinking: never refused
                        }
                    }
                }
            } else {
                synchronized (lock) {
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
        synchronized (lock) {
            if (!dirty || clock.uptimeMs() - dirtySince < debounceMs) {
                return;
            }
        }
        flushNow();
    }

    private boolean isDirty() {
        synchronized (lock) {
            return dirty;
        }
    }

    private void markDirty() {
        synchronized (lock) {
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
            synchronized (lock) {
                props.putAll(loaded);
                for (Iterator it = loaded.keySet().iterator(); it.hasNext();) {
                    String k = (String) it.next();
                    estimate += entryBytes(k, loaded.getProperty(k));
                }
            }
        } catch (Throwable corrupt) {
            // A damaged file reads as empty; the next flush replaces it. Until then it still takes its space.
            synchronized (lock) {
                unreadOnDisk = file.length();
            }
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
