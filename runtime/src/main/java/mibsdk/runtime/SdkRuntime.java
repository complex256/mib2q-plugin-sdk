package mibsdk.runtime;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import mibsdk.api.Features;
import mibsdk.api.IgnitionChanged;
import mibsdk.api.MibPlugin;
import mibsdk.api.OverrideScope;
import mibsdk.api.ParkingSoundChanged;
import mibsdk.api.PluginContext;
import mibsdk.api.Subscription;
import mibsdk.platform.HookTable;
import mibsdk.platform.ParkingIo;
import mibsdk.platform.PlatformAdapter;
import mibsdk.platform.PlatformHost;

/**
 * Discovers, orders, enables and contains plugins.
 *
 * <p>Threads: every lifecycle step (load, enable, disable, quarantine, reload, stop) runs on one runtime
 * control thread, so steps never interleave. Plugin code (class init, constructor, {@code onEnable},
 * {@code onDisable}, callbacks) runs only on the plugin's own {@link SerialExecutor}; the control thread
 * waits for it, but never longer than until the watchdog reports the executor hung (hungMs on the
 * {@link Clock}), with a backstop of about 2 x hungMs of real time in case the watchdog is gone.
 * {@link #start()}, {@link #stop()} and {@link #reload} block their caller until the step is done, but
 * never longer than about 2 x hungMs of real time; after that the step keeps running on the control
 * thread and the caller is released (logged as a WARN).
 *
 * <p>Locking: the control thread never locks an object plugin code can reach (see
 * {@link PluginContextImpl}), so a plugin holding a monitor cannot wedge the lifecycle.
 *
 * <p>Containment: an exception while loading or in {@code onEnable} gives FAILED; quarantineFailures
 * callback failures within quarantineWindowMs, or one hung callback, give QUARANTINED. Disable always
 * cancels the plugin's registrations, flushes its storage, runs {@code onDisable} on its executor
 * (skipped when the executor is hung; abandoned if it hangs) and shuts the executor down.
 *
 * <p>Permissions: {@code grants.properties} is read when plugins are loaded and again on every
 * {@link #reload}; a plugin whose manifest asks for a permission that is not granted is
 * {@code BLOCKED_PERMISSIONS} and never runs. Hooks are declarative (see {@link HooksImpl}); a
 * {@link HookDispatcher} thread forwards observations to the plugins' executors. A plugin's effective
 * permissions are those its manifest declares that are also granted; a granted but undeclared one is unusable.
 *
 * <p>Overrides: writes to persistent vehicle state go through the {@link OverrideLedger}
 * ({@code <persist>/ledger.properties}), which applies and restores on its own {@code mibsdk-ledger}
 * thread. The runtime restores a plugin's overrides when it is disabled, quarantined, revoked (reload with
 * fewer grants) or uninstalled; {@code IgnitionChanged(false)} restores every UNTIL_IGNITION_OFF override
 * before plugins see the event, and {@code ParkingSoundChanged} feeds the ledger's sticky re-apply. A
 * failed restore is retried with backoff for as long as the runtime runs, and at once on
 * {@code IgnitionChanged(true)} or any parking observation; until it succeeds the entry stays in
 * {@link #activeOverrides()} with {@code restorePending()}. At
 * start, before plugins load: UNTIL_IGNITION_OFF entries left by a run that ended without a clean stop
 * are restored (the ignition may have been off since), as are entries whose plugin jar is gone; after
 * loading (and after every reload), entries whose plugin is not ENABLED are restored. {@link #stop()}
 * restores UNTIL_IGNITION_OFF overrides but keeps PERSISTENT ones in the ledger for the next boot (where
 * the plugin re-claims them with their original prior, or they are restored as above), unless the plugin
 * releases them itself in {@code onDisable}.
 *
 * <p>Boot: {@link #start()} first asks the {@link BootGuard} ({@code boot.state}, {@code SAFE_MODE}) whether
 * this is a safe-mode boot. Safe mode: the adapter starts, every pending ledger entry is restored
 * ({@link OverrideLedger#restorePendingOnBoot}), the {@link DevChannel} runs, and no plugin is discovered or
 * loaded, not even through {@link #reload}. Otherwise stale ledger entries (UNTIL_IGNITION_OFF left by a
 * crash, entries of uninstalled plugins) are restored at once, and plugins load {@code pluginStartDelayMs}
 * later on the {@link Clock} (scheduled on the control thread; {@code start()} does not wait for it; 0 loads
 * during {@code start()}); a {@link #reload} before that load is deferred (logged; the load takes the
 * current jar). The boot counts toward safe mode only from the start of the plugin load
 * ({@link BootGuard#markPluginsLoading}), and {@code healthyAfterMs} after loading it is marked healthy. Every
 * {@code IgnitionChanged(false)} marks a clean shutdown. Independently of plugins, {@code healthyAfterMs} after
 * {@code start()} the HMI is marked healthy for the configurator ({@link BootGuard#markHmiHealthy}). A {@link HeapWatch} samples free heap every
 * {@code heapCheckMs} once plugins are loaded. With no adapter (null: no adapter supports this HMI version)
 * the runtime logs the STATUS line with {@code adapter=none} and stops at once, leaving the boot guard and
 * the ledger untouched. Once {@link #stop()} is requested no plugin is enabled any more: a delayed or
 * in-flight load bails, and a plugin whose {@code onEnable} was running is disabled again.
 */
public final class SdkRuntime {

    private static final String SOURCE = "mibsdk";
    private static final String MANIFEST_ENTRY = "plugin.json";
    private static final int MAX_MANIFEST_BYTES = 64 * 1024;
    private static final long WAIT_SLICE_MS = 50;
    /** How long stop() waits (real time) for queued restores before stopping the adapter. */
    private static final long LEDGER_DRAIN_MS = 5000;

