package mibsdk.runtime;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import mibsdk.api.Features;
import mibsdk.api.IgnitionChanged;
import mibsdk.api.Listener;
import mibsdk.api.MibPlugin;
import mibsdk.api.OverrideScope;
import mibsdk.api.ParkingSoundChanged;
import mibsdk.api.Permissions;
import mibsdk.api.PluginContext;
import mibsdk.api.Subscription;
import mibsdk.platform.HookTable;
import mibsdk.platform.ParkingIo;
import mibsdk.platform.PlatformAdapter;
import mibsdk.platform.PlatformHost;
import mibsdk.platform.UiInput;
import mibsdk.platform.UiPage;
import mibsdk.platform.UiTable;

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
 * before plugins see the event ({@code IgnitionChanged(true)} likewise ends one acquired while the ignition
 * was off), and {@code ParkingSoundChanged} feeds the ledger's sticky re-apply. A
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
 * {@code IgnitionChanged(false)} that follows an {@code IgnitionChanged(true)} of this run marks a clean shutdown
 * (an adapter's first observation may be "off": that is KL15 off at boot, not a shutdown; the ledger still learns
 * it). Independently of plugins, {@code healthyAfterMs} after
 * {@code start()} the HMI is marked healthy for the configurator ({@link BootGuard#markHmiHealthy}), unless
 * {@code pluginStartDelayMs >= healthyAfterMs}: then the wrap count is cleared in the same write that starts the
 * plugin count, so it is kept until the load and no crash counts nowhere. A boot where every plugin FAILED is not
 * counted as unhealthy (a failed plugin runs no code, so a later HMI crash is not its doing). A {@link HeapWatch} samples free heap every
 * {@code heapCheckMs} once plugins are loaded. With no adapter (null: no adapter supports this HMI version)
 * the runtime logs the STATUS line with {@code adapter=none} and stops at once, leaving the boot guard and
 * the ledger untouched. Once {@link #stop()} is requested no plugin is enabled any more: a delayed or
 * in-flight load bails, and a plugin whose {@code onEnable} was running is disabled again.
 *
 * <p>Features: read in {@code start()} (logged as {@code STATUS mibsdk features=[...]}), again before the plugin
 * load, and whenever the adapter calls {@link PlatformHost#featuresChanged()} (a service that arrived late); then on
 * the control thread, logging {@code STATUS mibsdk features=[...]} again if the set changed. Plugin contexts share one {@link LiveFeatures}, so
 * running plugins see the change at their next {@code has()}, hook registration or {@code vehicle().parking()}.
 * No API event announces it.
 *
 * <p>SDK UI: the runtime owns the Plugins menu, the plugin and SDK pages ({@link PluginsMenu}), the status-line
 * indicators ({@link StatusLine}) and the Plugins row label. It rebuilds them on the control thread after every
 * plugin state change, after a menu action, and every {@link #UI_POLL_MS} ms on the {@link Clock} (the ledger
 * changes on its own thread and has no listener), and pushes to the {@link UiHub} only what differs from the last
 * push. Input on the SDK pages arrives on the UI dispatcher thread, which only maps it to an action and queues that
 * on the control thread: it never blocks and never calls plugin code. The user's on/off choices
 * ({@link UserToggles}) apply at load and reload: a plugin switched off stays DISABLED {@value #OFF_REASON}.
 */
public final class SdkRuntime {

    private static final String SOURCE = "mibsdk";
    private static final String MANIFEST_ENTRY = "plugin.json";
    private static final int MAX_MANIFEST_BYTES = 64 * 1024;
    private static final long WAIT_SLICE_MS = 50;
    /** How often the SDK UI is rebuilt even without a plugin state change (override indicators). */
    static final long UI_POLL_MS = 1000;
    static final String OFF_REASON = "off in Plugins menu";
    static final String SAFE_MODE_REASON = "safe mode: not loaded";
    static final String PLUGINS_LABEL = "Plugins";
    static final String PLUGINS_LABEL_SAFE = "Plugins (safe mode)";
    /**
     * How long stop() waits (real time) for queued restores, and for the car to confirm them, before
     * stopping the adapter: {@link OverrideLedger#CONFIRM_TIMEOUT_MS} + 2 s.
     */
    private static final long LEDGER_DRAIN_MS = OverrideLedger.CONFIRM_TIMEOUT_MS + 2000;

    /** Test hook: simulates an SDK-side failure (e.g. OOM creating a thread) while enabling. */
    interface EnableHook {
        void afterResources(String pluginId);
    }

    static volatile EnableHook testEnableHook;

    /** Test hook: runs on the control thread in stop() just before {@code ledger.beginShutdown()}. */
    static volatile Runnable testBeforeLedgerShutdown;

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
    private final UiTable uiTable;

    private final List hosts = new ArrayList(); // PluginHost in discovery order; guarded by this
    private final List enabledOrder = new ArrayList(); // active PluginHost, oldest enable first; guarded by this
    private volatile Grants grants = Grants.load(null); // re-read by loadAll and reload on the control thread
    /** Read in start(), before the initial plugin load and on featuresChanged() (a service may arrive late). */
    private final LiveFeatures features = new LiveFeatures(Collections.<String>emptySet());
    private final Object featuresLock = new Object(); // serializes read-and-set, so the last read wins
    /** Publishing thread: this run saw IgnitionChanged(true); only an off after that is a clean shutdown. */
    private volatile boolean ignitionOnSeen;
    private SerialExecutor control; // guarded by this
    private boolean started; // guarded by this
    private boolean stopped; // guarded by this
    /** False if the adapter failed to start: then no plugin loads, not even through reload(). */
    private volatile boolean adapterStarted;
    private volatile boolean healthAtLoad; // set once in start(): the wrap count clears with the plugin-load write
    /** Set once in start(), before the STATUS line: no plugin loads, not even through reload(). */
    private volatile boolean safeMode;
    /** Why safe mode: {@link SdkState#REASON_MARKER} etc.; null when not in safe mode. Set once in start(). */
    private volatile String safeReason;
    private volatile String adapterId = "none";
    /** The boot guard was reset from the SDK page; takes effect at the next start. */
    private volatile boolean resetRequested;
    private final UserToggles toggles;
    private final StatusPrefs statusPrefs;
    /** Set on the control thread once the initial plugin load ran; reload() is deferred until then. */
    private volatile boolean pluginsLoaded;
    private PluginScheduler timer; // posts to the control executor; guarded by this
    private final List timers = new ArrayList(); // Subscription; cancelled by stop(); guarded by this
    private DevChannel dev; // guarded by this
    /** Created in start() once the control timer exists; plugin contexts and disableHost use it. */
    private volatile UiHub uiHub;
    private UiDispatcher uiDispatcher; // guarded by this
    private SerialExecutor uiPublisher; // builds and publishes UI snapshots; guarded by this
    private boolean uiRefreshQueued; // guarded by this
    // ---- the last SDK UI pushed to the hub; control thread only ----
    private List<UiPage> lastSdkPages;
    private List<String> lastIndicators;
    private List<String> lastStatusOrder; // control thread
    private Set<String> lastStatusHidden; // control thread
    private String lastPluginsLabel;
    private boolean overflowLogged;

    private final Runnable uiRefresh = new Runnable() {
        public void run() {
            synchronized (SdkRuntime.this) {
                uiRefreshQueued = false;
            }
            refreshUi();
        }
    };

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
        public void featuresChanged() {
            SerialExecutor c = control();
            if (c == null) {
                return;
            }
            c.submit("features", new Runnable() {
                public void run() {
                    if (!isStopped()) {
                        refreshFeatures();
                    }
                }
            });
        }

        @Override
        public void log(String msg, Throwable t) {
            log.append(t == null ? "INFO" : "ERROR", "platform", t == null ? msg : msg + ": " + describe(t));
        }
    };

    /** What plugin contexts ask for the adapter's parking I/O, on their first {@code vehicle().parking()}. */
    private final PluginContextImpl.ParkingSource parkingSource = new PluginContextImpl.ParkingSource() {
        public ParkingIo parkingIo() {
            return SdkRuntime.this.parkingIo();
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
        this(cfg, platform, clock, hooks, new UiTable(64), log);
    }

    /** {@code ui} is where the runtime publishes UI snapshots and takes user input (the adapter's side). */
    public SdkRuntime(RuntimeConfig cfg, PlatformAdapter platform, Clock clock, HookTable hooks, UiTable ui,
            RingLog log) {
        this.cfg = cfg;
        this.uiTable = ui;
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
        this.toggles = new UserToggles(cfg.userTogglesFile(), log);
        this.statusPrefs = new StatusPrefs(cfg.statusPrefsFile(), log);
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
                // Its own thread: the control thread blocks for up to hungMs inside onEnable/onDisable.
                uiPublisher = new SerialExecutor("ui-publish", clock, cfg.slowMs(), cfg.hungMs(), sdkListener("ui"));
                uiHub = new UiHub(uiTable, log, clock, new PluginScheduler(uiPublisher, clock), cfg.uiPublishMinMs());
                uiHub.setStatusMaxChars(cfg.uiStatusMaxChars());
                uiHub.setSdkHandler(sdkInput(control));
                uiDispatcher = new UiDispatcher(uiTable, uiHub);
                c = control;
                t = timer;
            }
        }
        if (c != null) {
            uiHub.publishNow(); // the Plugins row from the start: safe mode, or no plugin UI at all
        }
        if (c == null) {
            log.append("WARN", SOURCE, "no adapter supports this HMI version: the SDK stays off and no plugin loads");
            log.append("STATUS", SOURCE, "runtime started adapter=none safeMode=false");
            log.append("STATUS", SOURCE, "runtime stopped");
            writeLogFile();
            return;
        }
        safeMode = beginBoot();
        logRoots();
        String adapterId = "none";
        ledger.attach(platformApplier); // before the adapter can publish: creates the ledger thread here
        try {
            platform.start(platformHost);
            readFeatures();
            log.append("STATUS", SOURCE, "features=" + sorted(features.snapshot())); // the boot's set; changes log later
            adapterId = platform.id();
            this.adapterId = adapterId;
            adapterStarted = true;
        } catch (Throwable e) {
            log.append("ERROR", SOURCE, "adapter failed to start, no plugins will load: " + describe(e));
        }
        if (adapterStarted) {
            startUiDispatcher(); // also in safe mode: the SDK's own pages take input
        }
        if (safeMode && adapterStarted) {
            ledger.restorePendingOnBoot(platformApplier); // safe mode: no plugin will re-claim anything
        }
        if (safeMode) {
            c.submit("list-plugins", new Runnable() { // the Plugins menu lists them; none is loaded
                public void run() {
                    if (!isStopped()) {
                        listPluginsInSafeMode();
                    }
                }
            });
        }
        log.append("STATUS", SOURCE, "runtime started adapter=" + adapterId + " safeMode=" + safeMode);
        requestUiRefresh(); // safe mode is known now: label, header and SAFE indicator
        schedule(t.every(UI_POLL_MS, new Runnable() {
            public void run() {
                if (!isStopped()) {
                    refreshUi();
                }
            }
        }));
        logHeap();
        healthAtLoad = !safeMode && adapterStarted && cfg.pluginStartDelayMs() >= cfg.healthyAfterMs();
        if (!healthAtLoad) {
            scheduleHmiHealthy(t);
        }
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

    /**
     * STATUS {@code state persist=<dir> ok|UNWRITABLE ram=<dir> ok|UNWRITABLE bootGuard=armed|inert}; when the
     * persist root is writable, STATUS {@code durability persist=<dir> <DurableFiles.probe>}; plus a WARN per
     * unwritable root saying what that costs. The roots are probed with a scratch file; {@code bootGuard} is whether
     * {@link #beginBoot}'s write of boot.state succeeded. Creates the roots' directories when missing. Never throws.
     */
    private void logRoots() {
        try {
            String persist = probeWritable(cfg.roots().persist());
            String ram = probeWritable(cfg.cacheDir());
            boolean inert = bootGuard.inert(); // the boot.state write's own result (beginBoot ran first)
            log.append("STATUS", SOURCE, "state persist=" + cfg.roots().persist() + (persist == null ? " ok" : " UNWRITABLE")
                    + " ram=" + cfg.roots().ram() + (ram == null ? " ok" : " UNWRITABLE") + " bootGuard="
                    + (inert ? "inert" : "armed"));
            if (persist == null) {
                log.append("STATUS", SOURCE, "durability persist=" + cfg.roots().persist() + " "
                        + DurableFiles.probe(cfg.roots().persist(), clock));
            }
            if (persist != null) {
                log.append("WARN", SOURCE, "persistent root " + cfg.roots().persist() + " is not writable (" + persist
                        + "): plugin data and the override ledger may not be saved"
                        + (inert ? "; the boot guard is inert (crash loops are not counted)" : ""));
            } else if (inert) {
                log.append("WARN", SOURCE, "boot guard inert: cannot write " + bootGuard.file()
                        + " (crash loops are not counted)");
            }
            if (ram != null) {
                log.append("WARN", SOURCE, "RAM root " + cfg.roots().ram() + " is not writable (" + ram
                        + "): plugin jars cannot be snapshotted, so no plugin loads");
            }
        } catch (Throwable t) {
            log.append("WARN", SOURCE, "cannot check the state roots: " + describe(t));
        }
    }

    /** Null if a file can be created in {@code dir} (created first if missing); else why not. */
    static String probeWritable(File dir) {
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            return "cannot create " + dir;
        }
        File probe = new File(dir, ".probe");
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(probe);
            out.write('\n');
            out.close();
            out = null;
            return probe.delete() ? null : "cannot delete " + probe;
        } catch (Throwable t) {
            return describe(t);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // the failure is reported
                }
            }
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

    /** Listener for an SDK-internal executor: logs failures, slow and hung tasks; never blocks. */
    private SerialExecutor.Listener sdkListener(final String what) {
        return new SerialExecutor.Listener() {
            public void onFailure(String label, Throwable t) {
                log.append("ERROR", SOURCE, what + " task " + label + " failed: " + describe(t));
            }

            public void onSlow(String label, long ms) {
                log.append("WARN", SOURCE, what + " task " + label + " took " + ms + " ms");
            }

            public void onHung(String label) {
                log.append("ERROR", SOURCE, what + " task " + label + " hung for over " + cfg.hungMs() + " ms");
            }
        };
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
            boolean marker = bootGuard.markerPresent();
            safeReason = !safe ? null : marker ? SdkState.REASON_MARKER : SdkState.REASON_UNHEALTHY_BOOTS;
            if (safe && marker) {
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
            safeReason = SdkState.REASON_BOOT_GUARD_ERROR;
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

    private void startUiDispatcher() {
        UiDispatcher ud;
        synchronized (this) {
            ud = stopped ? null : uiDispatcher;
        }
        if (ud != null) {
            ud.start(); // a no-op if beginStop() stopped it meanwhile
        }
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
        SerialExecutor c = beginStop();
        if (c == null) {
            return;
        }
        UiHub hub = uiHub;
        if (hub != null) {
            try {
                hub.publishDown(); // no SDK UI outlives the runtime: off screen before any plugin is disabled
            } catch (Throwable t) {
                log.append("ERROR", SOURCE, "ui down failed: " + describe(t));
            }
        }
        runOnControl(c, "stop", new Runnable() {
            public void run() {
                Runnable hook = testBeforeLedgerShutdown;
                if (hook != null) {
                    hook.run();
                }
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
        if (!ledger.awaitSettled(LEDGER_DRAIN_MS)) { // restores need the adapter: let them finish first
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

    /**
     * Test hook, simulated power loss: marks the runtime stopped, halts the ledger (no final write) and shuts
     * down the control thread, the dev channel and the hook dispatcher. No plugin is disabled, nothing is
     * drained, the adapter is not stopped and no file is written.
     */
    void haltForTest() {
        SerialExecutor c = beginStop();
        if (c == null) {
            return;
        }
        ledger.halt();
        dispatcher.stop();
        c.shutdown();
    }

    /**
     * First step of stop() and haltForTest(): marks the runtime stopped (from here no plugin is enabled any
     * more, see loadPlugins/enableUnguarded), stops the UI input dispatcher and the UI publisher, cancels the
     * timers and stops the dev channel. Returns the control executor, or null if the runtime was not started or
     * is already stopped.
     */
    private SerialExecutor beginStop() {
        SerialExecutor c;
        Subscription[] subs;
        DevChannel d;
        UiDispatcher ud;
        SerialExecutor up;
        synchronized (this) {
            if (!started || stopped) {
                return null;
            }
            stopped = true;
            c = control;
            subs = (Subscription[]) timers.toArray(new Subscription[timers.size()]);
            timers.clear();
            d = dev;
            dev = null;
            ud = uiDispatcher;
            up = uiPublisher;
        }
        if (ud != null) {
            ud.stop(); // no input reaches a plugin from here on; down before ledger.beginShutdown()
        }
        if (up != null) {
            up.shutdown(); // no more UI publishes
        }
        for (int i = 0; i < subs.length; i++) {
            subs[i].cancel();
        }
        if (d != null) {
            d.stop();
        }
        return c;
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

    /** Test hook: true while the UI input thread is alive. */
    boolean uiDispatcherRunning() {
        UiDispatcher ud;
        synchronized (this) {
            ud = uiDispatcher;
        }
        return ud != null && ud.isRunning();
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
        bootGuard.markPluginsLoading(healthAtLoad); // from here a restart counts as unhealthy (unless the ignition went off)
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
                    logHealth();
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

    /**
     * After healthyAfterMs with plugins loaded: "boot healthy" only if the boot guard could record it and at least one
     * plugin is enabled (F6: never claim health when every plugin failed or the state is unwritable).
     */
    private void logHealth() {
        PluginState[] states;
        synchronized (this) {
            states = new PluginState[hosts.size()];
            for (int i = 0; i < states.length; i++) {
                states[i] = ((PluginHost) hosts.get(i)).state;
            }
        }
        int enabled = 0;
        StringBuffer others = new StringBuffer();
        PluginState[] all = PluginState.values();
        for (int k = 0; k < all.length; k++) {
            int n = 0;
            for (int i = 0; i < states.length; i++) {
                n += states[i] == all[k] ? 1 : 0;
            }
            if (all[k] == PluginState.ENABLED) {
                enabled = n;
            } else if (n > 0) {
                others.append(others.length() == 0 ? "" : " ").append(all[k]).append('=').append(n);
            }
        }
        long ms = cfg.healthyAfterMs();
        if (bootGuard.inert()) {
            log.append("WARN", SOURCE, "boot guard inert: ran " + ms + " ms with " + enabled
                    + " plugin(s) enabled, but cannot write " + bootGuard.file() + "; nothing recorded");
        } else if (enabled == 0) {
            log.append("INFO", SOURCE, "boot ran " + ms + " ms without a restart, but no plugin is enabled ("
                    + (others.length() == 0 ? "none installed" : others.toString())
                    + "); unhealthy-boot count reset: only HMI restarts count, plugin failures are contained");
        } else {
            log.append("INFO", SOURCE, "boot healthy: " + enabled + " plugin(s) enabled for " + ms + " ms");
        }
    }

    /** Control thread: the adapter's features as of now (it may learn of a service after start); logs a change. */
    private void refreshFeatures() {
        try {
            if (readFeatures()) {
                log.append("STATUS", SOURCE, "features=" + sorted(features.snapshot()));
            }
        } catch (Throwable t) {
            log.append("ERROR", SOURCE, "adapter features() failed, keeping " + sorted(features.snapshot()) + ": "
                    + describe(t));
        }
    }

    /** Reads the adapter's features into {@link #features}; true if they changed. Throws what features() throws. */
    private boolean readFeatures() {
        synchronized (featuresLock) {
            return features.set(platform.features());
        }
    }

    private static List sorted(Set<String> s) {
        List out = new ArrayList(s);
        Collections.sort(out);
        return out;
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
        toggles.refresh(); // mibsdk-dev may have edited the user's choices since the last read
        statusPrefs.refresh(installedIds());
        File[] files = pluginJars();
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
                if (toggles.isOff(h.id)) {
                    setState(h, PluginState.DISABLED, OFF_REASON);
                } else {
                    enableIfDependenciesEnabled(h);
                }
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

    /** {@code plugins/*.jar} (regular files), sorted by name. */
    private File[] pluginJars() {
        File[] files = cfg.pluginsDir().listFiles();
        if (files == null) {
            files = new File[0];
        }
        List out = new ArrayList();
        for (int i = 0; i < files.length; i++) {
            if (files[i].getName().endsWith(".jar") && files[i].isFile()) {
                out.add(files[i]);
            }
        }
        File[] jars = (File[]) out.toArray(new File[out.size()]);
        Arrays.sort(jars, new Comparator() {
            public int compare(Object a, Object b) {
                return ((File) a).getName().compareTo(((File) b).getName());
            }
        });
        return jars;
    }

    /**
     * Safe mode, control thread: lists the installed plugins for the Plugins menu from their plugin.json alone (no
     * plugin class is loaded, nothing is enabled); each readable one is DISABLED {@value #SAFE_MODE_REASON}.
     */
    private void listPluginsInSafeMode() {
        grants = Grants.load(cfg.grantsFile());
        toggles.refresh();
        statusPrefs.refresh(installedIds());
        File[] files = pluginJars();
        for (int i = 0; i < files.length && !isStopped(); i++) {
            PluginHost h = discover(files[i]);
            if (addHost(h, null) && h.state == PluginState.DISCOVERED) {
                setState(h, PluginState.DISABLED, SAFE_MODE_REASON);
            }
        }
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
            uiHub.removeOwner(h.id, null);
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
        uiHub.registerOwner(h.id); // its UI sorts in enable order, and stray UI from a previous run is dropped
        h.storage = new PluginStorage(new File(cfg.dataDir(), h.id + ".properties"), clock,
                PluginStorage.DEFAULT_DEBOUNCE_MS, storageQuota);
        h.exec = new SerialExecutor("plugin-" + h.id, clock, cfg.slowMs(), cfg.hungMs(), executorListener(h));
        h.scheduler = new PluginScheduler(h.exec, clock);
        h.ctx = new PluginContextImpl(h.id, log, h.storage, bus, h.exec, h.scheduler, h.registrations, features,
                effectivePermissions(m), hooks, parkingSource, ledger, uiHub, clock, m.icons(),
                cfg.pluginIconDir(h.id));
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
        disableHost(h, state, reason, runOnDisable, true);
    }

    /**
     * As above; {@code notice} false (a reload) removes the plugin's UI silently, else an open page of the
     * plugin turns into the notice {@code "<name> stopped: <reason>"}.
     */
    private void disableHost(final PluginHost h, PluginState state, String reason, boolean runOnDisable,
            boolean notice) {
        synchronized (this) {
            if (!h.active) {
                return;
            }
            h.active = false;
            enabledOrder.remove(h);
        }
        h.registrations.cancelAll(); // releases the plugin's live overrides
        uiHub.removeOwner(h.id, notice ? displayName(h) + " stopped: " + reason : null);
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

    private static String displayName(PluginHost h) {
        String name = h.manifest == null ? null : h.manifest.name();
        return name == null || name.length() == 0 ? h.id : name;
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
        toggles.refresh();
        statusPrefs.refresh(installedIds());
        PluginHost old = findHost(id);
        if (old != null) {
            disableHost(old, PluginState.DISABLED, "reloading", true, false); // no notice flash during a reload
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
            } else if (toggles.isOff(id)) {
                setState(h, PluginState.DISABLED, OFF_REASON);
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
            return features.has(Features.PARKING) ? platform.parking() : null;
        } catch (Throwable t) {
            log.append("ERROR", SOURCE, "adapter parking() failed: " + describe(t));
            return null;
        }
    }

    /**
     * IgnitionChanged(false) after an IgnitionChanged(true) of this run marks a clean shutdown in boot.state,
     * before publish returns: the unit may lose power any time after the ignition goes off. The one write per
     * ignition-off is the only I/O on the publishing thread (adapters publish from their own threads, never from
     * stock ones). An off before any on is the adapter's first observation (KL15 off at boot), not a shutdown:
     * marking it would keep a crash loop with the ignition off from ever counting. Never throws.
     */
    private void noteIgnition(Object event) {
        try {
            if (event instanceof IgnitionChanged) {
                if (((IgnitionChanged) event).isOn()) {
                    ignitionOnSeen = true;
                } else if (ignitionOnSeen) {
                    bootGuard.markCleanShutdown();
                }
            }
        } catch (Throwable t) {
            log.append("ERROR", SOURCE, "cannot record the clean shutdown: " + describe(t));
        }
    }

    /** Feeds platform events to the ledger. Called while publishing; never blocks, never throws. */
    private void feedLedger(Object event) {
        try {
            if (event instanceof IgnitionChanged) {
                // off restores UNTIL_IGNITION_OFF overrides; on restores those acquired while the ignition was off,
                // then retries pending restores
                ledger.ignitionChanged(((IgnitionChanged) event).isOn());
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

    // ---- SDK UI ----

    /**
     * Input on the SDK's pages, on the UI dispatcher thread: maps it to a menu action and queues that on the control
     * thread. Never blocks (a queue add), never calls plugin code, never throws.
     */
    private Listener<UiInput> sdkInput(final SerialExecutor c) {
        return new Listener<UiInput>() {
            public void on(UiInput in) {
                try {
                    final PluginsMenu.Action a = PluginsMenu.handle(in);
                    if (a.kind == PluginsMenu.Action.Kind.NONE) {
                        return;
                    }
                    c.submit("ui:" + a, new Runnable() {
                        public void run() {
                            if (!isStopped()) {
                                applyMenuAction(a);
                            }
                        }
                    });
                } catch (Throwable t) {
                    log.append("ERROR", SOURCE, "ui input failed: " + describe(t));
                }
            }
        };
    }

    /**
     * Control thread. On/off: recorded in {@link UserToggles} first; outside safe mode, on reloads the plugin (also
     * from FAILED or QUARANTINED) and off disables it (a stopped one is marked off). In safe mode only the choice is
     * recorded. Reset: only in count-based safe mode, never under the wrap-loop latch (the UI is off then anyway).
     */
    private void applyMenuAction(PluginsMenu.Action a) {
        try {
            applyMenuActionUnguarded(a);
        } catch (Throwable t) {
            log.append("ERROR", SOURCE, "Plugins menu action " + a + " failed: " + describe(t));
        } finally {
            refreshUi();
        }
    }

    private void applyMenuActionUnguarded(PluginsMenu.Action a) {
        if (a.kind == PluginsMenu.Action.Kind.RESET_BOOT_GUARD) {
            if (!safeMode || bootGuard.markerPresent() || bootGuard.wrapLoopLatched()) {
                log.append("WARN", SOURCE, "boot guard reset ignored: only offered in count-based safe mode");
            } else {
                bootGuard.reset();
                resetRequested = true;
                log.append("INFO", SOURCE, "boot guard reset from the SDK page: takes effect at the next start");
            }
        } else if (a.kind == PluginsMenu.Action.Kind.STATUS_SHOW || a.kind == PluginsMenu.Action.Kind.STATUS_HIDE) {
            boolean hide = a.kind == PluginsMenu.Action.Kind.STATUS_HIDE;
            statusPrefs.setHidden(a.id, hide);
            log.append("INFO", SOURCE, "Plugins menu: " + a.id + " status item " + (hide ? "hidden" : "shown"));
        } else if (a.kind == PluginsMenu.Action.Kind.STATUS_EARLIER || a.kind == PluginsMenu.Action.Kind.STATUS_LATER) {
            boolean earlier = a.kind == PluginsMenu.Action.Kind.STATUS_EARLIER;
            statusPrefs.move(a.id, earlier ? -1 : 1, statusEligible(statuses(), uiHub));
            log.append("INFO", SOURCE, "Plugins menu: " + a.id + " status item moved " + (earlier ? "earlier" : "later")
                    + ", order now " + statusPrefs.order());
        } else if (a.kind == PluginsMenu.Action.Kind.ENABLE || a.kind == PluginsMenu.Action.Kind.DISABLE) {
            boolean off = a.kind == PluginsMenu.Action.Kind.DISABLE;
            toggles.setOff(a.id, off);
            log.append("INFO", SOURCE, "Plugins menu: " + a.id + (off ? " off" : " on")
                    + (safeMode ? " (applies after safe mode ends)" : ""));
            if (!safeMode) {
                PluginHost h = findHost(a.id);
                PluginState s = h == null ? null : stateOf(h);
                if (!off && s != PluginState.ENABLED) {
                    reloadNow(a.id);
                    enableDependentsOf(a.id);
                } else if (off && h != null) {
                    if (s == PluginState.ENABLED) {
                        disableHost(h, PluginState.DISABLED, OFF_REASON, true);
                    } else if (s == PluginState.FAILED || s == PluginState.QUARANTINED) {
                        setState(h, PluginState.DISABLED, OFF_REASON);
                    }
                }
            }
        }
    }

    /**
     * Control thread, after {@code id} was switched on: reloads every plugin BLOCKED_DEPENDENCY on it (and not
     * switched off itself) once {@code id} is ENABLED, then the plugins blocked on those, and so on.
     */
    private void enableDependentsOf(String id) {
        List queue = new ArrayList();
        Set seen = new HashSet();
        queue.add(id);
        seen.add(id);
        while (!queue.isEmpty() && !isStopped()) {
            String dep = (String) queue.remove(0);
            PluginHost d = findHost(dep);
            if (d == null || stateOf(d) != PluginState.ENABLED) {
                continue;
            }
            PluginHost[] all;
            synchronized (this) {
                all = (PluginHost[]) hosts.toArray(new PluginHost[hosts.size()]);
            }
            for (int i = 0; i < all.length; i++) {
                PluginHost h = all[i];
                PluginManifest m = h.manifest;
                if (seen.contains(h.id) || stateOf(h) != PluginState.BLOCKED_DEPENDENCY || m == null
                        || !m.dependencies().contains(dep) || toggles.isOff(h.id)) {
                    continue;
                }
                seen.add(h.id);
                log.append("INFO", SOURCE, "dependency " + dep + " is back: reloading " + h.id);
                reloadNow(h.id);
                queue.add(h.id);
            }
        }
    }

    /** The ids of the plugin jars installed now ({@code <id>.jar}). */
    private Set<String> installedIds() {
        Set<String> out = new HashSet<String>();
        File[] files = pluginJars();
        for (int i = 0; i < files.length; i++) {
            String name = files[i].getName();
            if (name.endsWith(".jar")) {
                out.add(name.substring(0, name.length() - ".jar".length()));
            }
        }
        return out;
    }

    /** Every host's manifest (absent for an unreadable plugin.json), by plugin id. */
    private Map<String, PluginManifest> manifestsById() {
        Map<String, PluginManifest> manifests = new HashMap<String, PluginManifest>();
        synchronized (this) {
            for (int i = 0; i < hosts.size(); i++) {
                PluginHost h = (PluginHost) hosts.get(i);
                if (h.manifest != null) {
                    manifests.put(h.id, h.manifest);
                }
            }
        }
        return manifests;
    }

    /**
     * The plugins whose page gets the status-item rows: those that declare and were granted
     * {@code ui.slot.statusbar}, and any that has an item now.
     */
    private Set<String> statusEligible(List<PluginStatus> st, UiHub hub) {
        Set<String> withItem = hub == null ? new HashSet<String>() : hub.statusOwners();
        Map<String, PluginManifest> manifests = manifestsById();
        Grants g = grants;
        Set<String> out = new HashSet<String>();
        for (int i = 0; i < st.size(); i++) {
            String id = st.get(i).id();
            PluginManifest m = manifests.get(id);
            boolean permitted = m != null && m.permissions().contains(Permissions.UI_SLOT_STATUS_BAR)
                    && g.granted(id).contains(Permissions.UI_SLOT_STATUS_BAR);
            if (permitted || withItem.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    /** Queues one {@link #refreshUi} on the control thread; a queued one absorbs this request. */
    private void requestUiRefresh() {
        SerialExecutor c;
        synchronized (this) {
            if (uiRefreshQueued || control == null || stopped) {
                return;
            }
            uiRefreshQueued = true;
            c = control;
        }
        if (!c.submit("ui-refresh", uiRefresh)) {
            synchronized (this) {
                uiRefreshQueued = false;
            }
        }
    }

    /** Control thread: rebuilds the SDK pages, indicators and Plugins row label; pushes only what changed. */
    private void refreshUi() {
        UiHub hub = uiHub;
        if (hub == null || isStopped()) {
            return;
        }
        try {
            List<PluginStatus> st = statuses();
            Map<String, PluginManifest> manifests = manifestsById();
            Map<String, List<String>> pageKeys = new HashMap<String, List<String>>();
            Map<String, String> titles = new HashMap<String, String>();
            for (int i = 0; i < st.size(); i++) {
                String id = st.get(i).id();
                List<String> keys = hub.pageKeysOf(id);
                pageKeys.put(id, keys);
                for (int k = 0; k < keys.size(); k++) {
                    String title = hub.pageTitle(keys.get(k));
                    if (title != null) {
                        titles.put(keys.get(k), title);
                    }
                }
            }
            SdkState s = new SdkState(safeMode, safeReason, bootGuard.inert(), resetRequested, adapterId,
                    ledger.active());
            int hidden = PluginsMenu.rootOverflow(st.size(), s);
            if (hidden > 0 && !overflowLogged) {
                overflowLogged = true;
                log.append("WARN", SOURCE, st.size() + " plugins installed: the Plugins page lists the first "
                        + (st.size() - hidden) + " (+" + hidden + " more)");
            }
            List<String> statusOrder = statusPrefs.order();
            Set<String> statusHidden = statusPrefs.hiddenIds();
            PluginsMenu.StatusChoices sc = new PluginsMenu.StatusChoices(
                    StatusLine.order(statusOrder, statusEligible(st, hub)), statusHidden);
            List<UiPage> pages = PluginsMenu.pages(st, manifests, pageKeys, titles, toggles.offIds(), s, grants, sc);
            List<String> indicators = StatusLine.indicators(s);
            String label = safeMode ? PLUGINS_LABEL_SAFE : PLUGINS_LABEL;
            if (!PluginsMenu.samePages(pages, lastSdkPages)) {
                lastSdkPages = pages;
                hub.setSdkPages(pages);
            }
            if (!statusOrder.equals(lastStatusOrder) || !statusHidden.equals(lastStatusHidden)) {
                lastStatusOrder = statusOrder;
                lastStatusHidden = statusHidden;
                hub.setStatusPrefs(statusOrder, statusHidden);
            }
            if (!indicators.equals(lastIndicators)) {
                lastIndicators = indicators;
                hub.setIndicators(indicators);
            }
            if (!label.equals(lastPluginsLabel)) {
                lastPluginsLabel = label;
                hub.setPluginsRowLabel(label);
            }
        } catch (Throwable t) {
            log.append("ERROR", SOURCE, "SDK UI not refreshed: " + describe(t));
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
        requestUiRefresh();
        return true;
    }

    private void setState(PluginHost h, PluginState state, String reason) {
        synchronized (this) {
            h.state = state;
            h.reason = reason;
        }
        log.append("STATUS", h.id, state + " " + reason);
        requestUiRefresh();
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
