package mibsdk.adapter.hqc.ui;

import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mibsdk.adapter.hqc.SdkGlobals;
import mibsdk.platform.UiEntry;
import mibsdk.platform.UiPage;
import mibsdk.platform.UiSnapshot;
import mibsdk.platform.UiTable;
import mibsdk.runtime.Clock;
import mibsdk.runtime.RingLog;

/**
 * Applies the runtime's UI snapshots to the HMI on the SDK thread {@code mibsdk-ui}: Car drawer slots and row
 * visibility, the status line, and SDK pages (through one coalesced {@link HmiPoster} job).
 *
 * <p>Each pass waits up to {@link #AWAIT_MS} for a newer snapshot, then runs four isolated phases (a failure in one is
 * logged once per phase and message, and never starves the others):
 * <ul>
 * <li>slots: assigns the Car rows to drawer slots ({@link DrawerSlots}) and swaps {@code SdkGlobals.uiSlots};</li>
 * <li>rows: writes changed row visibility through {@code rows}, only while {@code SdkGlobals.uiRowModels} exists, and
 * every slot again when those models first appear (the drawer is built on HMIEvent, at a time of the HMI's
 * choosing);</li>
 * <li>status: shows or hides the status line, and re-asserts a non-empty one every {@link #STATUS_REASSERT_MS} on the
 * monotonic clock (stock engineering code also writes model 416). Popup 65 belongs to stock EngineeringApp until the
 * SDK has shown text on it: it is never hidden before that;</li>
 * <li>pages: once the drawer exists (so the first screen is up: before it the stock StartupFilter discards posted
 * jobs), posts one page job when {@code openSeq} or the page content moved past what was last <b>rendered</b>. The job
 * reads the latest snapshot when it runs; if a job is already pending, the job's after-hook ({@link #wake}) posts
 * again once it is done, so an open request is never lost.</li>
 * </ul>
 * Writes happen only when a value changed.
 *
 * <p><b>Down.</b> The runtime publishes {@link UiSnapshot#down} first thing in its stop, and {@link #stop} ends the
 * thread, which applies the same teardown on its way out: every row hidden (the Plugins row too, and
 * {@code SdkGlobals.uiDown} so a drawer built later starts without it), a status line the SDK showed hidden, and an open
 * SDK page closed back to the stock screen it came from. A later snapshot from a running runtime resumes. Only the
 * applier started last owns the HMI: a replaced one posts and renders nothing, and its exit teardown touches nothing.
 */
public final class UiApplier {

    static final long AWAIT_MS = 1000;
    static final long STATUS_REASSERT_MS = 10000;
    /** stop() waits this long for the thread's teardown: one await period plus margin. */
    static final long STOP_JOIN_MS = 3000;
    private static final String SOURCE = "mibsdk";
    private static final int UNKNOWN = -1;
    private static final int MAX_LOGGED_FAILURES = 32;
    private static final String[] PHASES = {"slots", "rows", "status", "pages"};

    /**
     * The generation of the applier that owns the HMI (the last one started); others post and render nothing, and
     * their exit teardown touches nothing. Changed and checked-then-acted-on only under GENERATION_LOCK.
     */
    private static volatile int liveGeneration;
    private static int lastGeneration; // guarded by GENERATION_LOCK
    private static final Object GENERATION_LOCK = new Object();

    private volatile int generation; // 0 until claimLive()
    private final UiTable table;
    private final RowModels rows;
    private final StatusLineSink status;
    private final PageRenderer pages;
    private final HmiPoster poster;
    private final Clock clock;
    private final RingLog log;
    private final Runnable pageJob = new Runnable() {
        public void run() {
            renderPages();
        }
    };

    // mibsdk-ui thread only.
    private long lastVersion = Long.MIN_VALUE;
    private UiSnapshot current;
    private boolean freshSnapshot;
    private DrawerAssignment slots = DrawerAssignment.EMPTY;
    private final int[] rowState = new int[HqcUiIds.ROW_SLOTS]; // UNKNOWN, 0 hidden, 1 visible
    private RowModels seenModels;
    private final Set<String> warnedOverflow = new HashSet<String>();
    private final Set<String> loggedFailures = new HashSet<String>();
    private String shownStatus; // null: the SDK never showed text
    private long statusShownAt;

    // Read by both threads; the page state is written by the page job (HMIEvent; one at a time).
    private volatile boolean down;
    private volatile boolean closedRendered;
    private volatile long renderedOpenSeq;
    private volatile Map<String, UiPage> renderedPages;

    private volatile boolean stopping;
    private Thread thread; // guarded by this

    UiApplier(UiTable t, RowModels rows, StatusLineSink s, PageRenderer pages, HmiPoster p, Clock clock,
              RingLog log) {
        this.table = t;
        this.rows = rows;
        this.status = s;
        this.pages = pages;
        this.poster = p;
        this.clock = clock;
        this.log = log;
        for (int i = 0; i < rowState.length; i++) {
            rowState[i] = UNKNOWN;
        }
    }

