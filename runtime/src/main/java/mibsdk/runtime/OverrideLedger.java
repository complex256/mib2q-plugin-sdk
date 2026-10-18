package mibsdk.runtime;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import mibsdk.api.OverrideConflictException;
import mibsdk.api.OverrideScope;
import mibsdk.api.VehicleOverride;

/**
 * Runtime-owned record of every override of persistent vehicle/ECU state, so the runtime (not the plugin)
 * can put the prior value back.
 *
 * <p>File: {@code <baseDir>/ledger.properties}, one line per key, {@code <key>=<pluginId>|<scope>|<prior>|<active>},
 * sorted by key, no comments and no wall-clock values (an empty ledger is an empty file). Written with
 * temp file + fsync + rename on every change. The prior is on disk before {@link #acquire} returns and
 * before anything is applied; an entry leaves the file only once its prior is back (applied, or reported
 * by the car).
 * An unreadable file is treated as empty (logged, and kept aside as {@code ledger.properties.corrupt});
 * a malformed line is skipped (logged).
 *
 * <p>Threads: every {@link Applier#apply} (initial apply, restore, sticky re-apply, retry) runs on the
 * ledger's own daemon thread {@code mibsdk-ledger} (a {@link SerialExecutor}, priority NORM-1), never on a
 * stock thread or a plugin's executor. The public methods only change memory under a private lock and
 * queue work; {@link #acquire} additionally writes the file on the caller's thread. No monitor reachable by
 * plugin code is ever taken: handles ({@link VehicleOverride}) lock the ledger's private lock only. Delays
 * (debounce, retry) are measured on the injected {@link Clock} via a {@link PluginScheduler}.
 *
 * <p>Ownership: an entry belongs to a plugin id. If the same plugin acquires the key again, the new handle
 * replaces the old one and the original prior is kept (also for an entry loaded from the file after a
 * reboot). A different plugin gets {@link OverrideConflictException}, unless the entry is already being
 * restored: then it takes over and inherits the original prior (the car may not have the prior back yet).
 *
 * <p>Sticky: while an override is in force, an observed value different from it is re-applied
 * 1000 ms after the last change, at most 5 times per 60000 ms (all on the {@link Clock}); then the ledger gives up re-applying (logged) but still restores later.
 *
 * <p>Failures: an apply or restore that throws is logged and retried with backoff on the Clock (5 s,
 * doubling, capped at 60 s). An apply gives up after 5 attempts (the car keeps its value). A restore is
 * never given up while the runtime runs: it is also retried at once by {@link #retryPendingRestores()}
 * (ignition on) and by any {@link #onObserved} of its key, and an observed value equal to the prior
 * completes it. Until then the entry stays in the file and in {@link #active()} with
 * {@link ActiveOverride#restorePending()}.
 */
public final class OverrideLedger {

    /** Writes one encoded value for one key to the car. May block or throw. */
    public interface Applier {
        void apply(String key, String encodedValue) throws Exception;
    }

    static final long DEBOUNCE_MS = 1000;
    static final int MAX_REAPPLIES = 5;
    static final long REAPPLY_WINDOW_MS = 60000;
    static final long RETRY_MS = 5000;
    static final long MAX_RETRY_MS = 60000;
    /** Attempts for applying an override; a restore is never given up while the runtime runs. */
    static final int MAX_ATTEMPTS = 5;
    private static final String SOURCE = "ledger";
    private static final int MAX_TOKEN = 128;
    private static final long WAIT_SLICE_MS = 50;

    /** One key's override. Guarded by {@link #lock}. */
    private static final class Entry {
        final String key;
        final String prior;
        String pluginId;
        OverrideScope scope;
        String active;
        Applier applier; // null for an entry loaded from the file: the attached fallback is used
        Handle holder; // null when orphaned (loaded from the file)
        boolean detached; // runtime stopping: kept for the next boot, but the holder may still release it
        boolean restoring;
        int generation; // bumped on every ownership/state change
        String applied; // value last applied successfully, null if unknown
        int failures; // consecutive failed applies/restores for the current generation
        int retrySeq; // only the newest scheduled retry runs
        // sticky re-apply
        String lastObserved;
        long lastDeviationAt;
        int checkSeq;
        boolean checkPending;
        boolean gaveUp;
        final long[] reapplies = new long[MAX_REAPPLIES];
        int reapplyCount;