    /** Test hook: simulates an SDK-side failure (e.g. OOM creating a thread) while enabling. */
    interface EnableHook {
        void afterResources(String pluginId);
    }

    static volatile EnableHook testEnableHook;

    /** Test hook: simulates an SDK-side failure at the start of the initial plugin load. */
    static volatile Runnable testBeforeLoadAll;

    private static Method onEnableMethod;
    private static Method onDisableMethod;

    /** Outcome of one task handed to another thread. */
    private static final class Completion {
        private boolean done; // guarded by this
        private String failure; // guarded by this; null on success

        synchronized void complete(String failureOrNull) {
            done = true;
            failure = failureOrNull;
            notifyAll();
        }

        synchronized boolean isDone() {
            return done;
        }

        synchronized String failure() {
            return failure;
        }
    }

    private final RuntimeConfig cfg;
    private final PlatformAdapter platform;
    private final Clock clock;
    private final HookTable hooks;
    private final RingLog log;
    private final EventBus bus = new EventBus();
    private final HookDispatcher dispatcher;
    private final PluginJarCache jarCache;
    private final ClassLoader pluginParent;
    private final OverrideLedger ledger;
    private final BootGuard bootGuard;
    private final StorageQuota storageQuota;

    private final List hosts = new ArrayList(); // PluginHost in discovery order; guarded by this
    private final List enabledOrder = new ArrayList(); // active PluginHost, oldest enable first; guarded by this
    private volatile Grants grants = Grants.load(null); // re-read by loadAll and reload on the control thread
    /** Read in start() and again before the initial plugin load (a hook service may be wrapped in between). */
    private volatile Set<String> features = Collections.emptySet();
    private SerialExecutor control; // guarded by this
    private boolean started; // guarded by this
    private boolean stopped; // guarded by this
    /** False if the adapter failed to start: then no plugin loads, not even through reload(). */
    private volatile boolean adapterStarted;
    /** Set once in start(), before the STATUS line: no plugin loads, not even through reload(). */
    private volatile boolean safeMode;
    /** Set on the control thread once the initial plugin load ran; reload() is deferred until then. */
    private volatile boolean pluginsLoaded;
    private PluginScheduler timer; // posts to the control executor; guarded by this
    private final List timers = new ArrayList(); // Subscription; cancelled by stop(); guarded by this
    private DevChannel dev; // guarded by this

    /** Quarantines the most recently enabled plugin; HeapWatch calls it on the control thread. */
    private final HeapWatch.Victim heapVictim = new HeapWatch.Victim() {
        public boolean anyEnabled() {
            synchronized (SdkRuntime.this) {
                return !enabledOrder.isEmpty();
            }
        }

        public void quarantineMostRecentlyEnabled(String reason) {
            PluginHost h;
            synchronized (SdkRuntime.this) {
                h = enabledOrder.isEmpty() ? null : (PluginHost) enabledOrder.get(enabledOrder.size() - 1);
            }
            if (h == null) {
                return;
            }
            log.append("WARN", SOURCE, "free heap below " + cfg.heapLowBytes() + " bytes for " + cfg.heapLowChecks()
                    + " checks: quarantining " + h.id + ", the most recently enabled plugin");
            disableHost(h, PluginState.QUARANTINED, reason, true);
        }
    };

    private final PlatformHost platformHost = new PlatformHost() {
        @Override
        public void publish(Object apiEvent) {
            noteIgnition(apiEvent);
            feedLedger(apiEvent); // before plugins see it: ignition off restores first
            bus.publish(apiEvent);
        }

        @Override
        public HookTable hooks() {
            return hooks;
        }

        @Override
        public void log(String msg, Throwable t) {
            log.append(t == null ? "INFO" : "ERROR", "platform", t == null ? msg : msg + ": " + describe(t));
        }
    };

    /** Applies ledger values through the adapter; used for entries loaded from the ledger file. */
    private final OverrideLedger.Applier platformApplier = new OverrideLedger.Applier() {
        public void apply(String key, String encodedValue) throws Exception {
            ParkingIo io = platform.parking();
            if (io == null) {
                throw new UnsupportedOperationException("the adapter has no parking I/O");
            }
            ParkingImpl.applier(io).apply(key, encodedValue);
        }
    };

    public SdkRuntime(RuntimeConfig cfg, PlatformAdapter platform, Clock clock, HookTable hooks, RingLog log) {
        this.cfg = cfg;
        this.platform = platform;
        this.clock = clock;
        this.hooks = hooks;
        this.log = log;
        this.dispatcher = new HookDispatcher(hooks, bus);
        this.jarCache = new PluginJarCache(cfg.cacheDir());
        this.pluginParent = new FilteringParentLoader(SdkRuntime.class.getClassLoader(),
                RuntimeConstants.PLUGIN_PARENT_PREFIXES);
        log.setClock(clock);
        this.ledger = new OverrideLedger(cfg.ledgerFile(), clock, log);
        this.bootGuard = new BootGuard(cfg.bootStateFile(), cfg.safeModeMarker(), cfg.safeModeAfterUnhealthyBoots(),
                log);
        this.storageQuota = new StorageQuota(cfg.dataDir(), cfg.storagePluginMaxBytes(), cfg.storageTotalMaxBytes());
    }