    /**
     * The production applier over {@code SdkGlobals}: popup 65 on {@code frameworkAccess} (the
     * {@code de.audi.atip.base.IFrameworkAccess} service), row visibility through {@code SdkGlobals.uiRowModels}, jobs
     * posted to HMIEvent, no page renderer yet. Starts nothing.
     */
    public static UiApplier create(Object frameworkAccess, Clock clock, RingLog log) {
        final UiApplier[] self = new UiApplier[1];
        HmiPoster poster = new HmiPoster(HmiPoster.HMI_EVENT, new Runnable() {
            public void run() {
                UiApplier a = self[0];
                if (a != null) {
                    a.wake();
                }
            }
        }, clock, HmiPoster.PENDING_DEADLINE_MS);
        RowModels rows = new RowModels() {
            public void setVisible(int slot, boolean visible) {
                RowModels m = SdkGlobals.uiRowModels;
                if (m != null) {
                    m.setVisible(slot, visible);
                }
            }
        };
        self[0] = new UiApplier(SdkGlobals.UI, rows, new Popup65StatusLine(frameworkAccess), PageRenderer.NONE,
                poster, clock, log);
        return self[0];
    }

    /**
     * Makes this applier the one that owns the HMI (start() does it; tests that drive {@link #step} by hand call it).
     * Waits for a replaced applier's exit teardown, which runs under the same lock.
     */
    void claimLive() {
        synchronized (GENERATION_LOCK) {
            generation = ++lastGeneration;
            liveGeneration = generation;
        }
    }

    private boolean isLive() {
        return generation != 0 && generation == liveGeneration;
    }

    /**
     * Claims the HMI ({@link #claimLive}) and starts the daemon thread {@code mibsdk-ui} (NORM-1). A second start, or a
     * start after stop, is ignored.
     */
    public synchronized void start() {
        if (thread != null || stopping) {
            return;
        }
        claimLive();
        Thread t = new Thread("mibsdk-ui") {
            public void run() {
                loop();
            }
        };
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        thread = t;
        t.start();
    }