        Entry(String key, String prior) {
            this.key = key;
            this.prior = prior;
        }

        Entry copy() {
            Entry c = new Entry(key, prior);
            c.pluginId = pluginId;
            c.scope = scope;
            c.active = active;
            c.applier = applier;
            c.holder = holder;
            c.detached = detached;
            c.restoring = restoring;
            c.applied = applied;
            return c;
        }

        void revertTo(Entry c) {
            pluginId = c.pluginId;
            scope = c.scope;
            active = c.active;
            applier = c.applier;
            holder = c.holder;
            detached = c.detached;
            restoring = c.restoring;
            applied = c.applied;
            failures = 0;
            resetSticky();
        }

        void resetSticky() {
            lastObserved = null;
            checkSeq++;
            checkPending = false;
            gaveUp = false;
            reapplyCount = 0;
        }
    }

    /** The plugin's handle. Locks only the ledger's private lock, never itself. */
    private final class Handle implements VehicleOverride {
        final String key;

        Handle(String key) {
            this.key = key;
        }

        @Override
        public boolean isActive() {
            synchronized (lock) {
                return heldBy(this) != null;
            }
        }

        @Override
        public void release() {
            releaseHandle(this, true);
        }
    }

    private final File file;
    private final Clock clock;
    private final RingLog log;
    private final Object lock = new Object();
    private final Object fileLock = new Object(); // serialises file writes; never held with lock while writing
    private final List entries = new ArrayList(); // Entry; guarded by lock
    private Applier fallback; // guarded by lock
    private boolean retainPersistent; // guarded by lock
    private boolean shutdown; // guarded by lock
    private SerialExecutor worker; // guarded by lock; created on first use
    private PluginScheduler timer; // guarded by lock

    public OverrideLedger(File ledgerFile, Clock clock) {
        this(ledgerFile, clock, null);
    }

    OverrideLedger(File ledgerFile, Clock clock, RingLog log) {
        this.file = ledgerFile;
        this.clock = clock;
        this.log = log;
        load();
    }

    /**
     * Records the override (prior first, on disk) and queues the apply of {@code activeEncoded}.
     *
     * @throws OverrideConflictException if another plugin holds {@code key}
     * @throws IllegalArgumentException if an argument is missing or would break the file format
     * @throws IllegalStateException if the ledger file cannot be written (then nothing is applied)
     */
    public VehicleOverride acquire(String key, String pluginId, OverrideScope scope, String priorEncoded,
            String activeEncoded, Applier applier) throws OverrideConflictException {
        requireToken("key", key);
        requireToken("pluginId", pluginId);
        requireToken("prior", priorEncoded);
        requireToken("active", activeEncoded);
        if (scope == null || applier == null) {
            throw new IllegalArgumentException("scope and applier are required");
        }
        Handle h = new Handle(key);
        synchronized (fileLock) {
            Entry e;
            Entry before;
            int gen;
            synchronized (lock) {
                if (shutdown) {
                    throw new IllegalStateException("the override ledger is shut down");
                }
                e = find(key);
                if (e != null && !e.restoring && !e.pluginId.equals(pluginId)) {
                    throw new OverrideConflictException(key, e.pluginId);
                }
                before = e == null ? null : e.copy();
                if (e == null) {
                    e = new Entry(key, priorEncoded);
                    entries.add(e);
                }
                e.pluginId = pluginId;
                e.scope = scope;
                e.active = activeEncoded;
                e.applier = applier;
                e.holder = h;
                e.detached = false;
                e.restoring = false;
                e.applied = null;
                e.failures = 0;
                e.resetSticky();
                gen = ++e.generation;
            }
            if (!writeLocked()) {
                boolean resync = false;
                synchronized (lock) {
                    if (e.generation == gen) {
                        if (before == null) {
                            entries.remove(e);
                        } else {
                            e.revertTo(before);
                            resync = e.restoring;
                        }
                        e.generation++;
                    }
                }
                if (resync) {
                    enqueueSync(key);
                }
                throw new IllegalStateException("cannot record the override in " + file.getName()
                        + "; nothing was applied");
            }
            info("override " + key + "=" + activeEncoded + " by " + pluginId + " (" + scope + "), prior " + e.prior);
        }
        enqueueSync(key);
        return h;
    }