    /**
     * Boot guard, adapter, then (unless safe mode) plugins: during this call if pluginStartDelayMs is 0
     * (blocks until loaded, bounded as documented above), else scheduled on the Clock. Only the first call
     * counts. A null adapter logs {@code adapter=none} and stops the runtime.
     */
    public void start() {
        SerialExecutor c;
        PluginScheduler t;
        synchronized (this) {
            if (started) {
                return;
            }
            started = true;
            if (platform == null) {
                stopped = true; // nothing to run: stop() and reload() become no-ops
                c = null;
                t = null;
            } else {
                control = newControlExecutor();
                timer = new PluginScheduler(control, clock);
                c = control;
                t = timer;
            }
        }
        if (c == null) {
            log.append("WARN", SOURCE, "no adapter supports this HMI version: the SDK stays off and no plugin loads");
            log.append("STATUS", SOURCE, "runtime started adapter=none safeMode=false");
            log.append("STATUS", SOURCE, "runtime stopped");
            writeLogFile();
            return;
        }
        safeMode = beginBoot();
        String adapterId = "none";
        ledger.attach(platformApplier); // before the adapter can publish: creates the ledger thread here
        try {
            platform.start(platformHost);
            features = Collections.unmodifiableSet(new HashSet<String>(platform.features()));
            adapterId = platform.id();
            adapterStarted = true;
        } catch (Throwable e) {
            log.append("ERROR", SOURCE, "adapter failed to start, no plugins will load: " + describe(e));
        }
        if (safeMode && adapterStarted) {
            ledger.restorePendingOnBoot(platformApplier); // safe mode: no plugin will re-claim anything
        }
        log.append("STATUS", SOURCE, "runtime started adapter=" + adapterId + " safeMode=" + safeMode);
        logHeap();
        scheduleHmiHealthy(t);
        startDevChannel();
        if (!adapterStarted || safeMode) {
            return;
        }
        c.submit("restore-stale", new Runnable() { // now, not after the start delay: e.g. "rear PDC muted"
            public void run() {
                if (!isStopped()) {
                    restoreStaleOverrides(); // idempotent; loadAll runs it again
                }
            }
        });
        dispatcher.start();
        Runnable load = new Runnable() {
            public void run() {
                loadPlugins();
            }
        };
        long delay = cfg.pluginStartDelayMs();
        if (delay == 0) {
            runOnControl(c, "load", load);
        } else {
            log.append("INFO", SOURCE, "plugins load in " + delay + " ms");
            schedule(t.after(delay, load));
        }
    }

    /** The free-heap baseline, once per start (the bench measures it). Never throws. */
    private void logHeap() {
        try {
            log.append("STATUS", SOURCE, HeapWatch.statusLine());
        } catch (Throwable t) {
            log.append("WARN", SOURCE, "cannot read the heap: " + describe(t));
        }
    }

    /**
     * healthyAfterMs after this start, plugins or not (safe mode, adapter failure): the HMI survived the wrapped
     * services, so the configurator's wrap-boot count is cleared ({@link BootGuard#markHmiHealthy}).
     */
    private void scheduleHmiHealthy(PluginScheduler t) {
        schedule(t.after(cfg.healthyAfterMs(), new Runnable() {
            public void run() {
                if (!isStopped()) {
                    bootGuard.markHmiHealthy();
                }
            }
        }));
    }

    private SerialExecutor newControlExecutor() {
        return new SerialExecutor("runtime", clock, Long.MAX_VALUE, Long.MAX_VALUE, new SerialExecutor.Listener() {
            public void onFailure(String label, Throwable t) {
                log.append("ERROR", SOURCE, "runtime task " + label + " failed: " + describe(t));
            }

            public void onSlow(String label, long ms) {
            }

            public void onHung(String label) {
            }
        }, false);
    }

    /** {@link BootGuard#beginBoot()} plus the logged reason; true means safe mode. Never throws. */
    private boolean beginBoot() {
        try {
            boolean safe = bootGuard.beginBoot();
            int n = bootGuard.unhealthyBoots();
            if (safe && bootGuard.markerPresent()) {
                log.append("WARN", SOURCE, "safeMode=true reason=marker (SAFE_MODE present; no plugin loads)");
            }
            if (safe && n >= cfg.safeModeAfterUnhealthyBoots()) {
                log.append("WARN", SOURCE, "safeMode=true reason=unhealthy-boots n=" + n + " (threshold "
                        + cfg.safeModeAfterUnhealthyBoots() + "; no plugin loads until the boot state is reset)");
            }
            if (bootGuard.wrapLoopLatched()) {
                log.append("WARN", SOURCE, "wrapping=off reason=wrap-boots n=" + bootGuard.wrapBoots() + " (threshold "
                        + cfg.safeModeAfterUnhealthyBoots() + " boots died before the HMI ran healthy; no DSI service"
                        + " is hooked until the boot state is reset: mibsdk-dev safe-mode off)");
            }
            return safe;
        } catch (Throwable e) { // cannot count crashes: do not risk a boot loop
            log.append("ERROR", SOURCE, "safeMode=true reason=boot-guard-error: " + describe(e));
            return true;
        }
    }

    private void startDevChannel() {
        DevChannel d = new DevChannel(this, cfg.pluginsDir(), cfg.logFile(), cfg.devPollMs(), clock);
        synchronized (this) {
            if (stopped) {
                return;
            }
            dev = d;
        }
        d.start();
    }

    /** Keeps a timer subscription for stop() to cancel (or cancels it at once if already stopping). */
    private void schedule(Subscription sub) {
        synchronized (this) {
            if (!stopped) {
                timers.add(sub);
                return;
            }
        }
        sub.cancel();
    }

