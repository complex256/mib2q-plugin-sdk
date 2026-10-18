package mibsdk.runtime;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Persisted crash counter that decides safe mode.
 *
 * <p>File ({@code boot.state}, properties, exactly these two lines, no comment and no wall-clock value):
 * <pre>
 * unhealthyBoots=&lt;n&gt;
 * lastShutdownClean=&lt;true|false&gt;
 * </pre>
 * A boot is unhealthy if it neither became healthy ({@link #markHealthy}: ran healthyAfterMs with plugins
 * enabled) nor shut down clean ({@link #markCleanShutdown}: the ignition went off). {@link #beginBoot} counts
 * the previous boot: it increments {@code unhealthyBoots} unless {@code lastShutdownClean}, then records this
 * boot as running ({@code lastShutdownClean=false}). Safe mode when the count reaches the threshold or the
 * {@code SAFE_MODE} marker exists. A missing, unreadable or malformed file reads as a fresh unit (0, clean).
 * Safe mode loads no plugins, so it never marks a boot healthy: it lasts until the counter is reset.
 *
 * <p>Writes go to a temp file, fsync, rename (as the ledger does); they never throw (logged instead).
 * Called on the runtime control thread and, for {@link #markCleanShutdown}, on the publishing thread.
 */
public final class BootGuard {

    static final String UNHEALTHY_BOOTS = "unhealthyBoots";
    static final String LAST_SHUTDOWN_CLEAN = "lastShutdownClean";
    private static final String SOURCE = "mibsdk";

    private final File bootState;
    private final File safeModeMarker;
    private final int threshold;
    private final RingLog log;
    private int unhealthyBoots; // guarded by this
    private boolean lastShutdownClean; // guarded by this
    private boolean loaded; // guarded by this; the mark methods never write state they have not read

    public BootGuard(File bootState, File safeModeMarker, int threshold) {
        this(bootState, safeModeMarker, threshold, null);
    }

    BootGuard(File bootState, File safeModeMarker, int threshold, RingLog log) {
        this.bootState = bootState;
        this.safeModeMarker = safeModeMarker;
        this.threshold = threshold;
        this.log = log;
    }

    /** Counts the previous boot and records this one as running; true means SAFE MODE. */
    public synchronized boolean beginBoot() {
        read();
        if (!lastShutdownClean && unhealthyBoots < Integer.MAX_VALUE) {
            unhealthyBoots++;
        }
        lastShutdownClean = false;
        write();
        return markerPresent() || unhealthyBoots >= threshold;
    }

    /** This boot ran long enough with plugins enabled: unhealthyBoots=0. */
    public synchronized void markHealthy() {
        loadOnce();
        if (unhealthyBoots != 0) {
            unhealthyBoots = 0;
            write();
        }
    }

    /** The ignition went off: whatever ends this run (usually power-off) is a clean shutdown. */
    public synchronized void markCleanShutdown() {
        loadOnce();
        if (!lastShutdownClean) {
            lastShutdownClean = true;
            write();
        }
    }

    synchronized int unhealthyBoots() {
        loadOnce();
        return unhealthyBoots;
    }

    boolean markerPresent() {
        return safeModeMarker != null && safeModeMarker.exists();
    }

    private File tmpFile() {
        return new File(bootState.getPath() + ".tmp");
    }

    private void loadOnce() {
        if (!loaded) {
            read();
        }
    }

    private void read() {
        loaded = true;
        unhealthyBoots = 0;
        lastShutdownClean = true; // nothing before: a fresh unit
        File f = bootState;
        if (!f.isFile()) {
            f = tmpFile(); // died between delete and rename: the temp file is complete (fsynced)
            if (!f.isFile()) {
                return;
            }
        }
        Properties p = new Properties();
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            p.load(in);
        } catch (Throwable t) {
            append("WARN", f.getName() + " unreadable, treated as fresh: " + SdkRuntime.describe(t));
            return;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // read-only stream
                }
            }
        }
        String n = p.getProperty(UNHEALTHY_BOOTS);
        try {
            int v = n == null ? 0 : Integer.parseInt(n.trim());
            unhealthyBoots = v < 0 ? 0 : v;
        } catch (NumberFormatException e) {
            append("WARN", bootState.getName() + ": bad " + UNHEALTHY_BOOTS + ", treated as 0");
        }
        String clean = p.getProperty(LAST_SHUTDOWN_CLEAN);
        lastShutdownClean = clean == null || !"false".equals(clean.trim());
    }

    private void write() {
        String text = UNHEALTHY_BOOTS + "=" + unhealthyBoots + "\n" + LAST_SHUTDOWN_CLEAN + "=" + lastShutdownClean
                + "\n";
        File tmp = tmpFile();
        FileOutputStream out = null;
        try {
            File dir = bootState.getAbsoluteFile().getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
                throw new IOException("cannot create " + dir);
            }
            out = new FileOutputStream(tmp);
            out.write(text.getBytes("ISO-8859-1"));
            out.getFD().sync();
            out.close();
            out = null;
            if (!tmp.renameTo(bootState)) {
                bootState.delete(); // non-POSIX rename cannot replace; read() recovers from .tmp
                if (!tmp.renameTo(bootState)) {
                    throw new IOException("cannot rename " + tmp.getName() + " to " + bootState.getName());
                }
            }
        } catch (Throwable t) {
            append("ERROR", "cannot write " + bootState.getName() + ": " + SdkRuntime.describe(t));
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // the original failure is logged
                }
            }
        }
    }

    private void append(String level, String msg) {
        if (log != null) {
            log.append(level, SOURCE, msg);
        }
    }
}