    /** Restores every entry of {@code pluginId} (live or orphaned). During shutdown PERSISTENT ones are kept. */
    public void releaseAllFor(String pluginId) {
        if (pluginId == null) {
            return;
        }
        List keys = new ArrayList();
        synchronized (lock) {
            for (int i = 0; i < entries.size(); i++) {
                Entry e = (Entry) entries.get(i);
                if (!e.restoring && !e.detached && e.pluginId.equals(pluginId) && endOrDetach(e, false)) {
                    keys.add(e.key);
                }
            }
        }
        enqueueSyncs(keys, "released for " + pluginId);
    }

    /** Restores every entry of {@code scope} (ignition off: UNTIL_IGNITION_OFF). Never blocks. */
    public void releaseScope(OverrideScope scope) {
        List keys = new ArrayList();
        synchronized (lock) {
            for (int i = 0; i < entries.size(); i++) {
                Entry e = (Entry) entries.get(i);
                if (!e.restoring && e.scope == scope) {
                    markRestoring(e);
                    keys.add(e.key);
                }
            }
        }
        enqueueSyncs(keys, "scope " + scope + " ended");
    }

    /** Safe mode / startup: restores every entry left in the file, using {@code applier} (also kept as fallback). */
    public void restorePendingOnBoot(Applier applier) {
        List keys = new ArrayList();
        synchronized (lock) {
            if (applier != null) {
                fallback = applier;
            }
            for (int i = 0; i < entries.size(); i++) {
                Entry e = (Entry) entries.get(i);
                if (!e.restoring) {
                    markRestoring(e);
                }
                keys.add(e.key);
            }
        }
        timer(); // create the ledger thread now, not later on a publishing thread
        enqueueSyncs(keys, "pending restore on boot");
    }

    /**
     * Retries every pending restore now (ignition on, or any sign the car is awake). Never blocks, so the
     * platform host may call it while publishing.
     */
    public void retryPendingRestores() {
        List keys = new ArrayList();
        synchronized (lock) {
            for (int i = 0; i < entries.size(); i++) {
                Entry e = (Entry) entries.get(i);
                if (e.restoring) {
                    keys.add(e.key);
                }
            }
        }
        enqueueSyncs(keys, null);
    }

    /**
     * The car reported {@code observedEncoded} for {@code key}. While an override is in force this drives the
     * sticky re-apply; while a restore is pending it retries the restore at once, and an observed value equal
     * to the prior completes it. Never blocks (memory only, plus queueing), so the platform host may call it
     * while publishing.
     */
    public void onObserved(String key, String observedEncoded) {
        if (key == null || observedEncoded == null) {
            return;
        }
        int seq;
        synchronized (lock) {
            Entry e = find(key);
            if (e == null || shutdown) {
                return;
            }
            if (e.restoring) {
                e.lastObserved = observedEncoded; // sync() treats an observed prior as restored
                seq = -1;
            } else if (e.gaveUp) {
                return;
            } else {
                seq = observeActive(e, observedEncoded);
            }
        }
        if (seq == -1) {
            enqueueSync(key);
        } else if (seq != -2) {
            scheduleCheck(key, seq, DEBOUNCE_MS);
        }
    }

    /** Sticky bookkeeping for an override in force; the check sequence to schedule, or -2 for none. Under lock. */
    private int observeActive(Entry e, String observedEncoded) {
        e.lastObserved = observedEncoded;
        if (observedEncoded.equals(e.active)) {
            e.applied = e.active;
            return -2;
        }
        e.applied = null;
        e.lastDeviationAt = clock.uptimeMs();
        if (e.checkPending) {
            return -2;
        }
        e.checkPending = true;
        return e.checkSeq;
    }