    /** Disables every plugin (most recently enabled first), stops the adapter. Blocks until done; idempotent. */
    public void stop() {
        SerialExecutor c;
        Subscription[] subs;
        DevChannel d;
        synchronized (this) {
            if (!started || stopped) {
                return;
            }
            stopped = true; // from here no plugin is enabled any more (see loadPlugins/enableUnguarded)
            c = control;
            subs = (Subscription[]) timers.toArray(new Subscription[timers.size()]);
            timers.clear();
            d = dev;
            dev = null;
        }
        for (int i = 0; i < subs.length; i++) {
            subs[i].cancel();
        }
        if (d != null) {
            d.stop();
        }
        runOnControl(c, "stop", new Runnable() {
            public void run() {
                ledger.beginShutdown(); // from here PERSISTENT overrides stay in the ledger for the next boot
                PluginHost[] active;
                synchronized (SdkRuntime.this) {
                    active = (PluginHost[]) enabledOrder.toArray(new PluginHost[enabledOrder.size()]);
                }
                for (int i = active.length - 1; i >= 0; i--) {
                    disableHost(active[i], PluginState.DISABLED, "runtime stopped", true);
                }
            }
        });
        if (!ledger.awaitIdle(LEDGER_DRAIN_MS)) { // restores need the adapter: let them finish first
            log.append("WARN", SOURCE, "override restores still pending at stop; the ledger keeps them for the next boot");
        }
        dispatcher.stop();
        try {
            platform.stop();
        } catch (Throwable t) {
            log.append("ERROR", SOURCE, "adapter stop failed: " + describe(t));
        }
        c.shutdown();
        ledger.shutdown();
        log.append("STATUS", SOURCE, "runtime stopped");
        writeLogFile();
    }

    /** Best effort: the log file holds the last lines even after the dev channel stopped. */
    private void writeLogFile() {
        try {
            log.writeTo(cfg.logFile());
        } catch (Throwable ignored) {
            // the dev channel logs write failures; nothing more to do here
        }
    }

    /** Disables the plugin (if enabled), snapshots {@code plugins/<id>.jar} again and enables it. Blocks. */
    public void reload(final String pluginId) {
        runOnControl(control(), "reload:" + pluginId, new Runnable() {
            public void run() {
                reloadNow(pluginId);
            }
        });
    }

    /** Disables one plugin with the given reason. Blocks. (Package-private until the Plugins menu needs it.) */
    void disable(final String pluginId, final String reason) {
        runOnControl(control(), "disable:" + pluginId, new Runnable() {
            public void run() {
                PluginHost h = findHost(pluginId);
                if (h != null) {
                    disableHost(h, PluginState.DISABLED, reason, true);
                }
            }
        });
    }

    /** One entry per discovered jar, in discovery order. */
    public synchronized List<PluginStatus> statuses() {
        List<PluginStatus> out = new ArrayList<PluginStatus>(hosts.size());
        for (int i = 0; i < hosts.size(); i++) {
            PluginHost h = (PluginHost) hosts.get(i);
            out.add(new PluginStatus(h.id, h.version(), h.state, h.reason));
        }
        return out;
    }

    /** True if this boot is in safe mode (SDK on, no plugins); fixed by start(). */
    public boolean safeMode() {
        return safeMode;
    }

    /** True once stop() has been requested (also after a start() without adapter). */
    synchronized boolean isStopped() {
        return stopped;
    }

    public RingLog log() {
        return log;
    }

    /** Every override of vehicle state currently in force, sorted by key (for the indicator UI). */
    public List<ActiveOverride> activeOverrides() {
        return ledger.active();
    }

    /** The override ledger ({@code restorePendingOnBoot} runs on it at a safe-mode start). */
    OverrideLedger ledger() {
        return ledger;
    }

    /** Test hook: live event subscriptions across all plugins. */
    int eventSubscriberCount() {
        return bus.subscriberCount();
    }

    // ---- control thread ----

    /** Loads every plugin, then starts the health timer and the heap watch. Bails if stop() was requested. */
    private void loadPlugins() {
        if (isStopped()) {
            return;
        }
        bootGuard.markPluginsLoading(); // from here a restart counts as unhealthy (unless the ignition went off)
        boolean done;
        try {
            done = loadAll();
        } finally {
            // reload() is serviced from now on (same control thread, so never mid-load), even after an SDK-side
            // failure in the load: otherwise every later reload would be deferred for the rest of the run
            pluginsLoaded = true;
        }
        if (!done) {
            return;
        }
        PluginScheduler t;
        synchronized (this) {
            t = timer;
        }
        schedule(t.after(cfg.healthyAfterMs(), new Runnable() {
            public void run() {
                if (!isStopped()) {
                    bootGuard.markHealthy();
                    log.append("INFO", SOURCE, "boot healthy: plugins enabled for " + cfg.healthyAfterMs() + " ms");
                }
            }
        }));
        if (cfg.heapLowBytes() > 0) {
            final HeapWatch heap = new HeapWatch(HeapWatch.runtimeMemory(), cfg.heapLowBytes(), cfg.heapLowChecks(),
                    heapVictim);
            schedule(t.every(cfg.heapCheckMs(), new Runnable() {
                public void run() {
                    if (!isStopped()) {
                        heap.check();
                    }
                }
            }));
        }
    }

    /** Control thread: the adapter's features as of now (it may learn of a wrapped service after start). */
    private void refreshFeatures() {
        try {
            features = Collections.unmodifiableSet(new HashSet<String>(platform.features()));
        } catch (Throwable t) {
            log.append("ERROR", SOURCE, "adapter features() failed, keeping " + features + ": " + describe(t));
        }
    }

    /** False if it bailed because stop() was requested (then the stop step owns the ledger and the plugins). */
    private boolean loadAll() {
        Runnable hook = testBeforeLoadAll;
        if (hook != null) {
            hook.run();
        }
        refreshFeatures();
        grants = Grants.load(cfg.grantsFile());
        purgeSnapshots(); // stale copies from a previous run
        restoreStaleOverrides();
        File[] files = cfg.pluginsDir().listFiles();
        if (files == null) {
            files = new File[0];
        }
        Arrays.sort(files, new Comparator() {
            public int compare(Object a, Object b) {
                return ((File) a).getName().compareTo(((File) b).getName());
            }
        });
        List pending = new ArrayList(); // PluginHost, DISCOVERED
        for (int i = 0; i < files.length; i++) {
            if (!files[i].getName().endsWith(".jar") || !files[i].isFile()) {
                continue;
            }
            PluginHost h = discover(files[i]);
            if (!addHost(h, null)) {
                continue;
            }
            if (h.state == PluginState.DISCOVERED) {
                pending.add(h);
            }
        }
        List order = resolveOrder(pending);
        for (int i = 0; i < order.size(); i++) {
            PluginHost h = (PluginHost) order.get(i);
            if (isStopped()) {
                log.append("INFO", SOURCE, "stop requested: plugin loading abandoned");
                return false;
            }
            try {
                enableIfDependenciesEnabled(h);
            } catch (Throwable t) { // one plugin's SDK-side failure must not stop the others loading
                abortEnable(h, "runtime error while enabling: " + describe(t));
            }
        }
        if (isStopped()) {
            return false; // PERSISTENT overrides of plugins not loaded stay for the next boot
        }
        restoreOrphanedOverrides();
        purgeSnapshots();
        return true;
    }

