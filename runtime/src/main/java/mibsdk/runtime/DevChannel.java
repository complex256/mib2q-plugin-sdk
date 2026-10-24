package mibsdk.runtime;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import mibsdk.api.Subscription;

/**
 * File-based developer channel. Every {@code pollMs} on the {@link Clock}: each new or changed
 * {@code plugins/<id>.reload} marker calls {@code rt.reload(id)} once, then the log file is rewritten from the runtime's {@link RingLog}, but only if the log changed since the last successful
 * write ({@link RingLog#sequence()}) or the file is gone: an idle unit does not rewrite it every poll.
 *
 * <p>Markers are never deleted: the plugins dir is in the install root, read-only while the HMI runs. Instead the
 * channel remembers, in memory, the signature (mtime, size and leading bytes) of each marker it consumed; a marker
 * reloads again only once it changes ({@code mibsdk-dev push} writes a fresh token) or disappears and comes back.
 * The signature is recorded before the reload, so a marker rewritten during the reload triggers another. Markers
 * present when the channel starts count as consumed: this run loads every plugin from its current jar anyway.
 *
 * <p>Threads: its own daemon worker {@code mibsdk-dev} (priority NORM-1), driven by the shared timer. A
 * reload blocks that worker, never the runtime control thread's callers. Nothing escapes a poll.
 */
public final class DevChannel {

    private static final String SOURCE = "mibsdk";
    private static final String SUFFIX = ".reload";
    /** Leading marker bytes in the signature: mtime may have one-second resolution, a pushed token differs. */
    private static final int SIGNATURE_BYTES = 128;
    private static final int TOKEN_READ_BYTES = 4096;
    private static final int TOKEN_MAX = 64;

    private final SdkRuntime rt;
    private final File pluginsDir;
    private final File logFile;
    private final long pollMs;
    private final Clock clock;
    private SerialExecutor worker; // guarded by this
    private Subscription timer; // guarded by this
    private boolean stopped; // guarded by this
    private String lastWriteError; // worker thread only: log each distinct failure once
    /** Marker file name to the signature it had when consumed; worker thread only (filled by start() first). */
    private final Map consumed = new HashMap();
    private long writtenSequence = -1; // worker thread only: RingLog.sequence() of the last successful write

    public DevChannel(SdkRuntime rt, File pluginsDir, File logFile, long pollMs, Clock clock) {
        this.rt = rt;
        this.pluginsDir = pluginsDir;
        this.logFile = logFile;
        this.pollMs = pollMs < 1 ? 1 : pollMs;
        this.clock = clock;
    }

    /** Starts polling. Only the first call counts; does nothing after {@link #stop()}. */
    public synchronized void start() {
        if (worker != null || stopped) {
            return;
        }
        try {
            scanMarkers(true);
        } catch (Throwable t) {
            log("WARN", "dev channel: cannot list reload markers: " + SdkRuntime.describe(t));
        }
        worker = new SerialExecutor("dev", clock, Long.MAX_VALUE, Long.MAX_VALUE, new SerialExecutor.Listener() {
            public void onFailure(String label, Throwable t) {
                log("ERROR", "dev channel poll failed: " + SdkRuntime.describe(t));
            }

            public void onSlow(String label, long ms) {
            }

            public void onHung(String label) {
            }
        }, false);
        timer = new PluginScheduler(worker, clock).every(pollMs, new Runnable() {
            public void run() {
                poll();
            }
        });
    }

    /** Stops polling. Idempotent; a reload in progress finishes on its own. */
    public void stop() {
        SerialExecutor w;
        Subscription t;
        synchronized (this) {
            stopped = true;
            w = worker;
            t = timer;
            worker = null;
            timer = null;
        }
        if (t != null) {
            t.cancel();
        }
        if (w != null) {
            w.shutdown();
        }
    }

    private synchronized boolean isStopped() {
        return stopped;
    }

    /** One poll. Never throws. */
    void poll() {
        try {
            reloadMarked();
        } catch (Throwable t) {
            log("ERROR", "dev channel: reload markers failed: " + SdkRuntime.describe(t));
        }
        try {
            RingLog log = rt.log();
            long seq = log.sequence(); // read before the snapshot: a line appended meanwhile triggers the next write
            if (seq == writtenSequence && logFile.exists()) {
                return;
            }
            log.writeTo(logFile);
            writtenSequence = seq;
            lastWriteError = null;
        } catch (Throwable t) {
            String what = SdkRuntime.describe(t);
            if (!what.equals(lastWriteError)) {
                lastWriteError = what;
                log("WARN", "dev channel: cannot write " + logFile + ": " + what);
            }
        }
    }