    /**
     * Every override whose effect may still be on the car, sorted by key: live, orphaned, and those whose
     * restore has not succeeded yet ({@link ActiveOverride#restorePending()}). An entry disappears only once
     * its prior is back.
     */
    public List<ActiveOverride> active() {
        List<ActiveOverride> out = new ArrayList<ActiveOverride>();
        synchronized (lock) {
            List sorted = sortedEntries();
            for (int i = 0; i < sorted.size(); i++) {
                Entry e = (Entry) sorted.get(i);
                out.add(new ActiveOverride(e.key, e.pluginId, e.scope, e.active, e.restoring));
            }
        }
        return out;
    }

    /** Sets the applier for entries loaded from the file and starts any restore that was waiting for one. */
    void attach(Applier applier) {
        timer(); // create the ledger thread now, not later on a publishing thread
        List keys = new ArrayList();
        synchronized (lock) {
            fallback = applier;
            for (int i = 0; i < entries.size(); i++) {
                Entry e = (Entry) entries.get(i);
                if (e.restoring) {
                    keys.add(e.key);
                }
            }
        }
        enqueueSyncs(keys, null);
    }

    /**
     * Runtime stop: from now on, releases that do not come from the plugin itself (registrations being
     * cancelled, {@link #releaseAllFor}) keep PERSISTENT entries in the file, unowned, for the next boot.
     */
    void beginShutdown() {
        synchronized (lock) {
            retainPersistent = true;
        }
    }