    /** Topological order of {@code pending}; blocks the ones with a missing, blocked or cyclic dependency. */
    private List resolveOrder(List pending) {
        List order = new ArrayList();
        Set resolved = new HashSet(); // ids
        boolean progress = true;
        while (progress) {
            progress = false;
            for (Iterator it = pending.iterator(); it.hasNext();) {
                PluginHost h = (PluginHost) it.next();
                String blocker = null;
                boolean ready = true;
                List<String> deps = h.manifest.dependencies();
                for (int i = 0; i < deps.size() && blocker == null; i++) {
                    String dep = (String) deps.get(i);
                    PluginHost d = findHost(dep);
                    if (d == null) {
                        blocker = "missing dependency " + dep;
                    } else if (isBlocked(stateOf(d))) {
                        blocker = "dependency " + dep + " is " + stateOf(d);
                    } else if (!resolved.contains(dep)) {
                        ready = false;
                    }
                }
                if (blocker != null) {
                    setState(h, PluginState.BLOCKED_DEPENDENCY, blocker);
                } else if (ready) {
                    order.add(h);
                    resolved.add(h.id);
                } else {
                    continue;
                }
                it.remove();
                progress = true;
            }
        }
        for (int i = 0; i < pending.size(); i++) {
            setState((PluginHost) pending.get(i), PluginState.BLOCKED_DEPENDENCY, "dependency cycle");
        }
        return order;
    }

    private void enableIfDependenciesEnabled(PluginHost h) {
        List<String> deps = h.manifest.dependencies();
        for (int i = 0; i < deps.size(); i++) {
            String dep = (String) deps.get(i);
            PluginHost d = findHost(dep);
            PluginState s = d == null ? null : stateOf(d);
            if (s != PluginState.ENABLED) {
                setState(h, PluginState.BLOCKED_DEPENDENCY,
                        d == null ? "missing dependency " + dep : "dependency " + dep + " is " + s);
                return;
            }
        }
        enable(h);
    }

    private void enable(PluginHost h) {
        try {
            enableUnguarded(h);
        } catch (Throwable t) { // SDK-side failure (e.g. OOM creating the executor thread)
            abortEnable(h, "runtime error while enabling: " + describe(t));
        }
    }

    /** Releases whatever {@link #enableUnguarded} created so far and records FAILED. Never throws. */
    private void abortEnable(PluginHost h, String reason) {
        try {
            synchronized (this) {
                h.active = false;
                enabledOrder.remove(h);
            }
            if (h.registrations != null) {
                h.registrations.cancelAll();
            }
            ledger.releaseAllFor(h.id);
            if (h.exec != null) {
                h.exec.shutdown();
            }
            h.plugin = null;
            if (h.snapshot != null) {
                h.snapshot.delete();
            }
            setState(h, PluginState.FAILED, reason);
        } catch (Throwable ignored) {
            // nothing more can be done for this plugin; the caller moves on
        }
    }

    private void enableUnguarded(final PluginHost h) {
        if (isStopped()) {
            return;
        }
        final ClassLoader loader;
        final PluginManifest m;
        File snap = null;
        try {
            snap = jarCache.snapshot(h.jar, h.id);
            m = readManifest(snap); // load exactly the bytes we validate
            if (!h.id.equals(m.id())) {
                throw new ManifestException("plugin.json id changed to " + m.id() + " while loading");
            }
            loader = PluginClassLoaders.create(snap, pluginParent);
        } catch (Throwable t) {
            if (snap != null) {
                snap.delete();
            }
            setState(h, t instanceof ManifestException ? PluginState.BLOCKED_INVALID : PluginState.FAILED,
                    "cannot load jar: " + describe(t));
            return;
        }
        String missing = missingPermissions(m); // the snapshot is what runs, so check what it asks for
        if (missing != null) {
            snap.delete();
            setState(h, PluginState.BLOCKED_PERMISSIONS, missing);
            return;
        }
        h.manifest = m;
        h.snapshot = snap;
        h.failures = new FailureTracker(cfg.quarantineFailures(), cfg.quarantineWindowMs(), clock);
        h.registrations = new Registrations();
        h.storage = new PluginStorage(new File(cfg.dataDir(), h.id + ".properties"), clock,
                PluginStorage.DEFAULT_DEBOUNCE_MS, storageQuota);
        h.exec = new SerialExecutor("plugin-" + h.id, clock, cfg.slowMs(), cfg.hungMs(), executorListener(h));
        h.scheduler = new PluginScheduler(h.exec, clock);
        h.ctx = new PluginContextImpl(h.id, log, h.storage, bus, h.exec, h.scheduler, h.registrations, features,
                effectivePermissions(m), hooks, parkingIo(), ledger);
        EnableHook hook = testEnableHook;
        if (hook != null) {
            hook.afterResources(h.id);
        }
        synchronized (this) {
            h.active = true;
        }
        final Completion done = new Completion();
        h.exec.submit("enable:" + h.id, new Runnable() {
            public void run() {
                try {
                    Class<?> c = Class.forName(m.main(), true, loader);
                    if (!MibPlugin.class.isAssignableFrom(c)) {
                        throw new ClassCastException(m.main() + " does not extend " + MibPlugin.class.getName());
                    }
                    MibPlugin p = (MibPlugin) c.newInstance();
                    h.plugin = p;
                    invoke(onEnable(), p, new Object[] {h.ctx});
                    done.complete(null);
                } catch (Throwable t) {
                    done.complete(describe(unwrap(t)));
                }
            }
        });
        if (!await(h, done)) {
            disableHost(h, PluginState.QUARANTINED, "hung in onEnable", false);
        } else if (done.failure() != null) {
            disableHost(h, PluginState.FAILED, done.failure(), true);
        } else if (isStopped()) { // stop() was requested while onEnable ran: undo it as the stop step would,
            ledger.beginShutdown(); // which first keeps PERSISTENT overrides in the ledger for the next boot
            disableHost(h, PluginState.DISABLED, "runtime stopped", true);
        } else {
            synchronized (this) {
                if (!h.active) {
                    return; // cannot happen: only the control thread disables
                }
                enabledOrder.add(h);
            }
            setState(h, PluginState.ENABLED, "enabled");
        }
    }