    private void reloadMarked() {
        scanMarkers(false);
    }

    /**
     * Records every marker's signature; unless {@code baselineOnly}, reloads each one that is new or changed. Forgets
     * markers that are gone.
     */
    private void scanMarkers(boolean baselineOnly) {
        File[] files = pluginsDir.listFiles();
        if (files == null) {
            return;
        }
        Arrays.sort(files, new Comparator() {
            public int compare(Object a, Object b) {
                return ((File) a).getName().compareTo(((File) b).getName());
            }
        });
        Set present = new HashSet();
        for (int i = 0; i < files.length && !isStopped(); i++) {
            File f = files[i];
            String name = f.getName();
            if (!name.endsWith(SUFFIX) || !f.isFile()) {
                continue;
            }
            present.add(name);
            String sig = signature(f, null);
            if (sig == null || sig.equals(consumed.get(name))) {
                continue; // unreadable or changing (next poll retries), or already consumed
            }
            consumed.put(name, sig); // before the reload: a marker rewritten meanwhile triggers another
            if (baselineOnly) {
                continue;
            }
            String id = name.substring(0, name.length() - SUFFIX.length());
            if (!ManifestParser.isValidId(id)) {
                log("WARN", "dev channel: ignored a reload marker whose name is not a plugin id");
                continue;
            }
            String token = token(f);
            log("INFO", "dev channel: reload requested for " + id + (token.length() == 0 ? "" : " token=" + token));
            rt.reload(id); // blocks this worker only
        }
        if (isStopped()) {
            return;
        }
        for (Iterator it = consumed.keySet().iterator(); it.hasNext();) {
            if (!present.contains(it.next())) {
                it.remove(); // gone: the same marker dropped again is a new request
            }
        }
    }

    /**
     * {@code <mtime>:<size>:<leading bytes>}, or null if the marker cannot be read or changed while it was read
     * (e.g. {@code mibsdk-dev push} renamed a new marker over it): mtime and size are read before and after the
     * content and must match, and the content must be as long as they say, so a concurrent mv never yields a
     * mixed signature. Null is retried on the next poll.
     *
     * @param afterRead test hook run between the content read and the second stat; null on the unit
     */
    static String signature(File f, Runnable afterRead) {
        long mtime = f.lastModified();
        long size = f.length();
        int want = (int) Math.min(size, SIGNATURE_BYTES);
        byte[] head = new byte[want];
        InputStream in = null;
        int n = 0;
        try {
            in = new FileInputStream(f);
            while (n < head.length) {
                int r = in.read(head, n, head.length - n);
                if (r < 0) {
                    break;
                }
                n += r;
            }
        } catch (IOException e) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // read-only stream
                }
            }
        }
        if (afterRead != null) {
            afterRead.run();
        }
        if (n != want || f.lastModified() != mtime || f.length() != size) {
            return null; // changed under us: not stable yet
        }
        StringBuffer b = new StringBuffer();
        b.append(mtime).append(':').append(size).append(':');
        for (int i = 0; i < n; i++) {
            b.append(Integer.toHexString(head[i] & 0xff)).append(',');
        }
        return b.toString();
    }

    /**
     * The marker's token for the log line: its content as Latin-1, reduced to printable ASCII without spaces (so
     * trimming and sanitising are the same pass), capped at {@link #TOKEN_MAX}. Empty if unreadable or empty.
     */
    static String token(File f) {
        byte[] buf = new byte[TOKEN_READ_BYTES];
        int n = 0;
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            while (n < buf.length) {
                int r = in.read(buf, n, buf.length - n);
                if (r < 0) {
                    break;
                }
                n += r;
            }
        } catch (IOException e) {
            // keep what was read
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // read-only stream
                }
            }
        }
        StringBuffer b = new StringBuffer();
        for (int i = 0; i < n && b.length() < TOKEN_MAX; i++) {
            int c = buf[i] & 0xff;
            if (c > 0x20 && c < 0x7f) {
                b.append((char) c);
            }
        }
        return b.toString();
    }

    private void log(String level, String msg) {
        try {
            rt.log().append(level, SOURCE, msg);
        } catch (Throwable ignored) {
            // nothing left to report to
        }
    }
}
