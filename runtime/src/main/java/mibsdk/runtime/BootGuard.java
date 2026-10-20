package mibsdk.runtime;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Persisted crash counter that decides safe mode.
 *
 * <p>File ({@code boot.state}, properties, exactly these three lines, no comment and no wall-clock value):
 * <pre>
 * unhealthyBoots=&lt;n&gt;
 * lastShutdownClean=&lt;true|false&gt;
 * wrapBoots=&lt;n&gt;
 * </pre>
 * This class is the single owner of the format ({@link #reset()} content is
 * {@code unhealthyBoots=0\nlastShutdownClean=true\nwrapBoots=0\n}). A file without {@code wrapBoots} (written
 * before the key existed) reads as {@code wrapBoots=0}; unknown keys are ignored.
 *
 * <p>Only a boot that started loading plugins can count (spec: restarts "with plugins enabled"). A boot is
 * unhealthy if, after {@link #markPluginsLoading} (the start of the plugin load), it neither became healthy
 * ({@link #markHealthy}: ran healthyAfterMs with plugins enabled) nor saw the ignition go off
 * ({@link #markCleanShutdown}). {@link #beginBoot} counts the previous boot (increments {@code unhealthyBoots}
 * unless {@code lastShutdownClean}) and records this one as clean: a safe-mode boot, an adapter failure or a
 * restart during the plugin-start delay never counts. Safe mode when the count reaches the threshold or the
 * {@code SAFE_MODE} marker exists. A missing, unreadable or malformed file reads as a fresh unit (0, clean).
 * Safe mode loads no plugins, so it never lowers the count: it lasts until {@link #reset()}.
 *
 * <p>{@code wrapBoots} covers what {@code unhealthyBoots} cannot see: a crash between the configurator wrapping
 * the DSI services (at LSD start) and the plugin load (pluginStartDelayMs later), e.g. in a proxy, a trace proxy
 * over it or a bridge. The configurator, at init only, passes everything through if
 * {@link #wrapLoopDetected} (wrapBoots &gt;= threshold), else counts its boot with {@link #countWrapBoot}. The
 * runtime clears the count once the HMI ran healthyAfterMs after the runtime started, plugins or not
 * ({@link #markHmiHealthy}), and on a clean shutdown ({@link #markCleanShutdown}). When the plugin-start delay is
 * at least healthyAfterMs there is no such timer: the count is cleared in the same write that starts the plugin
 * count ({@link #markPluginsLoading(boolean)}), so a crash is always counted by one of the two. Once the threshold is reached
 * it latches like safe mode: a pass-through boot that runs fine proves nothing about wrapping, so only
 * {@link #reset()} (mibsdk-dev safe-mode off) clears it.
 *
 * <p>Writes go to a temp file, fsync, rename (as the ledger does); they never throw (logged instead). While the
 * last write failed the guard is {@link #inert()}: it cannot count crashes, and the runtime says so.
 * Called on the runtime control thread and, for {@link #markCleanShutdown}, on the publishing thread.
 */
public final class BootGuard {

    static final String UNHEALTHY_BOOTS = "unhealthyBoots";
    static final String LAST_SHUTDOWN_CLEAN = "lastShutdownClean";
    static final String WRAP_BOOTS = "wrapBoots";
    private static final String SOURCE = "mibsdk";

    /** One reading of boot.state. */
    private static final class State {
        int unhealthyBoots;
        boolean lastShutdownClean = true; // nothing before: a fresh unit
        int wrapBoots;
    }

    private final File bootState;
    private final File safeModeMarker;
    private final int threshold;
    private final RingLog log;
    private int unhealthyBoots; // guarded by this
    private boolean lastShutdownClean; // guarded by this
    private int wrapBoots; // guarded by this
    private boolean ignitionOffSeen; // guarded by this; in memory only: this run saw IgnitionChanged(false)
    private boolean loaded; // guarded by this; the mark methods never write state they have not read
    private boolean writeFailed; // guarded by this; the last write of boot.state failed

    public BootGuard(File bootState, File safeModeMarker, int threshold) {
        this(bootState, safeModeMarker, threshold, null);
    }

    BootGuard(File bootState, File safeModeMarker, int threshold, RingLog log) {
        this.bootState = bootState;
        this.safeModeMarker = safeModeMarker;
        this.threshold = threshold;
        this.log = log;
    }

    /**
     * What {@link #beginBoot} would return now, without writing anything (for the configurator, which runs
     * before the runtime). Never throws.
     */
    public static boolean wouldEnterSafeMode(File bootState, File safeModeMarker, int threshold) {
        try {
            if (safeModeMarker != null && safeModeMarker.exists()) {
                return true;
            }
            State st = load(bootState, null);
            return counted(st) >= threshold;
        } catch (Throwable t) {
            return true; // as beginBoot's caller: cannot count crashes, do not risk a boot loop
        }
    }

    /**
     * For the configurator, read-only: true if {@code threshold} boots in a row wrapped the DSI services without
     * the HMI then running healthy or shutting down clean (a crash loop the plugin counter cannot see), or if the
     * file cannot be judged. Never throws.
     */
    public static boolean wrapLoopDetected(File bootState, int threshold) {
        try {
            return load(bootState, null).wrapBoots >= threshold;
        } catch (Throwable t) {
            return true; // cannot count: do not risk a boot loop
        }
    }

    /**
     * For the configurator, once at init when it wraps this boot: wrapBoots + 1, every other key kept. The only
     * write outside the runtime; the runtime reads the file only later, in {@link #beginBoot}. Never throws.
     *
     * @return true only if the count was written. False means a wrap loop could not be caught, so the caller
     *         must not wrap this boot (fail safe, as when the file cannot be judged).
     */
    public static boolean countWrapBoot(File bootState, RingLog log) {
        try {
            State st = load(bootState, log);
            if (st.wrapBoots < Integer.MAX_VALUE) {
                st.wrapBoots++;
            }
            return write(bootState, st, log);
        } catch (Throwable t) {
            append(log, "ERROR", "cannot count the wrap boot: " + SdkRuntime.describe(t));
            return false;
        }
    }

    /** Counts the previous boot and records this one as clean until plugins load; true means SAFE MODE. */
    public synchronized boolean beginBoot() {
        read();
        unhealthyBoots = counted(currentState());
        lastShutdownClean = true; // this boot counts only once plugins start loading
        write();
        return markerPresent() || unhealthyBoots >= threshold;
    }

    /**
     * Plugins start loading: from here a restart without ignition-off or health counts as unhealthy (unless
     * the ignition already went off during this run). With {@code hmiHealthy} (the start delay is at least
     * healthyAfterMs, so no HMI-healthy timer ran) it also clears wrapBoots (unless latched), in the same write:
     * the wrap count hands over to the plugin count with no gap in which a crash counts nowhere.
     */
    synchronized void markPluginsLoading(boolean hmiHealthy) {
        loadOnce();
        boolean changed = hmiHealthy && clearWrapBoots();
        if (!ignitionOffSeen && lastShutdownClean) {
            lastShutdownClean = false;
            changed = true;
        }
        if (changed) {
            write();
        }
    }

    /** This boot ran long enough with plugins enabled: unhealthyBoots=0. */
    public synchronized void markHealthy() {
        loadOnce();
        if (unhealthyBoots != 0) {
            unhealthyBoots = 0;
            write();
        }
    }

    /**
     * The HMI ran healthyAfterMs since the runtime started, plugins or not: wrapBoots=0, unless the wrap loop
     * already latched (see the class comment).
     */
    public synchronized void markHmiHealthy() {
        loadOnce();
        if (clearWrapBoots()) {
            write();
        }
    }

    /**
     * The ignition went off: whatever ends this run (usually power-off) is a clean shutdown. Also clears
     * wrapBoots (unless latched).
     */
    public synchronized void markCleanShutdown() {
        loadOnce();
        ignitionOffSeen = true;
        boolean changed = clearWrapBoots();
        if (!lastShutdownClean) {
            lastShutdownClean = true;
            changed = true;
        }
        if (changed) {
            write();
        }
    }

    /**
     * Leaves safe mode on the next boot (unless the marker exists) and re-enables wrapping: unhealthyBoots=0,
     * lastShutdownClean=true, wrapBoots=0.
     */
    public synchronized void reset() {
        loaded = true;
        unhealthyBoots = 0;
        lastShutdownClean = true;
        wrapBoots = 0;
        write();
    }

    /** True if the configurator passes everything through until {@link #reset()} (wrapBoots reached threshold). */
    synchronized boolean wrapLoopLatched() {
        loadOnce();
        return wrapBoots >= threshold;
    }

    synchronized int wrapBoots() {
        loadOnce();
        return wrapBoots;
    }

    /** True if it changed wrapBoots. */
    private boolean clearWrapBoots() {
        if (wrapBoots == 0 || wrapBoots >= threshold) {
            return false;
        }
        wrapBoots = 0;
        return true;
    }

    synchronized int unhealthyBoots() {
        loadOnce();
        return unhealthyBoots;
    }

    /** True while the last write of boot.state failed: crash loops are not being counted. */
    public synchronized boolean inert() {
        return writeFailed;
    }

    /** The boot.state file (for messages). */
    public File file() {
        return bootState;
    }

    boolean markerPresent() {
        return safeModeMarker != null && safeModeMarker.exists();
    }

    /** The count after counting the boot recorded in {@code st}. */
    private static int counted(State st) {
        return !st.lastShutdownClean && st.unhealthyBoots < Integer.MAX_VALUE ? st.unhealthyBoots + 1
                : st.unhealthyBoots;
    }

    private State currentState() {
        State st = new State();
        st.unhealthyBoots = unhealthyBoots;
        st.lastShutdownClean = lastShutdownClean;
        st.wrapBoots = wrapBoots;
        return st;
    }

    private static File tmpFile(File bootState) {
        return DurableFiles.tmpFile(bootState);
    }

    private void loadOnce() {
        if (!loaded) {
            read();
        }
    }

    private void read() {
        loaded = true;
        State st = load(bootState, log);
        unhealthyBoots = st.unhealthyBoots;
        lastShutdownClean = st.lastShutdownClean;
        wrapBoots = st.wrapBoots;
    }

    private static State load(File bootState, RingLog log) {
        State st = new State();
        File f = bootState;
        if (!f.isFile()) {
            f = tmpFile(bootState); // died between delete and rename: the temp file is complete (fsynced)
            if (!f.isFile()) {
                return st;
            }
        }
        Properties p = new Properties();
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            p.load(in);
        } catch (Throwable t) {
            append(log, "WARN", f.getName() + " unreadable, treated as fresh: " + SdkRuntime.describe(t));
            return st;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // read-only stream
                }
            }
        }
        st.unhealthyBoots = count(p, UNHEALTHY_BOOTS, bootState, log);
        String clean = p.getProperty(LAST_SHUTDOWN_CLEAN);
        st.lastShutdownClean = clean == null || !"false".equals(clean.trim());
        st.wrapBoots = count(p, WRAP_BOOTS, bootState, log); // absent in files written before the key existed: 0
        return st;
    }

    /** A non-negative count; missing or malformed is 0. */
    private static int count(Properties p, String key, File bootState, RingLog log) {
        String n = p.getProperty(key);
        try {
            int v = n == null ? 0 : Integer.parseInt(n.trim());
            return v < 0 ? 0 : v;
        } catch (NumberFormatException e) {
            append(log, "WARN", bootState.getName() + ": bad " + key + ", treated as 0");
            return 0;
        }
    }

    private void write() {
        writeFailed = !write(bootState, currentState(), log);
    }

    /** False if it failed (logged). */
    private static boolean write(File bootState, State st, RingLog log) {
        String text = UNHEALTHY_BOOTS + "=" + st.unhealthyBoots + "\n" + LAST_SHUTDOWN_CLEAN + "="
                + st.lastShutdownClean + "\n" + WRAP_BOOTS + "=" + st.wrapBoots + "\n";
        try {
            DurableFiles.replace(bootState, text.getBytes("ISO-8859-1"));
            return true;
        } catch (Throwable t) {
            append(log, "ERROR", "cannot write " + bootState + ": " + SdkRuntime.describe(t));
            return false;
        }
    }

    private static void append(RingLog log, String level, String msg) {
        if (log != null) {
            log.append(level, SOURCE, msg);
        }
    }
}