    /**
     * Cancels registrations, flushes storage, runs onDisable (unless hung), shuts the executor down, then
     * records the state; an onDisable that hangs turns the state into QUARANTINED "hung in onDisable".
     * Does nothing if the host is not active (already disabled).
     */
    private void disableHost(final PluginHost h, PluginState state, String reason, boolean runOnDisable) {
        synchronized (this) {
            if (!h.active) {
                return;
            }
            h.active = false;
            enabledOrder.remove(h);
        }
        h.registrations.cancelAll(); // releases the plugin's live overrides
        ledger.releaseAllFor(h.id); // and any it owns from a previous run
        h.storage.flushNow();
        final MibPlugin p = h.plugin;
        if (runOnDisable && p != null && !h.hung) {
            final Completion done = new Completion();
            boolean submitted = h.exec.submit("disable:" + h.id, new Runnable() {
                public void run() {
                    try {
                        invoke(onDisable(), p, new Object[0]);
                        done.complete(null);
                    } catch (Throwable t) {
                        done.complete(describe(unwrap(t)));
                    }
                }
            });
            if (submitted && !await(h, done)) {
                log.append("WARN", h.id, "onDisable did not finish within " + cfg.hungMs() + " ms; abandoned");
                state = PluginState.QUARANTINED;
                reason = "hung in onDisable";
            } else if (submitted && done.failure() != null) {
                log.append("ERROR", h.id, "onDisable failed: " + done.failure());
            }
        }
        h.exec.shutdown();
        h.storage.flushNow(); // onDisable may have written state
        h.plugin = null;
        setState(h, state, reason);
    }

    private void reloadNow(String id) {
        if (isStopped()) {
            return;
        }
        if (safeMode) {
            log.append("WARN", SOURCE, "reload ignored: safe mode (no plugin loads until safe mode is cleared)");
            return;
        }
        if (!adapterStarted) {
            log.append("WARN", SOURCE, "reload ignored: the adapter did not start, so no plugin may load");
            return;
        }
        if (!pluginsLoaded) { // the initial load snapshots the current jar anyway
            log.append("INFO", SOURCE, "reload deferred: plugins not loaded yet");
            return;
        }
        if (!ManifestParser.isValidId(id)) {
            log.append("WARN", SOURCE, "reload ignored: invalid plugin id");
            return;
        }
        grants = Grants.load(cfg.grantsFile());
        PluginHost old = findHost(id);
        if (old != null) {
            disableHost(old, PluginState.DISABLED, "reloading", true);
        }
        File jar = new File(cfg.pluginsDir(), id + ".jar");
        if (!jar.isFile()) {
            if (old != null) {
                setState(old, PluginState.DISABLED, "plugin jar not found");
            }
            restoreOrphanedOverrides();
            purgeSnapshots();
            return;
        }
        PluginHost h = discover(jar);
        if (!addHost(h, old)) {
            return;
        }
        if (h.state == PluginState.DISCOVERED) {
            List deps = h.manifest.dependencies();
            if (deps.contains(id)) {
                setState(h, PluginState.BLOCKED_DEPENDENCY, "dependency cycle");
            } else {
                enableIfDependenciesEnabled(h);
            }
        }
        restoreOrphanedOverrides();
        purgeSnapshots();
    }

    /** The manifest's permissions that are also granted: what the plugin may actually use. */
    private Set<String> effectivePermissions(PluginManifest m) {
        Set<String> granted = grants.granted(m.id());
        List<String> declared = m.permissions();
        Set<String> out = new HashSet<String>();
        for (int i = 0; i < declared.size(); i++) {
            String p = (String) declared.get(i);
            if (granted.contains(p)) {
                out.add(p);
            }
        }
        return Collections.unmodifiableSet(out);
    }

    /** At start, before plugins load: restores ledger entries that must not survive into this run. */
    private void restoreStaleOverrides() {
        List<ActiveOverride> left = ledger.active();
        boolean untilIgnitionOff = false;
        for (int i = 0; i < left.size(); i++) {
            ActiveOverride o = (ActiveOverride) left.get(i);
            untilIgnitionOff |= !o.restorePending() && o.scope() == OverrideScope.UNTIL_IGNITION_OFF;
        }
        if (untilIgnitionOff) {
            log.append("INFO", SOURCE, "restoring UNTIL_IGNITION_OFF overrides left by the previous run");
            ledger.releaseScope(OverrideScope.UNTIL_IGNITION_OFF);
        }
        left = ledger.active();
        for (int i = 0; i < left.size(); i++) {
            ActiveOverride o = (ActiveOverride) left.get(i);
            if (!o.restorePending() && !new File(cfg.pluginsDir(), o.pluginId() + ".jar").isFile()) {
                log.append("INFO", SOURCE, "restoring " + o.key() + " of " + o.pluginId() + ": plugin is not installed");
                ledger.releaseAllFor(o.pluginId());
            }
        }
    }

