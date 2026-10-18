package mibsdk.runtime;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import mibsdk.api.Subscription;

/**
 * File-based developer channel. Every {@code pollMs} on the {@link Clock}: each {@code plugins/<id>.reload}
 * marker is deleted and {@code rt.reload(id)} called (deleted first, so a marker touched during the reload
 * triggers another; a marker that cannot be deleted is skipped, never reloaded on every poll), then the
 * log file is rewritten from the runtime's {@link RingLog}.
 *
 * <p>Threads: its own daemon worker {@code mibsdk-dev} (priority NORM-1), driven by the shared timer. A
 * reload blocks that worker, never the runtime control thread's callers. Nothing escapes a poll.
 */
public final class DevChannel {

    private static final String SOURCE = "mibsdk";
    private static final String SUFFIX = ".reload";

    private final SdkRuntime rt;
    private final File pluginsDir;
    private final File logFile;
    private final long pollMs;
    private final Clock clock;
    private SerialExecutor worker; // guarded by this
    private Subscription timer; // guarded by this
    private boolean stopped; // guarded by this
    private String lastWriteError; // worker thread only: log each distinct failure once
    private File lastStuck; // worker thread only

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
            rt.log().writeTo(logFile);
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
        File[] files = pluginsDir.listFiles();
        if (files == null) {
            return;
        }
        Arrays.sort(files, new Comparator() {
            public int compare(Object a, Object b) {
                return ((File) a).getName().compareTo(((File) b).getName());
            }
        });
        for (int i = 0; i < files.length && !isStopped(); i++) {
            File f = files[i];
            String name = f.getName();
            if (!name.endsWith(SUFFIX)) {
                continue;
            }
            if (!f.delete()) {
                if (!f.equals(lastStuck)) {
                    lastStuck = f;
                    log("WARN", "dev channel: cannot delete " + name + "; not reloading");
                }
                continue;
            }
            String id = name.substring(0, name.length() - SUFFIX.length());
            if (!ManifestParser.isValidId(id)) {
                log("WARN", "dev channel: ignored a reload marker whose name is not a plugin id");
                continue;
            }
            log("INFO", "dev channel: reload requested for " + id);
            rt.reload(id); // blocks this worker only
        }
    }

    private void log(String level, String msg) {
        try {
            rt.log().append(level, SOURCE, msg);
        } catch (Throwable ignored) {
            // nothing left to report to
        }
    }
}