    /**
     * Ends the thread and waits (at most {@link #STOP_JOIN_MS}) for its teardown, so a restarted runtime's applier
     * never races it. Idempotent.
     */
    public void stop() {
        Thread t;
        synchronized (this) {
            stopping = true;
            t = thread;
        }
        if (t == null || t == Thread.currentThread()) {
            return;
        }
        try {
            t.join(STOP_JOIN_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (t.isAlive()) {
            log.append("WARN", SOURCE, "ui applier still tearing down after " + STOP_JOIN_MS + " ms");
        }
    }

    /**
     * HmiPoster's after-job hook, on HMIEvent: posts the next page job if the snapshot moved past what was rendered
     * while the last job was pending. Reads volatile state only; never throws.
     */
    void wake() {
        if (!isLive()) {
            return; // replaced: never post again (a stale job would otherwise re-post itself forever)
        }
        try {
            postPagesIfNeeded();
        } catch (Throwable t) {
            // never propagate into the HMI
        }
    }

    private void loop() {
        while (!stopping) {
            try {
                step(AWAIT_MS);
            } catch (Throwable t) {
                failed("wait", t);
            }
        }
        synchronized (GENERATION_LOCK) { // held through the teardown: no newer applier can claim the HMI meanwhile
            if (isLive()) { // else a newer applier owns the HMI: leave it alone
                enterDown();
                runPhases();
            }
        }
    }

    /** One pass (the thread's body; tests call it directly): wait for a newer snapshot, then the four phases. */
    void step(long waitMs) throws InterruptedException {
        UiSnapshot s = table.awaitNewer(lastVersion, waitMs);
        if (s != null) {
            lastVersion = s.version();
            current = s;
            freshSnapshot = true;
            if (s.isDown()) {
                enterDown();
            } else if (down) {
                down = false;
                SdkGlobals.uiDown = false;
            }
        }
        if (current == null) {
            return;
        }
        runPhases();
    }

    /** Idempotent: the runtime's down snapshot and the thread's exit may both take the UI down. */
    private void enterDown() {
        SdkGlobals.uiDown = true;
        if (down) {
            return;
        }
        down = true;
        closedRendered = false;
        current = UiSnapshot.down(lastVersion);
        freshSnapshot = true;
    }

    private void runPhases() {
        for (int p = 0; p < PHASES.length; p++) {
            try {
                switch (p) {
                    case 0:
                        if (freshSnapshot) {
                            freshSnapshot = false;
                            assignSlots(current);
                        }
                        break;
                    case 1:
                        applyRows();
                        break;
                    case 2:
                        applyStatus(current.statusLine());
                        break;
                    default:
                        postPagesIfNeeded();
                        break;
                }
            } catch (Throwable t) {
                failed(PHASES[p], t);
            }
        }
    }

    /** Logs each distinct (phase, failure) once; bounded. */
    private void failed(String phase, Throwable t) {
        String key = phase + ": " + t;
        if (loggedFailures.size() < MAX_LOGGED_FAILURES && loggedFailures.add(key)) {
            log.append("ERROR", SOURCE, "ui " + key + " (continuing)");
        }
    }

    private void assignSlots(UiSnapshot s) {
        DrawerAssignment a = DrawerSlots.assign(slots, s.carRows(), HqcUiIds.PLUGIN_ROW_SLOTS);
        slots = a;
        SdkGlobals.uiSlots = a;
        List<String> overflow = a.overflow();
        for (int i = 0; i < overflow.size(); i++) {
            String key = overflow.get(i);
            if (warnedOverflow.add(key)) {
                log.append("WARN", SOURCE, "ui car row " + key + " has no drawer slot; reachable from the Plugins menu");
            }
        }
    }

    /** Writes each slot whose visibility changed; all of them once {@code SdkGlobals.uiRowModels} (re)appears. */
    private void applyRows() {
        RowModels m = SdkGlobals.uiRowModels;
        if (m == null) {
            return;
        }
        if (m != seenModels) {
            seenModels = m;
            for (int i = 0; i < rowState.length; i++) {
                rowState[i] = UNKNOWN;
            }
        }
        for (int slot = 0; slot < rowState.length; slot++) {
            int want = slots.visibleAt(slot) ? 1 : 0;
            if (rowState[slot] != want) {
                rows.setVisible(slot, want == 1);
                rowState[slot] = want;
            }
        }
    }

    private void applyStatus(String text) {
        long now = clock.uptimeMs();
        if (text.length() == 0) {
            if (shownStatus != null && shownStatus.length() > 0) { // only ever hide what the SDK showed
                status.hide();
                shownStatus = "";
            }
        } else if (!text.equals(shownStatus) || now - statusShownAt >= STATUS_REASSERT_MS) {
            status.show(text);
            shownStatus = text;
            statusShownAt = now;
        }
    }

    private void postPagesIfNeeded() {
        if (!isLive() || (stopping && !down)) {
            return; // replaced, or stopping before the teardown took the UI down
        }
        if (SdkGlobals.uiRowModels == null) {
            return; // no drawer yet, so no first screen yet: the StartupFilter would discard the job
        }
        if (needsRender(table.snapshot())) {
            poster.post(pageJob); // false: a job is pending; its wake() re-checks when it is done
        }
    }

    private boolean needsRender(UiSnapshot s) {
        if (down) {
            return !closedRendered;
        }
        return isOpenRequest(s) || !samePages(s.pages(), renderedPages);
    }

    private boolean isOpenRequest(UiSnapshot s) {
        return s.openSeq() != renderedOpenSeq && s.openPageKey() != null;
    }

    /**
     * The page job, on HMIEvent: renders the snapshot current now, never the one that triggered the post; once down,
     * closes SDK pages back to the stock screen. A replaced applier's job does nothing.
     */
    private void renderPages() {
        if (!isLive()) {
            return;
        }
        if (down) {
            closedRendered = true; // recorded first: a throwing renderer is not retried in a loop
            renderedPages = Collections.<String, UiPage>emptyMap();
            pages.close();
            return;
        }
        UiSnapshot s = table.snapshot();
        boolean open = isOpenRequest(s);
        renderedOpenSeq = s.openSeq();
        renderedPages = s.pages();
        if (open) {
            pages.open(s.openPageKey(), s);
        } else {
            pages.refresh(s);
        }
    }

    /** Same page keys, titles and entries (UiPage has no equals: the runtime rebuilds them every snapshot). */
    static boolean samePages(Map<String, UiPage> a, Map<String, UiPage> b) {
        if (a == b) {
            return true;
        }
        if (b == null) {
            return a.isEmpty();
        }
        if (a.size() != b.size()) {
            return false;
        }
        for (Iterator<Map.Entry<String, UiPage>> it = a.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, UiPage> e = it.next();
            if (!samePage(e.getValue(), b.get(e.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private static boolean samePage(UiPage x, UiPage y) {
        if (x == y) {
            return true;
        }
        if (x == null || y == null || !eq(x.title(), y.title()) || x.entries().size() != y.entries().size()) {
            return false;
        }
        for (int i = 0; i < x.entries().size(); i++) {
            UiEntry p = x.entries().get(i);
            UiEntry q = y.entries().get(i);
            if (p.kind() != q.kind() || p.on() != q.on() || !eq(p.key(), q.key()) || !eq(p.label(), q.label())
                    || !eq(p.value(), q.value()) || !eq(p.targetPageKey(), q.targetPageKey())) {
                return false;
            }
        }
        return true;
    }

    private static boolean eq(Object x, Object y) {
        return x == null ? y == null : x.equals(y);
    }
}
