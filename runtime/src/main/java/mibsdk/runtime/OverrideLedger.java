package mibsdk.runtime;

import java.io.File;
import java.io.FileInputStream;
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
 * <p>File: {@code <persist>/ledger.properties}, one line per key,
 * {@code <key>=<pluginId>|<scope>|<prior>|<active>|<state>}, sorted by key, no comments and no wall-clock
 * values (an empty ledger is an empty file). {@code <state>} is {@code A} (held: the override is in force)
 * or {@code R} (restoring: the prior must go back, whoever owns the plugin id). A line of four fields,
 * written before the state existed, loads as {@code A}; writing always produces five. Written with
 * {@link DurableFiles#replace} on every change. The prior is on disk before {@link #acquire} returns and
 * before anything is applied; a release has its {@code R} queued to the file before the first restore
 * attempt, and {@link #shutdown()} writes the final state, so a restore still pending at stop is finished
 * by the next boot (a loaded {@code R} entry is restored as soon as {@link #attach} gives it an applier).
 * An entry leaves the file only once the car has reported its prior (see Confirmation).
 * An unreadable file is treated as empty (logged, and kept aside as {@code ledger.properties.corrupt});
 * a malformed line is skipped (logged).
 *
 * <p>Threads: every {@link Applier#apply} (initial apply, restore, sticky re-apply, retry) runs on the
 * ledger's own daemon thread {@code mibsdk-ledger} (a {@link SerialExecutor}, priority NORM-1), never on a
 * stock thread or a plugin's executor. The public methods only change memory under a private lock and
 * queue work; {@link #acquire} additionally writes the file on the caller's thread, and {@link #shutdown()}
 * once more on its caller's thread. No monitor reachable by
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
 * <p>Confirmation: a restore is complete only when the car reports the prior, never because the write
 * returned (on HQC {@link mibsdk.platform.ParkingIo#current()} only echoes the last write, so it is not
 * read). Each entry counts the reports of its key; a report counts for the restore if it arrived after the
 * entry became restoring, including one that overtakes the return of the write. A write that returns
 * starts a {@link #CONFIRM_TIMEOUT_MS} wait on the Clock: a report of the prior completes the restore; a
 * report of another value, or no report by the timeout, is a failed attempt, unless at the timeout the
 * car's last report of the key is the prior and was made after the override's own write: an ECU need not
 * report a write that leaves its value unchanged, so that completes it too (logged as a warning). A report
 * from before that write (or one that overtook it) does not: the override may have taken with its report
 * lost, so the car may still hold the override value. An observed prior completes a restore
 * whether or not a write was made. Applying an override still counts as done when the write returns.
 *
 * <p>Failures: an apply or restore that throws, and a restore the car does not confirm, is logged and
 * retried with backoff on the Clock (5 s, doubling, capped at 60 s). An apply gives up after 5 attempts
 * (the car keeps its value). A restore is never given up while the runtime runs: it is also retried at once
 * by {@link #retryPendingRestores()} (ignition on, unless a write is awaiting confirmation) and by an
 * {@link #onObserved} of its key that reports a different value than the previous report (repeats wait for
 * the backoff, and reports arriving while such a retry is queued share it). Until it completes the entry
 * stays in the file, marked restoring, and in {@link #active()} with {@link ActiveOverride#restorePending()};
 * one still pending at stop is finished by the next boot.
 *
 * <p>Ignition ({@link #ignitionChanged}): off restores every UNTIL_IGNITION_OFF entry. An UNTIL_IGNITION_OFF
 * override acquired while the ignition was off belongs to the drive that just ended: the next ignition on
 * restores it. The state is unknown (treated as on) until the first event. The R of a restore is on disk
 * before its first write, so a power loss right after ignition off leaves it for the next boot.
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
    /** How long (on the Clock) a returned restore write waits for the car to report the prior. */
    static final long CONFIRM_TIMEOUT_MS = 5000;
    /** File state of an entry whose override is in force. */
    static final String STATE_HELD = "A";
    /** File state of an entry whose prior must be restored. */
    static final String STATE_RESTORING = "R";
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
        boolean acquiredWhileOff; // memory only: taken while the ignition was off (ends at ignition on if UNTIL_IGNITION_OFF)
        int generation; // bumped on every ownership/state change
        String applied; // value last applied successfully, null if unknown
        int failures; // consecutive failed applies/restores for the current generation
        int retrySeq; // only the newest scheduled retry runs
        boolean syncQueued; // an observation-triggered sync is queued and has not started yet
        boolean retryNow; // a changed report asked for an immediate restore write
        // restore confirmation
        int observations; // reports of the key while the entry exists
        int restoringSince; // observations when the entry became restoring
        String lastReport; // the car's last report of the key, of any age (survives state changes)
        int applyMark; // observations when the override value was last written (or acquired): later reports postdate it
        boolean wrote; // a restore write returned since the entry became restoring
        boolean confirming; // a restore write returned; waiting for the car to report the prior
        int confirmFrom; // observations when that write returned
        int confirmSeq; // only the newest confirmation check runs
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
            stopConfirming();
            resetSticky();
        }

        void stopConfirming() {
            confirming = false;
            confirmSeq++;
        }

        /** The car reported the prior since the entry became restoring (it is the latest report). */
        boolean reportedPrior() {
            return observations > restoringSince && prior.equals(lastObserved);
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
    private boolean halted; // guarded by lock; simulated power loss: no file write any more
    private boolean ignitionOff; // guarded by lock; unknown (false) until the first ignition event
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
                e.acquiredWhileOff = ignitionOff;
                e.applyMark = e.observations;
                e.applied = null;
                e.failures = 0;
                e.stopConfirming();
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
        enqueueRestores(keys, "released for " + pluginId);
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
        enqueueRestores(keys, "scope " + scope + " ended");
    }

    /**
     * The ignition changed. Never blocks. Off: every UNTIL_IGNITION_OFF entry is restored. On: an
     * UNTIL_IGNITION_OFF override taken while the ignition was off belonged to the drive that just ended, so it
     * is restored now (before plugins see the event; a plugin may re-acquire on it); then every pending restore
     * is retried (the ECUs are awake). A PERSISTENT override taken while off is unaffected.
     */
    public void ignitionChanged(boolean on) {
        if (!on) {
            synchronized (lock) {
                ignitionOff = true;
            }
            releaseScope(OverrideScope.UNTIL_IGNITION_OFF);
            return;
        }
        List keys = new ArrayList();
        synchronized (lock) {
            ignitionOff = false;
            for (int i = 0; i < entries.size(); i++) {
                Entry e = (Entry) entries.get(i);
                if (!e.restoring && e.scope == OverrideScope.UNTIL_IGNITION_OFF && e.acquiredWhileOff) {
                    markRestoring(e);
                    keys.add(e.key);
                }
            }
        }
        if (!keys.isEmpty()) {
            enqueuePersist(); // the R goes to the file before the restore write
            for (int i = 0; i < keys.size(); i++) {
                info("restoring " + keys.get(i) + ": acquired while the ignition was off; ignition on");
            }
        }
        retryPendingRestores(); // one sync per restoring entry, the ones just marked included
    }

    /** Safe mode / startup: restores every entry left in the file, using {@code applier} (also kept as fallback). */
    public void restorePendingOnBoot(Applier applier) {
        List keys = new ArrayList();
        boolean marked = false;
        synchronized (lock) {
            if (applier != null) {
                fallback = applier;
            }
            for (int i = 0; i < entries.size(); i++) {
                Entry e = (Entry) entries.get(i);
                if (!e.restoring) {
                    markRestoring(e);
                    marked = true;
                }
                keys.add(e.key);
            }
        }
        timer(); // create the ledger thread now, not later on a publishing thread
        if (marked) {
            enqueuePersist();
        }
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
     * sticky re-apply; while a restore is pending a changed value retries the restore at once (a repeated value
     * waits for the backoff; reports while that retry is queued share it), and the prior, or any value while a
     * write awaits confirmation, is passed to the confirmation even if repeated. Never blocks: it only counts
     * the report and queues work (completion and its file write happen on the ledger thread), so the platform
     * host may call it while publishing.
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
            e.observations++; // before any decision: a report that overtakes apply() is counted
            e.lastReport = observedEncoded;
            if (e.restoring) {
                // Retry at once only for news: a changed value, and no observation-triggered sync already
                // queued. An adapter that re-reports the same value after each rejected write would otherwise
                // spin apply/fail/observe; those retries stay on the Clock backoff instead. A report that
                // decides the restore (the prior; anything after a write) reaches sync() even if repeated.
                boolean changed = !observedEncoded.equals(e.lastObserved);
                e.lastObserved = observedEncoded; // sync() treats an observed prior as restored
                if (changed) {
                    e.retryNow = true;
                }
                boolean decides = e.confirming || observedEncoded.equals(e.prior);
                if (!(changed || decides) || e.syncQueued) {
                    return;
                }
                e.syncQueued = true;
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

    /**
     * Waits (at most about {@code maxMs} of real time, in 50 ms slices) until the queued ledger work has run
     * and no restore write is awaiting the car's confirmation. True only if then no restore is pending; false
     * if one is (e.g. waiting for its backoff) or at the deadline: the ledger keeps what is still pending
     * (marked restoring in the file) for the next boot.
     */
    boolean awaitSettled(long maxMs) {
        long slices = maxMs / WAIT_SLICE_MS + 1;
        long used = 0;
        while (true) {
            SerialExecutor w;
            synchronized (lock) {
                w = worker;
            }
            final boolean[] done = {false};
            boolean queued = w != null && w.submit("ledger:idle", new Runnable() {
                public void run() {
                    synchronized (done) {
                        done[0] = true;
                        done.notifyAll();
                    }
                }
            });
            try {
                synchronized (done) {
                    while (queued && !done[0]) {
                        if (used++ >= slices) {
                            return false;
                        }
                        done.wait(WAIT_SLICE_MS);
                    }
                }
                if (!anyConfirming()) {
                    return !anyRestoring();
                }
                if (used++ >= slices) {
                    return false;
                }
                Thread.sleep(WAIT_SLICE_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private boolean anyConfirming() {
        synchronized (lock) {
            for (int i = 0; i < entries.size(); i++) {
                if (((Entry) entries.get(i)).confirming) {
                    return true;
                }
            }
            return false;
        }
    }

    private boolean anyRestoring() {
        synchronized (lock) {
            for (int i = 0; i < entries.size(); i++) {
                if (((Entry) entries.get(i)).restoring) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Stops the ledger thread, then writes the file once on the caller's thread (queued writes are dropped
     * with the thread). Entries not yet restored stay in the file, with their state, for the next boot. A
     * second call does nothing.
     */
    void shutdown() {
        SerialExecutor w;
        synchronized (lock) {
            if (shutdown) {
                return;
            }
            shutdown = true;
            w = worker;
            worker = null;
            timer = null;
        }
        if (w != null) {
            w.shutdown();
        }
        write();
    }

    /**
     * Simulated power loss: stops the ledger thread and its timer with no final write. Nothing is written to
     * the file after this returns (a task already running on the ledger thread may finish its platform call,
     * but not its write). Package-private, for tests.
     */
    void halt() {
        SerialExecutor w;
        synchronized (lock) {
            halted = true;
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
        enqueueRestores(keys, byPlugin ? "released by the plugin" : "the plugin stopped");
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
        e.restoringSince = e.observations; // only reports from now on count for the restore
        e.wrote = false;
        e.retryNow = false;
        e.stopConfirming();
        e.resetSticky();
        e.generation++;
    }

    // ---- ledger thread ----

    /** Entries just marked restoring: their R goes to the file first, then each restore is queued. */
    private void enqueueRestores(List keys, String why) {
        if (keys.isEmpty()) {
            return;
        }
        enqueuePersist();
        enqueueSyncs(keys, why);
    }

    /** Queues one write of the file on the ledger thread (never on the caller's: it may be publishing). */
    private void enqueuePersist() {
        SerialExecutor w = worker();
        if (w != null) {
            w.submit("ledger:persist", new Runnable() {
                public void run() {
                    write();
                }
            });
        }
    }

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

    /**
     * Brings the car in line with the entry: the active value while held, the prior while restoring. A
     * restore is not done when its write returns: it waits for the car's report (see {@link #confirmCheck}).
     */
    private void sync(String key) {
        Entry e;
        String value;
        Applier a;
        int gen;
        boolean restore;
        String refutedBy = null; // the car reported this instead of the prior after a restore write
        boolean writeNow = true; // false: only record the refuted write; the retry waits for the backoff
        synchronized (lock) {
            e = find(key);
            if (e == null) {
                return;
            }
            e.syncQueued = false; // this run serves any queued observation; later ones may queue again
            boolean retryNow = e.retryNow;
            e.retryNow = false;
            restore = e.restoring;
            gen = e.generation;
            if (restore && !e.reportedPrior() && e.confirming) {
                if (e.observations == e.confirmFrom) {
                    return; // the write awaits the car's report; confirmCheck decides at the timeout
                }
                e.stopConfirming();
                refutedBy = e.lastObserved;
                writeNow = retryNow; // a repeated report must not turn into a write per report
            }
            value = restore ? e.prior : e.active;
            if (!restore && value.equals(e.applied)) {
                return; // already in force
            }
            a = e.applier != null ? e.applier : fallback;
        }
        if (refutedBy != null) {
            onNotConfirmed(e, gen, "reported " + refutedBy, writeNow);
            if (!writeNow) {
                return;
            }
        }
        if (restore && finishIfReported(e, gen)) {
            return;
        }
        if (a == null) {
            warn((restore ? "restore of " : "apply of ") + key + " waits: no platform applier yet");
            return;
        }
        Throwable failure = applyQuietly(a, key, value);
        if (!restore) {
            markApplied(e);
        }
        if (failure != null) {
            onSyncFailed(e, gen, restore, value, failure);
            return;
        }
        int confirmSeq = 0;
        String owner;
        synchronized (lock) {
            if (find(key) != e) {
                return;
            }
            owner = e.pluginId;
            if (!restore) {
                e.applied = value;
                e.failures = 0;
            } else if (e.generation == gen && !e.reportedPrior()) {
                e.wrote = true;
                e.confirming = true;
                e.confirmFrom = e.observations;
                confirmSeq = ++e.confirmSeq;
            } else if (e.generation == gen) {
                e.wrote = true; // the report overtook the return of apply(): finished below
            }
        }
        if (!restore) {
            info("applied " + key + "=" + value + " for " + owner);
        } else if (confirmSeq != 0) {
            scheduleConfirmCheck(key, gen, confirmSeq);
        } else {
            finishIfReported(e, gen);
        }
    }

    /**
     * The override value was just written (returned or threw: it may have reached the car). A report that
     * arrived before now, even one that overtook the write, may predate it. Ledger thread.
     */
    private void markApplied(Entry e) {
        synchronized (lock) {
            if (find(e.key) == e) {
                e.applyMark = e.observations;
            }
        }
    }

    /** Completes a restore whose prior the car has reported. Ledger thread. False if it is not done. */
    private boolean finishIfReported(Entry e, int gen) {
        boolean wrote;
        synchronized (lock) {
            if (find(e.key) != e || e.generation != gen || !e.reportedPrior()) {
                return false;
            }
            wrote = e.wrote;
        }
        finishRestore(e, gen, wrote ? "confirmed by the car" : "reported by the car", false);
        return true;
    }

    /** Removes a restored entry and rewrites the file (same generation only). Ledger thread. */
    private void finishRestore(Entry e, int gen, String how, boolean warning) {
        String owner;
        synchronized (lock) {
            if (find(e.key) != e || e.generation != gen) {
                return;
            }
            entries.remove(e);
            e.stopConfirming();
            owner = e.pluginId;
        }
        write();
        String msg = "restored " + e.key + "=" + e.prior + " (" + how + ") (override of " + owner + " ended)";
        if (warning) {
            warn(msg);
        } else {
            info(msg);
        }
    }

    /**
     * {@link #CONFIRM_TIMEOUT_MS} after a restore write returned: done if the car reported the prior (also
     * when its last report, made after the override's own write, is the prior and nothing came after the
     * restore write), else a failed attempt.
     */
    private void confirmCheck(String key, int gen, int seq) {
        Entry e;
        String reason = null;
        boolean byLastReport = false;
        boolean wrote;
        synchronized (lock) {
            e = find(key);
            if (e == null || e.generation != gen || e.confirmSeq != seq || !e.confirming) {
                return;
            }
            e.stopConfirming();
            wrote = e.wrote;
            if (e.reportedPrior()) {
                reason = null;
            } else if (e.observations > e.confirmFrom) {
                reason = "reported " + e.lastObserved;
            } else if (e.prior.equals(e.lastReport) && e.observations > e.applyMark) {
                byLastReport = true; // reported after the override's own write, so the car kept the prior
            } else {
                reason = "no report within " + CONFIRM_TIMEOUT_MS + " ms";
            }
        }
        if (reason != null) {
            onNotConfirmed(e, gen, reason, false);
        } else if (byLastReport) {
            finishRestore(e, gen, "confirmed by the car's last report; none after the write", true);
        } else {
            finishRestore(e, gen, wrote ? "confirmed by the car" : "reported by the car", false);
        }
    }

    private void scheduleConfirmCheck(final String key, final int gen, final int seq) {
        PluginScheduler t = timer();
        if (t != null) {
            t.after(CONFIRM_TIMEOUT_MS, new Runnable() {
                public void run() {
                    confirmCheck(key, gen, seq);
                }
            });
        }
    }

    /**
     * Logs a failed apply/restore and schedules a retry (same generation only). A restore is retried with
     * backoff (5 s doubling, capped at 60 s, on the Clock) for as long as the runtime runs; an apply gives up
     * after MAX_ATTEMPTS.
     */
    private void onSyncFailed(Entry e, int gen, boolean restore, String value, Throwable failure) {
        attemptFailed(e, gen, restore, value, null, SdkRuntime.describe(failure), false);
    }

    /**
     * A restore write returned but the car did not confirm the prior: a failed attempt. {@code retryingNow}:
     * the caller writes the prior again at once, so no backoff retry is scheduled.
     */
    private void onNotConfirmed(Entry e, int gen, String reason, boolean retryingNow) {
        attemptFailed(e, gen, true, e.prior, reason, null, retryingNow);
    }

    private void attemptFailed(Entry e, int gen, boolean restore, String value, String unconfirmed, String cause,
            boolean retryingNow) {
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
        if (retry && !retryingNow) { // before logging: once the line is visible, the retry is already on the Clock
            scheduleSync(e.key, gen, seq, delay);
        }
        String attempt = "(attempt " + attempts + (restore ? "" : "/" + MAX_ATTEMPTS) + ")";
        String what = (restore ? "restore of " + e.key + " to " : "apply of " + e.key + "=") + value + " for " + owner;
        error(what + (unconfirmed != null ? " not confirmed by the car (" + unconfirmed + ") " + attempt
                : " failed " + attempt + ": " + cause)
                + (!retry ? "" : retryingNow ? "; retrying now" : "; retrying in " + delay + " ms"));
        if (!retry && attempts >= MAX_ATTEMPTS) {
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
        markApplied(e);
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
        return DurableFiles.tmpFile(file);
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
            if (halted) {
                return false; // the power is gone
            }
            List sorted = sortedEntries();
            for (int i = 0; i < sorted.size(); i++) {
                Entry e = (Entry) sorted.get(i);
                b.append(e.key).append('=').append(e.pluginId).append('|').append(e.scope.name()).append('|')
                        .append(e.prior).append('|').append(e.active).append('|')
                        .append(e.restoring ? STATE_RESTORING : STATE_HELD).append('\n');
            }
        }
        try {
            DurableFiles.replace(file, b.toString().getBytes("ISO-8859-1"));
            return true;
        } catch (Throwable t) {
            error("cannot write " + file.getName() + ": " + SdkRuntime.describe(t));
            return false;
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

    /**
     * {@code <pluginId>|<scope>|<prior>|<active>|<state>}, or the four fields without a state (an older
     * file: held); null if malformed. A restoring entry is loaded unowned and restoring.
     */
    private static Entry parse(String key, String value) {
        if (!isToken(key) || value == null) {
            return null;
        }
        String[] f = new String[5];
        int n = 0;
        int start = 0;
        while (true) {
            if (n == f.length) {
                return null;
            }
            int bar = value.indexOf('|', start);
            f[n++] = bar < 0 ? value.substring(start) : value.substring(start, bar);
            if (bar < 0) {
                break;
            }
            start = bar + 1;
        }
        boolean restoring;
        if (n == 4 || n == 5 && STATE_HELD.equals(f[4])) {
            restoring = false;
        } else if (n == 5 && STATE_RESTORING.equals(f[4])) {
            restoring = true;
        } else {
            return null;
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
        e.restoring = restoring; // holder stays null: a loaded entry is never held by a handle
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