    /** Waits (at most about {@code maxMs} of real time) until the queued ledger work has run. */
    boolean awaitIdle(long maxMs) {
        SerialExecutor w;
        synchronized (lock) {
            w = worker;
        }
        if (w == null) {
            return true;
        }
        final boolean[] done = {false};
        boolean submitted = w.submit("ledger:idle", new Runnable() {
            public void run() {
                synchronized (done) {
                    done[0] = true;
                    done.notifyAll();
                }
            }
        });
        if (!submitted) {
            return true;
        }
        long slices = maxMs / WAIT_SLICE_MS + 1;
        synchronized (done) {
            for (long i = 0; !done[0] && i < slices; i++) {
                try {
                    done.wait(WAIT_SLICE_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return done[0];
        }
    }

    /** Stops the ledger thread. Entries not yet restored stay in the file for the next boot. */
    void shutdown() {
        SerialExecutor w;
        synchronized (lock) {
            shutdown = true;
            w = worker;
            worker = null;
            timer = null;
        }
        if (w != null) {
            w.shutdown();
        }
    }

    // ---- releasing ----

    private void releaseHandle(Handle h, boolean byPlugin) {
        String key;
        synchronized (lock) {
            Entry e = heldBy(h);
            if (e == null || !endOrDetach(e, byPlugin)) {
                return;
            }
            key = e.key;
        }
        List keys = new ArrayList();
        keys.add(key);
        enqueueSyncs(keys, byPlugin ? "released by the plugin" : "the plugin stopped");
    }

    /** Called when the plugin's registrations are cancelled (disable, quarantine, stop). */
    void releaseByRuntime(VehicleOverride handle) {
        if (handle instanceof Handle) {
            releaseHandle((Handle) handle, false);
        }
    }

    /**
     * Ends the entry (true: a restore is needed) or, during shutdown, detaches a PERSISTENT one: it stays in
     * the file for the next boot, but its holder can still release it (e.g. in onDisable). Under lock.
     */
    private boolean endOrDetach(Entry e, boolean byPlugin) {
        if (!byPlugin && retainPersistent && e.scope == OverrideScope.PERSISTENT) {
            e.detached = true;
            return false;
        }
        markRestoring(e);
        return true;
    }

    private static void markRestoring(Entry e) {
        e.restoring = true;
        e.holder = null;
        e.detached = false;
        e.failures = 0;
        e.resetSticky();
        e.generation++;
    }

    // ---- ledger thread ----

    private void enqueueSyncs(List keys, String why) {
        for (int i = 0; i < keys.size(); i++) {
            String key = (String) keys.get(i);
            if (why != null) {
                info("restoring " + key + ": " + why);
            }
            enqueueSync(key);
        }
    }

    private void enqueueSync(final String key) {
        SerialExecutor w = worker();
        if (w != null) {
            w.submit("ledger:sync:" + key, new Runnable() {
                public void run() {
                    sync(key);
                }
            });
        }
    }

    /** Brings the car in line with the entry: the active value while held, the prior while restoring. */
    private void sync(String key) {
        Entry e;
        String value;
        Applier a;
        int gen;
        boolean restore;
        boolean alreadyBack; // the car reported the prior while the restore was pending
        synchronized (lock) {
            e = find(key);
            if (e == null) {
                return;
            }
            restore = e.restoring;
            alreadyBack = restore && e.prior.equals(e.lastObserved);
            value = restore ? e.prior : e.active;
            if (!restore && value.equals(e.applied)) {
                return; // already in force
            }
            a = e.applier != null ? e.applier : fallback;
            gen = e.generation;
        }
        if (a == null && !alreadyBack) {
            warn((restore ? "restore of " : "apply of ") + key + " waits: no platform applier yet");
            return;
        }
        Throwable failure = alreadyBack ? null : applyQuietly(a, key, value);
        if (failure != null) {
            onSyncFailed(e, gen, restore, value, failure);
            return;
        }
        boolean removed = false;
        String owner;
        synchronized (lock) {
            if (find(key) != e) {
                return;
            }
            owner = e.pluginId;
            e.applied = value;
            e.failures = 0;
            if (restore && e.generation == gen) {
                entries.remove(e);
                removed = true;
            }
        }
        if (removed) {
            write();
            info("restored " + key + "=" + value + (alreadyBack ? " (reported by the car)" : "")
                    + " (override of " + owner + " ended)");
        } else if (!restore) {
            info("applied " + key + "=" + value + " for " + owner);
        }
    }

    /**
     * Logs a failed apply/restore and schedules a retry (same generation only). A restore is retried with
     * backoff (5 s doubling, capped at 60 s, on the Clock) for as long as the runtime runs; an apply gives up
     * after MAX_ATTEMPTS.
     */
    private void onSyncFailed(Entry e, int gen, boolean restore, String value, Throwable failure) {
        int attempts = 0;
        int seq = 0;
        String owner;
        synchronized (lock) {
            if (find(e.key) != e) {
                return;
            }
            owner = e.pluginId;
            e.applied = null;
            if (e.generation == gen) {
                attempts = ++e.failures;
                seq = ++e.retrySeq;
            }
        }
        boolean retry = attempts > 0 && (restore || attempts < MAX_ATTEMPTS);
        long delay = retryDelay(attempts);
        error((restore ? "restore of " + e.key + " to " : "apply of " + e.key + "=") + value + " for " + owner
                + " failed (attempt " + attempts + (restore ? "" : "/" + MAX_ATTEMPTS) + "): "
                + SdkRuntime.describe(failure) + (retry ? "; retrying in " + delay + " ms" : ""));
        if (retry) {
            scheduleSync(e.key, gen, seq, delay);
        } else if (attempts >= MAX_ATTEMPTS) {
            error("stopped retrying the apply of " + e.key + "; the car keeps its current value");
        }
    }

    /** RETRY_MS doubling per failed attempt, capped at MAX_RETRY_MS. */
    static long retryDelay(int attempts) {
        long d = RETRY_MS;
        for (int i = 1; i < attempts && d < MAX_RETRY_MS; i++) {
            d *= 2;
        }
        return Math.min(d, MAX_RETRY_MS);
    }

    /** Sticky budget: records a re-apply at {@code now} unless MAX_REAPPLIES happened in the window. Under lock. */
    private static boolean takeReapplySlot(Entry e, long now) {
        int kept = 0;
        for (int i = 0; i < e.reapplyCount; i++) {
            if (e.reapplies[i] > now - REAPPLY_WINDOW_MS) {
                e.reapplies[kept++] = e.reapplies[i];
            }
        }
        e.reapplyCount = kept;
        if (kept >= MAX_REAPPLIES) {
            return false;
        }
        e.reapplies[e.reapplyCount++] = now;
        return true;
    }

    private void stickyCheck(String key, int seq) {
        Entry e;
        String value = null;
        Applier a = null;
        long wait = 0;
        boolean giveUp = false;
        int count = 0;
        synchronized (lock) {
            e = find(key);
            if (e == null || e.checkSeq != seq) {
                return;
            }
            e.checkPending = false;
            if (e.restoring || e.gaveUp || e.lastObserved == null || e.lastObserved.equals(e.active)) {
                return;
            }
            long now = clock.uptimeMs();
            wait = e.lastDeviationAt + DEBOUNCE_MS - now;
            if (wait > 0) {
                e.checkPending = true;
            } else {
                if (!takeReapplySlot(e, now)) {
                    e.gaveUp = true;
                    giveUp = true;
                } else {
                    count = e.reapplyCount;
                    e.lastObserved = null;
                    value = e.active;
                    a = e.applier != null ? e.applier : fallback;
                }
            }
        }
        if (wait > 0) {
            scheduleCheck(key, seq, wait);
            return;
        }
        if (giveUp) {
            error("giving up sticky re-apply of " + key + " for " + e.pluginId + ": the car changed it back "
                    + MAX_REAPPLIES + " times within " + REAPPLY_WINDOW_MS + " ms; it is still restored later");
            return;
        }
        if (a == null) {
            return;
        }
        Throwable failure = applyQuietly(a, key, value);
        synchronized (lock) {
            if (find(key) == e && failure == null && value.equals(e.active)) {
                e.applied = value;
            }
        }
        if (failure == null) {
            info("re-applied " + key + "=" + value + " (" + count + "/" + MAX_REAPPLIES + " within "
                    + REAPPLY_WINDOW_MS + " ms)");
        } else {
            error("re-apply of " + key + "=" + value + " failed: " + SdkRuntime.describe(failure));
        }
    }

    private void scheduleCheck(final String key, final int seq, long delayMs) {
        PluginScheduler t = timer();
        if (t != null) {
            t.after(delayMs, new Runnable() {
                public void run() {
                    stickyCheck(key, seq);
                }
            });
        }
    }

    private void scheduleSync(final String key, final int gen, final int seq, long delayMs) {
        PluginScheduler t = timer();
        if (t != null) {
            t.after(delayMs, new Runnable() {
                public void run() {
                    boolean current;
                    synchronized (lock) {
                        Entry e = find(key);
                        current = e != null && e.generation == gen && e.retrySeq == seq;
                    }
                    if (current) {
                        sync(key);
                    }
                }
            });
        }
    }

    private static Throwable applyQuietly(Applier a, String key, String value) {
        try {
            a.apply(key, value);
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    private SerialExecutor worker() {
        synchronized (lock) {
            if (worker == null && !shutdown) {
                worker = new SerialExecutor("ledger", clock, 5000, 30000, new SerialExecutor.Listener() {
                    public void onFailure(String label, Throwable t) {
                        error("ledger task " + label + " failed: " + SdkRuntime.describe(t));
                    }

                    public void onSlow(String label, long ms) {
                        warn("ledger task " + label + " took " + ms + " ms");
                    }

                    public void onHung(String label) {
                        error("ledger task " + label + " is hung (platform apply blocked?)");
                    }
                });
            }
            return worker;
        }
    }

    private PluginScheduler timer() {
        SerialExecutor w = worker();
        synchronized (lock) {
            if (w == null) {
                return null;
            }
            if (timer == null) {
                timer = new PluginScheduler(w, clock);
            }
            return timer;
        }
    }

    // ---- state helpers (under lock) ----

    private Entry find(String key) {
        for (int i = 0; i < entries.size(); i++) {
            Entry e = (Entry) entries.get(i);
            if (e.key.equals(key)) {
                return e;
            }
        }
        return null;
    }

    private Entry heldBy(Handle h) {
        Entry e = find(h.key);
        return e != null && e.holder == h ? e : null;
    }

    private List sortedEntries() {
        List sorted = new ArrayList(entries);
        Collections.sort(sorted, new Comparator() {
            public int compare(Object a, Object b) {
                return ((Entry) a).key.compareTo(((Entry) b).key);
            }
        });
        return sorted;
    }

    // ---- file ----

    private File tmpFile() {
        return new File(file.getPath() + ".tmp");
    }

    private boolean write() {
        synchronized (fileLock) {
            return writeLocked();
        }
    }

    /** Writes the current state; caller holds fileLock. Logs and returns false on failure. */
    private boolean writeLocked() {
        StringBuffer b = new StringBuffer();
        synchronized (lock) {
            List sorted = sortedEntries();
            for (int i = 0; i < sorted.size(); i++) {
                Entry e = (Entry) sorted.get(i);
                b.append(e.key).append('=').append(e.pluginId).append('|').append(e.scope.name()).append('|')
                        .append(e.prior).append('|').append(e.active).append('\n');
            }
        }
        File dir = file.getAbsoluteFile().getParentFile();
        File tmp = tmpFile();
        FileOutputStream out = null;
        try {
            if (dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
                throw new IOException("cannot create " + dir);
            }
            out = new FileOutputStream(tmp);
            out.write(b.toString().getBytes("ISO-8859-1"));
            out.getFD().sync();
            out.close();
            out = null;
            if (!tmp.renameTo(file)) {
                // Non-POSIX rename cannot replace: drop the old file; load() recovers from .tmp if we die here.
                file.delete();
                if (!tmp.renameTo(file)) {
                    throw new IOException("cannot rename " + tmp.getName() + " to " + file.getName());
                }
            }
            return true;
        } catch (Throwable t) {
            error("cannot write " + file.getName() + ": " + SdkRuntime.describe(t));
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

    private void load() {
        File f = file;
        if (!f.isFile()) {
            if (!tmpFile().isFile()) {
                return;
            }
            f = tmpFile(); // died between delete and rename: the temp file is complete (fsynced)
            warn("recovering " + file.getName() + " from " + f.getName());
        }
        Properties p = new Properties();
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            p.load(in);
        } catch (Throwable t) {
            File aside = new File(file.getPath() + ".corrupt");
            aside.delete();
            boolean kept = f.renameTo(aside);
            error(f.getName() + " is corrupt, treated as empty" + (kept ? " (kept as " + aside.getName() + ")" : "")
                    + ": " + SdkRuntime.describe(t));
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
        for (Enumeration names = p.propertyNames(); names.hasMoreElements();) {
            String key = (String) names.nextElement();
            Entry e = parse(key, p.getProperty(key));
            if (e == null) {
                error("corrupt ledger line ignored: " + clip(key + "=" + p.getProperty(key)));
            } else {
                entries.add(e);
            }
        }
        if (!entries.isEmpty()) {
            info(entries.size() + " override(s) pending from the previous run");
        }
    }

    /** {@code <pluginId>|<scope>|<prior>|<active>}, or null if malformed. */
    private static Entry parse(String key, String value) {
        if (!isToken(key) || value == null) {
            return null;
        }
        String[] f = new String[4];
        int start = 0;
        for (int i = 0; i < 4; i++) {
            int bar = value.indexOf('|', start);
            if (i < 3 && bar < 0 || i == 3 && bar >= 0) {
                return null;
            }
            f[i] = i < 3 ? value.substring(start, bar) : value.substring(start);
            start = bar + 1;
        }
        OverrideScope scope = null;
        OverrideScope[] all = OverrideScope.values();
        for (int i = 0; i < all.length; i++) {
            if (all[i].name().equals(f[1])) {
                scope = all[i];
            }
        }
        if (!isToken(f[0]) || scope == null || !isToken(f[2]) || !isToken(f[3])) {
            return null;
        }
        Entry e = new Entry(key, f[2]);
        e.pluginId = f[0];
        e.scope = scope;
        e.active = f[3];
        return e;
    }

    private static void requireToken(String what, String s) {
        if (!isToken(s)) {
            throw new IllegalArgumentException(what + " must be 1-" + MAX_TOKEN + " characters of [A-Za-z0-9._-]");
        }
    }

    /** Characters that can never break the line format or need properties escaping. */
    private static boolean isToken(String s) {
        if (s == null || s.length() == 0 || s.length() > MAX_TOKEN) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static String clip(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    // ---- logging ----

    private void info(String msg) {
        append("INFO", msg);
    }

    private void warn(String msg) {
        append("WARN", msg);
    }

    private void error(String msg) {
        append("ERROR", msg);
    }

    private void append(String level, String msg) {
        if (log != null) {
            log.append(level, SOURCE, msg);
        }
    }
}