    /** Restores ledger entries whose plugin is not running (blocked, failed, revoked, removed). */
    private void restoreOrphanedOverrides() {
        List<ActiveOverride> all = ledger.active();
        for (int i = 0; i < all.size(); i++) {
            ActiveOverride o = (ActiveOverride) all.get(i);
            PluginHost h = findHost(o.pluginId());
            PluginState s = h == null ? null : stateOf(h);
            if (!o.restorePending() && s != PluginState.ENABLED) {
                log.append("INFO", SOURCE, "restoring " + o.key() + " of " + o.pluginId() + ": plugin is "
                        + (s == null ? "not installed" : s.toString()));
                ledger.releaseAllFor(o.pluginId());
            }
        }
    }

    /** The adapter's parking I/O, or null (no Features.PARKING, or the adapter failed). */
    private ParkingIo parkingIo() {
        try {
            return features.contains(Features.PARKING) ? platform.parking() : null;
        } catch (Throwable t) {
            log.append("ERROR", SOURCE, "adapter parking() failed: " + describe(t));
            return null;
        }
    }

    /**
     * IgnitionChanged(false) marks a clean shutdown in boot.state, before publish returns: the unit may lose
     * power any time after the ignition goes off. The one write per ignition-off is the only I/O on the
     * publishing thread (adapters publish from their own threads, never from stock ones). Never throws.
     */
    private void noteIgnition(Object event) {
        try {
            if (event instanceof IgnitionChanged && !((IgnitionChanged) event).isOn()) {
                bootGuard.markCleanShutdown();
            }
        } catch (Throwable t) {
            log.append("ERROR", SOURCE, "cannot record the clean shutdown: " + describe(t));
        }
    }

    /** Feeds platform events to the ledger. Called while publishing; never blocks, never throws. */
    private void feedLedger(Object event) {
        try {
            if (event instanceof IgnitionChanged) {
                if (((IgnitionChanged) event).isOn()) {
                    ledger.retryPendingRestores(); // the ECUs are awake: a failed restore should go through now
                } else {
                    ledger.releaseScope(OverrideScope.UNTIL_IGNITION_OFF);
                }
            } else if (event instanceof ParkingSoundChanged && ((ParkingSoundChanged) event).value() != null) {
                ledger.onObserved(ParkingImpl.KEY, ParkingImpl.encode(((ParkingSoundChanged) event).value()));
            }
        } catch (Throwable t) {
            log.append("ERROR", SOURCE, "override ledger could not process " + event.getClass().getName() + ": "
                    + describe(t));
        }
    }

    /** "missing permissions: a, b" when the manifest asks for permissions that are not granted, else null. */
    private String missingPermissions(PluginManifest m) {
        Set<String> granted = grants.granted(m.id());
        List<String> wanted = m.permissions();
        List missing = new ArrayList();
        for (int i = 0; i < wanted.size(); i++) {
            String p = (String) wanted.get(i);
            if (!granted.contains(p) && !missing.contains(p)) {
                missing.add(p);
            }
        }
        if (missing.isEmpty()) {
            return null;
        }
        StringBuffer b = new StringBuffer("missing permissions: ");
        for (int i = 0; i < missing.size(); i++) {
            b.append(i > 0 ? ", " : "").append((String) missing.get(i));
        }
        return b.toString();
    }

    /** Reads plugin.json; the host is DISCOVERED, or BLOCKED_INVALID with the reason. */
    private PluginHost discover(File jar) {
        String base = jar.getName().substring(0, jar.getName().length() - ".jar".length());
        try {
            PluginManifest m = readManifest(jar);
            if (!jar.getName().equals(m.id() + ".jar")) {
                return new PluginHost(safeLabel(base), jar, null, PluginState.BLOCKED_INVALID,
                        "jar of plugin " + m.id() + " must be named " + m.id() + ".jar");
            }
            String missing = missingPermissions(m);
            if (missing != null) {
                return new PluginHost(m.id(), jar, m, PluginState.BLOCKED_PERMISSIONS, missing);
            }
            return new PluginHost(m.id(), jar, m, PluginState.DISCOVERED, "discovered");
        } catch (Throwable t) {
            return new PluginHost(safeLabel(base), jar, null, PluginState.BLOCKED_INVALID, describe(t));
        }
    }

