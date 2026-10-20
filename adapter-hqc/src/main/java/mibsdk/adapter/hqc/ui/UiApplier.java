package mibsdk.adapter.hqc.ui;

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
 * <p>Each pass waits up to {@link #AWAIT_MS} for a newer snapshot, then:
 * <ul>
 * <li>assigns the Car rows to drawer slots ({@link DrawerSlots}) and swaps {@code SdkGlobals.uiSlots}; row visibility
 * is written through {@code rows} only while {@code SdkGlobals.uiRowModels} exists, and every slot is written again
 * when those models first appear (the drawer is built on HMIEvent, at a time of the HMI's choosing);</li>
 * <li>shows or hides the status line, and re-asserts a non-empty one every {@link #STATUS_REASSERT_MS} on the
 * monotonic clock (stock engineering code also writes model 416);</li>
 * <li>posts one page job when {@code openSeq} or the page content moved past what was last <b>rendered</b>. The job
 * reads the latest snapshot when it runs and records what it rendered; if a job is already pending, the job's
 * after-hook ({@link #wake}) posts again once it is done, so an open request is never lost.</li>
 * </ul>
 * Writes happen only when a value changed. {@link #stop} ends the thread, which tears the SDK UI down on its way out:
 * every row hidden, the status line hidden, and SDK pages reconciled against an empty snapshot (the stopped notice).
 */
public final class UiApplier {

    static final long AWAIT_MS = 1000;
    static final long STATUS_REASSERT_MS = 10000;
    /** stop() waits this long for the thread's teardown: one await period plus margin. */
    static final long STOP_JOIN_MS = 3000;
    private static final String SOURCE = "mibsdk";
    private static final int UNKNOWN = -1;

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
    private DrawerAssignment slots = DrawerAssignment.EMPTY;
    private final int[] rowState = new int[HqcUiIds.ROW_SLOTS]; // UNKNOWN, 0 hidden, 1 visible
    private RowModels seenModels;
    private final Set<String> warnedOverflow = new HashSet<String>();
    private String shownStatus; // null: unknown
    private long statusShownAt;
    private boolean failureLogged;

    // Written by the page job (HMIEvent; one at a time), read by both threads.
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
        });
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

    /** Starts the daemon thread {@code mibsdk-ui} (NORM-1). A second start, or a start after stop, is ignored. */
    public synchronized void start() {
        if (thread != null || stopping) {
            return;
        }
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
                if (!failureLogged) {
                    failureLogged = true;
                    log.append("ERROR", SOURCE, "ui applier pass failed (continuing): " + t);
                }
            }
        }
        try {
            teardown();
        } catch (Throwable t) {
            log.append("ERROR", SOURCE, "ui teardown failed: " + t);
        }
    }

    /** One pass (the thread's body; tests call it directly): wait for a newer snapshot, then apply. */
    void step(long waitMs) throws InterruptedException {
        UiSnapshot s = table.awaitNewer(lastVersion, waitMs);
        if (s != null) {
            lastVersion = s.version();
            current = s;
            assignSlots(s);
        }
        if (current == null) {
            return;
        }
        applyRows();
        applyStatus(current.statusLine());
        postPagesIfNeeded();
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
            if (shownStatus == null || shownStatus.length() > 0) {
                status.hide();
                shownStatus = "";
            }
        } else if (!text.equals(shownStatus) || now - statusShownAt >= STATUS_REASSERT_MS) {
            status.show(text);
            shownStatus = text;
            statusShownAt = now;
        }
    }

    /** Pages as the job should see them: the latest snapshot, or an empty one once stopping (the teardown). */
    private UiSnapshot pageSource() {
        return stopping ? UiSnapshot.EMPTY : table.snapshot();
    }

    private void postPagesIfNeeded() {
        if (needsRender(pageSource())) {
            poster.post(pageJob); // false: a job is pending; its wake() re-checks when it is done
        }
    }

    private boolean needsRender(UiSnapshot s) {
        return isOpenRequest(s) || !samePages(s.pages(), renderedPages);
    }

    private boolean isOpenRequest(UiSnapshot s) {
        return s.openSeq() != renderedOpenSeq && s.openPageKey() != null;
    }

    /** The page job, on HMIEvent: renders the snapshot current now, never the one that triggered the post. */
    private void renderPages() {
        UiSnapshot s = pageSource();
        boolean open = isOpenRequest(s);
        renderedOpenSeq = s.openSeq(); // recorded first: a throwing renderer is not retried in a loop
        renderedPages = s.pages();
        if (open) {
            pages.open(s.openPageKey(), s);
        } else {
            pages.refresh(s);
        }
    }

    private void teardown() {
        SdkGlobals.uiSlots = DrawerAssignment.EMPTY;
        slots = DrawerAssignment.EMPTY;
        applyRows();
        if (shownStatus != null && shownStatus.length() > 0) {
            status.hide();
            shownStatus = "";
        }
        postPagesIfNeeded();
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