    private static PluginManifest readManifest(File jar) throws IOException, ManifestException {
        ZipFile zip = new ZipFile(jar);
        try {
            ZipEntry e = zip.getEntry(MANIFEST_ENTRY);
            if (e == null) {
                throw new ManifestException(MANIFEST_ENTRY + " missing from jar root");
            }
            InputStream in = zip.getInputStream(e);
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    if (out.size() > MAX_MANIFEST_BYTES) {
                        throw new ManifestException(MANIFEST_ENTRY + " larger than " + MAX_MANIFEST_BYTES + " bytes");
                    }
                }
                return ManifestParser.parse(new String(out.toByteArray(), "UTF-8"));
            } finally {
                in.close();
            }
        } finally {
            zip.close();
        }
    }

    // ---- executor callbacks (plugin worker / watchdog threads; never block) ----

    private SerialExecutor.Listener executorListener(final PluginHost h) {
        return new SerialExecutor.Listener() {
            public void onFailure(String label, Throwable t) {
                String what = describe(t);
                log.append("ERROR", h.id, "callback " + label + " failed: " + what);
                if (h.failures.recordFailure()) {
                    requestQuarantine(h, cfg.quarantineFailures() + " failures within " + cfg.quarantineWindowMs()
                            + " ms, last: " + what);
                }
            }

            public void onSlow(String label, long ms) {
                log.append("WARN", h.id, "callback " + label + " took " + ms + " ms");
            }

            public void onHung(String label) {
                h.hung = true;
                log.append("ERROR", h.id, "callback " + label + " hung for over " + cfg.hungMs() + " ms");
                requestQuarantine(h, "hung in " + label);
            }
        };
    }

    private void requestQuarantine(final PluginHost h, final String reason) {
        SerialExecutor c = control();
        if (c == null) {
            return;
        }
        c.submit("quarantine:" + h.id, new Runnable() {
            public void run() {
                disableHost(h, PluginState.QUARANTINED, reason, true);
            }
        });
    }

    // ---- helpers ----

    private synchronized SerialExecutor control() {
        return control;
    }

    /**
     * Runs {@code r} on the control thread and waits for it, at most about 2 x hungMs of real time (counted
     * wait slices, not wall-clock deltas); returns at once if the runtime is stopped.
     */
    private void runOnControl(SerialExecutor c, String label, final Runnable r) {
        if (c == null) {
            return;
        }
        final Completion done = new Completion();
        boolean submitted = c.submit(label, new Runnable() {
            public void run() {
                try {
                    r.run();
                } finally {
                    done.complete(null);
                }
            }
        });
        if (!submitted) {
            return;
        }
        long slices = 2 * cfg.hungMs() / WAIT_SLICE_MS + 1;
        synchronized (done) {
            for (long i = 0; !done.isDone() && i < slices; i++) {
                try {
                    done.wait(WAIT_SLICE_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (done.isDone()) {
                return;
            }
        }
        log.append("WARN", SOURCE, "runtime step " + label + " still running after about " + 2 * cfg.hungMs()
                + " ms; caller released");
    }

    /**
     * Waits for a task on the plugin's executor. True when it completed; false when the executor was reported
     * hung, or (backstop, should the watchdog be gone) about 2 x hungMs of real time passed.
     */
    private boolean await(PluginHost h, Completion done) {
        long slices = 2 * cfg.hungMs() / WAIT_SLICE_MS + 1;
        synchronized (done) {
            for (long i = 0; !done.isDone() && !h.hung && i < slices; i++) {
                try {
                    done.wait(WAIT_SLICE_MS);
                } catch (InterruptedException e) {
                    break;
                }
            }
            if (done.isDone()) {
                return true;
            }
        }
        h.hung = true;
        return false;
    }

    /** Records a discovered host; false (logged) if another host already has its id and is not being replaced. */
    private synchronized boolean addHost(PluginHost h, PluginHost replacing) {
        int i = replacing == null ? -1 : hosts.indexOf(replacing);
        if (i >= 0) {
            hosts.set(i, h);
        } else if (findHost(h.id) != null) {
            log.append("WARN", SOURCE, "plugin " + h.id + " is already known; the second copy is ignored");
            return false;
        } else {
            hosts.add(h);
        }
        log.append("STATUS", h.id, h.state + " " + h.reason);
        return true;
    }

    private void setState(PluginHost h, PluginState state, String reason) {
        synchronized (this) {
            h.state = state;
            h.reason = reason;
        }
        log.append("STATUS", h.id, state + " " + reason);
    }

    private synchronized PluginState stateOf(PluginHost h) {
        return h.state;
    }

    private synchronized PluginHost findHost(String id) {
        for (int i = 0; i < hosts.size(); i++) {
            PluginHost h = (PluginHost) hosts.get(i);
            if (h.id.equals(id)) {
                return h;
            }
        }
        return null;
    }

    private static boolean isBlocked(PluginState s) {
        return s == PluginState.BLOCKED_INVALID || s == PluginState.BLOCKED_PERMISSIONS
                || s == PluginState.BLOCKED_DEPENDENCY;
    }

    /** Deletes cached jar snapshots that no active plugin is loaded from. */
    private void purgeSnapshots() {
        Set keep = new HashSet();
        synchronized (this) {
            for (int i = 0; i < enabledOrder.size(); i++) {
                File f = ((PluginHost) enabledOrder.get(i)).snapshot;
                if (f != null) {
                    keep.add(f);
                }
            }
        }
        jarCache.purgeExcept(keep);
    }

    /** A jar base name made safe for a log/status label (it is never used as a path or URL). */
    private static String safeLabel(String s) {
        StringBuffer b = new StringBuffer(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.';
            b.append(ok ? c : '_');
        }
        return b.toString();
    }

    /** One-line description of a throwable, with its cause and top frame. Never throws. */
    static String describe(Throwable t) {
        try {
            String s = String.valueOf(t);
            Throwable cause = t.getCause();
            if (cause != null && cause != t) {
                s = s + " caused by " + cause;
            }
            StackTraceElement[] st = t.getStackTrace();
            if (st != null && st.length > 0) {
                s = s + " at " + st[0];
            }
            return s;
        } catch (Throwable e) {
            return t == null ? "null" : t.getClass().getName();
        }
    }

    private static Throwable unwrap(Throwable t) {
        if (t instanceof InvocationTargetException && ((InvocationTargetException) t).getTargetException() != null) {
            return ((InvocationTargetException) t).getTargetException();
        }
        return t;
    }

    private static void invoke(Method m, Object target, Object[] args) throws Exception {
        m.invoke(target, args);
    }

    /** MibPlugin's lifecycle methods are protected; the runtime calls them reflectively (controller ruling R3). */
    private static synchronized Method onEnable() throws NoSuchMethodException {
        if (onEnableMethod == null) {
            Method m = MibPlugin.class.getDeclaredMethod("onEnable", new Class[] {PluginContext.class});
            m.setAccessible(true);
            onEnableMethod = m;
        }
        return onEnableMethod;
    }

    private static synchronized Method onDisable() throws NoSuchMethodException {
        if (onDisableMethod == null) {
            Method m = MibPlugin.class.getDeclaredMethod("onDisable", new Class[0]);
            m.setAccessible(true);
            onDisableMethod = m;
        }
        return onDisableMethod;
    }
}
