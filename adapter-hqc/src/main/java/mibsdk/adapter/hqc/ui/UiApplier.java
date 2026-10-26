package mibsdk.adapter.hqc.ui;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mibsdk.adapter.hqc.SdkGlobals;
import mibsdk.platform.UiEntry;
import mibsdk.platform.UiIcon;
import mibsdk.platform.UiPage;
import mibsdk.platform.UiRow;
import mibsdk.platform.UiSnapshot;
import mibsdk.platform.UiStatusPart;
import mibsdk.platform.UiTable;
import mibsdk.runtime.Clock;
import mibsdk.runtime.RingLog;

/**
 * Applies the runtime's UI snapshots to the HMI on the SDK thread {@code mibsdk-ui}: Car drawer slots and row
 * visibility and icons, the status line, and SDK pages (through one combined {@link HmiPoster} job).
 *
 * <p>Each pass waits up to {@link #AWAIT_MS} for a newer snapshot, then runs four isolated phases (a failure in one is
 * logged once per phase and message, and never starves the others):
 * <ul>
 * <li>slots: assigns the Car rows to drawer slots ({@link DrawerSlots}, which gives their icons image ids from
 * {@code ids}), swaps {@code SdkGlobals.uiSlots}, and publishes the wanted {@link HmiWidgetState}; gives the page
 * items' icons their image ids ({@link #assignItemIcons}) and publishes them to the page renderer;</li>
 * <li>rows: writes changed row visibility through {@code rows}, only while {@code SdkGlobals.uiRowModels} exists, and
 * every slot again when those models first appear (the drawer is built on HMIEvent, at a time of the HMI's
 * choosing). Slot 0, the Plugins row, is wanted visible only while the main menu has no Plugins row of its own
 * ({@code SdkGlobals.drawerPluginsRowWanted()}: the takeover is not ACTIVE), evaluated on every pass, so a main
 * menu that fails after the drawer hid its row brings the row back within {@link #AWAIT_MS}. This phase also
 * publishes the main menu's Plugins row into the wanted {@link HmiWidgetState}: visible iff not down and the main
 * menu is ACTIVE;</li>
 * <li>status: shows or hides the status line ({@link StatusSinks}: the SDK popup {@link HqcUiIds#POPUP_SDK_STATUS}
 * with icons, popup 65 as text only and only as the fallback), and re-asserts a non-empty one every
 * {@link #STATUS_REASSERT_MS} on the monotonic clock (on popup 65, stock engineering code also writes model 416). It
 * shows again when the line's parts changed, or when the sink changed since the last show, so a fallback to popup 65
 * reaches it within one pass. It publishes the line's {@link StatusLayout} into the wanted {@link HmiWidgetState} (the
 * popup's bounds and built-in icons); an empty line (down included) publishes {@link StatusLayout#EMPTY}, so the
 * popup's id icon cells are cleared. Popup 65 belongs to stock EngineeringApp until the SDK has shown text on it: it
 * is never hidden before that;</li>
 * <li>hmi: once the drawer, the status popup or the main menu's Plugins row exists (so the first screen is up: before
 * it the stock StartupFilter discards posted jobs), posts the one HMI job when {@code openSeq} or the page content
 * moved past what was last <b>rendered</b> (the item icons' ids included), or a page asked for navigation
 * ({@link PageRenderer#navPending}, F2: the job always runs it), or the wanted {@link HmiWidgetState} differs from
 * the one
 * {@link HmiWidgets} applied (its status layout only once the popup is built; the main-menu row against the row it
 * was applied to), or, on the pass only, a new main wizard still waits for its wrench (it may not be connected
 * yet). The job re-evaluates both when it
 * runs (pages from the latest snapshot), each part isolated; if a job is already pending, the job's after-hook
 * ({@link #wake}) posts again once it is done, so an open request is never lost.</li>
 * </ul>
 * Writes happen only when a value changed.
 *
 * <p><b>Down.</b> The runtime publishes {@link UiSnapshot#down} first thing in its stop, and {@link #stop} ends the
 * thread, which applies the same teardown on its way out: every row hidden (the Plugins row too, and
 * {@code SdkGlobals.uiDown} so a drawer built later starts without it, and the main menu's Plugins row), a status line
 * the SDK showed hidden, and an open
 * SDK page closed back to the stock screen it came from. A close the renderer had to defer (RVC/PDC on screen) is
 * posted again once per pass while the runtime stays down, never from a job's after-hook. A later snapshot from a running runtime resumes. Only the
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
    private static final String[] PHASES = {"slots", "rows", "status", "hmi"};

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
    private final IconIds ids;
    private final BuiltinIcons builtins;
    private final HmiWidgets widgets;
    private final Runnable hmiJob = new Runnable() {
        public void run() {
            runHmiJob();
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
    private boolean warnedIdsSpent;
    private final Set<String> loggedFailures = new HashSet<String>();
    private String shownStatus; // null: the SDK never showed text
    private String shownParts; // the parts of the last show (UiStatusPart has no equals: compared as text)
    private int shownSink = UNKNOWN; // StatusSinks.active() at the last show
    private long statusShownAt;

    // Read by both threads; the page state is written by the page job (HMIEvent; one at a time).
    private volatile boolean down;
    private volatile boolean closedRendered;
    private volatile long renderedOpenSeq;
    /** The newest openSeq the applier saw, and when (monotonic): an open request's age is measured from here. */
    private volatile long seenOpenSeq = Long.MIN_VALUE;
    private volatile long seenOpenAt;
    private volatile Map<String, UiPage> renderedPages;
    /** The page items' icon ids: given on {@code mibsdk-ui}; the ones the last page render saw. */
    private volatile ItemIcons itemIcons = ItemIcons.NONE;
    private volatile ItemIcons renderedIcons = ItemIcons.NONE;
    /** What the stock widgets should show; published by {@code mibsdk-ui}, applied by the HMI job. */
    private volatile HmiWidgetState wantWidgets = HmiWidgetState.EMPTY;
    /** Set by a pass that saw a deferred close: the next HMI job retries it (never the after-hook's own re-post). */
    private volatile boolean closeRetry;
    /** The same for a page rebuild the renderer deferred ({@link PageRenderer#rebuildPending}). */
    private volatile boolean rebuildRetry;

    private volatile boolean stopping;
    private Thread thread; // guarded by this

    /**
     * {@code ids}: the session's image ids ({@code SdkGlobals.ICON_IDS} in production); {@code builtins}: the
     * built-in icon set; {@code widgets}: applies the wanted {@link HmiWidgetState} on HMIEvent.
     */
    UiApplier(UiTable t, RowModels rows, StatusLineSink s, PageRenderer pages, HmiPoster p, Clock clock,
              RingLog log, IconIds ids, BuiltinIcons builtins, HmiWidgets widgets) {
        this.table = t;
        this.rows = rows;
        this.status = s;
        this.pages = pages;
        this.poster = p;
        this.clock = clock;
        this.log = log;
        this.ids = ids;
        this.builtins = builtins;
        this.widgets = widgets;
        for (int i = 0; i < rowState.length; i++) {
            rowState[i] = UNKNOWN;
        }
    }

    /**
     * The production applier over {@code SdkGlobals}: the status line on the SDK popup's Car models, or on popup 65
     * through {@code frameworkAccess} (the {@code de.audi.atip.base.IFrameworkAccess} service) as the fallback
     * ({@link StatusSinks} over {@code SdkGlobals.statusPopup}), row visibility through {@code SdkGlobals.uiRowModels},
     * jobs posted to HMIEvent, SDK pages as native screens ({@link PageScreens}), icon ids from the session's
     * {@code SdkGlobals.ICON_IDS} (never a new table) and the built-ins {@code HqcPlatform.start} read.
     * {@code statusMaxChars}: {@code ui.status.max.chars}. Starts nothing.
     */
    public static UiApplier create(Object frameworkAccess, Clock clock, RingLog log, int statusMaxChars) {
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
        UiInputs.clock = clock; // SDK presses are timed on it: an open from a Car screen needs a recent one
        PageRenderer pages = PageScreens.create(frameworkAccess, SdkGlobals.UI, poster, log);
        StatusLineSink sdkPopup = new SdkPopupStatusLine(new SdkPopupStatusLine.Source() {
            public SdkPopupStatusLine.Models get() {
                return SdkCarModelBank.statusModels();
            }
        });
        StatusLineSink status = new StatusSinks(sdkPopup, new Popup65StatusLine(frameworkAccess, statusMaxChars),
                new StatusSinks.State() {
                    public int get() {
                        return SdkGlobals.statusPopup;
                    }
                }, log);
        self[0] = new UiApplier(SdkGlobals.UI, rows, status, pages, poster, clock, log, SdkGlobals.ICON_IDS,
                SdkGlobals.builtinIcons, new HmiWidgets());
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

    /** The image id table this applier gives ids from. */
    IconIds iconIds() {
        return ids;
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
     * HmiPoster's after-job hook, on HMIEvent: posts the next HMI job if the snapshot moved past what was rendered, or
     * the wanted widget state past what was applied, while the last job was pending. Reads volatile state only; never
     * throws.
     */
    void wake() {
        if (!isLive()) {
            return; // replaced: never post again (a stale job would otherwise re-post itself forever)
        }
        try {
            postJobIfNeeded(false); // never the deferred-close retry: that waits for the next tick
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
            if (s.openSeq() != seenOpenSeq) {
                seenOpenAt = clock.uptimeMs();
                seenOpenSeq = s.openSeq();
            }
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
                            assignItemIcons(current);
                        }
                        break;
                    case 1:
                        applyRows();
                        break;
                    case 2:
                        applyStatus(current);
                        break;
                    default:
                        postJobIfNeeded(true);
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
        DrawerAssignment a = DrawerSlots.assign(slots, s.carRows(), HqcUiIds.PLUGIN_ROW_SLOTS, ids, builtins);
        slots = a;
        SdkGlobals.uiSlots = a;
        int[][] bitmaps = new int[HqcUiIds.ROW_SLOTS][];
        boolean unserved = false;
        for (int slot = 0; slot < HqcUiIds.ROW_SLOTS; slot++) {
            bitmaps[slot] = a.iconAt(slot);
            unserved |= bitmaps[slot] == null && hasIcon(s.carRows(), a.keyAt(slot));
        }
        wantWidgets = new HmiWidgetState(bitmaps, wantWidgets.status(), wantWidgets.mainMenuRowVisible());
        if (unserved && !warnedIdsSpent && ids != null && ids.exhausted()) {
            warnedIdsSpent = true;
            log.append("WARN", SOURCE, "ui icon ids " + HqcUiIds.ICON_ID_FIRST + ".." + HqcUiIds.ICON_ID_LAST
                    + " are spent: new icons show without an image until the HMI restarts");
        }
        List<String> overflow = a.overflow();
        for (int i = 0; i < overflow.size(); i++) {
            String key = overflow.get(i);
            if (warnedOverflow.add(key)) {
                log.append("WARN", SOURCE, "ui car row " + key + " has no drawer slot; reachable from the Plugins menu");
            }
        }
    }

    /**
     * On {@code mibsdk-ui}: gives every page item's icon its image id (a built-in its {@link HqcUiIds#ITEM_ICON_PX}
     * variant, an installed file its own, through {@link DrawerSlots#id}; the same icon keeps its id), and publishes
     * the result to the page renderer when it changed. Stock-thread code never gives ids. Returns what is published.
     */
    ItemIcons assignItemIcons(UiSnapshot s) {
        Map<UiIcon, Integer> m = new HashMap<UiIcon, Integer>();
        for (Iterator<UiPage> it = s.pages().values().iterator(); it.hasNext();) {
            List<UiEntry> entries = it.next().entries();
            for (int i = 0; i < entries.size(); i++) {
                UiEntry e = entries.get(i);
                UiIcon icon = e.kind() == UiEntry.ITEM ? e.icon() : null;
                if (icon != null && !m.containsKey(icon)) {
                    int id = itemIconId(icon);
                    if (id >= 0) {
                        m.put(icon, new Integer(id));
                    }
                }
            }
        }
        ItemIcons next = new ItemIcons(m);
        if (!next.equals(itemIcons)) {
            // The renderer first: a job running in between then counts the old ids as rendered and renders again.
            pages.itemIcons(next);
            itemIcons = next;
        }
        return itemIcons;
    }

    private int itemIconId(UiIcon icon) {
        if (ids == null) {
            return -1;
        }
        if (icon.kind() == UiIcon.STOCK) {
            return builtins == null ? -1 : DrawerSlots.id(builtins.get(icon.name(), HqcUiIds.ITEM_ICON_PX, false), ids);
        }
        return DrawerSlots.id(icon, ids);
    }

    /** True if the row {@code key} of {@code rows} has an icon. */
    private static boolean hasIcon(List<UiRow> rows, String key) {
        for (int i = 0; key != null && rows != null && i < rows.size(); i++) {
            UiRow r = rows.get(i);
            if (r != null && key.equals(r.key())) {
                return r.icon() != null;
            }
        }
        return false;
    }

    /**
     * Publishes the main menu's Plugins row's wanted visibility; writes each slot whose visibility changed, all of
     * them once {@code SdkGlobals.uiRowModels} (re)appears. Slot 0 only while the main menu has no Plugins row.
     */
    private void applyRows() {
        wantWidgets = wantWidgets.withMainMenuRow(!down && SdkGlobals.mainMenu == SdkGlobals.MAIN_MENU_ACTIVE);
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
            boolean visible = slots.visibleAt(slot) && (slot != 0 || SdkGlobals.drawerPluginsRowWanted());
            int want = visible ? 1 : 0;
            if (rowState[slot] != want) {
                rows.setVisible(slot, want == 1);
                rowState[slot] = want;
            }
        }
    }

    private void applyStatus(UiSnapshot s) {
        String text = s.statusLine();
        long now = clock.uptimeMs();
        if (text.length() == 0) {
            SdkStatusPopup.wanted = StatusLayout.EMPTY; // the popup's id icon cells are cleared
            wantWidgets = wantWidgets.withStatus(StatusLayout.EMPTY);
            if (shownStatus != null && shownStatus.length() > 0) { // only ever hide what the SDK showed
                status.hide();
                shownStatus = "";
            }
            return;
        }
        List<UiStatusPart> parts = s.statusParts();
        StatusLayout layout = StatusLayout.of(parts, ids, builtins);
        SdkStatusPopup.wanted = layout; // a popup built from now on starts from it
        wantWidgets = wantWidgets.withStatus(layout);
        String partsKey = parts.toString();
        int sink = status instanceof StatusSinks ? ((StatusSinks) status).active() : 0;
        if (!text.equals(shownStatus) || !partsKey.equals(shownParts) || sink != shownSink
                || now - statusShownAt >= STATUS_REASSERT_MS) {
            status.show(parts, text);
            shownStatus = text;
            shownParts = partsKey;
            // after the show: a throwing SDK popup fails over to popup 65 inside it, and that is where the line is
            shownSink = status instanceof StatusSinks ? ((StatusSinks) status).active() : 0;
            statusShownAt = now;
        }
    }

    /** {@code tick}: called from the applier's pass (not the job's after-hook), so a deferred close may be retried. */
    private void postJobIfNeeded(boolean tick) {
        if (!isLive() || (stopping && !down)) {
            return; // replaced, or stopping before the teardown took the UI down
        }
        if (SdkGlobals.uiRowModels == null && SdkStatusPopup.current == null && SdkGlobals.mainMenuRow == null) {
            return; // no drawer, popup or screen 46 yet, so no first screen yet: the StartupFilter would discard the job
        }
        boolean render = needsRender(table.snapshot(), tick);
        if (render && tick && down && closedRendered) {
            closeRetry = true; // the job's own re-evaluation (tick false) would not retry it
        }
        if (render && tick && !down && pages.rebuildPending()) {
            rebuildRetry = true; // likewise: once per pass, never from the after-hook
        }
        if (render || pages.navPending() || widgetsNeedJob() || (tick && widgets.wrenchPending())) {
            poster.post(hmiJob); // false: a job is pending; its wake() re-checks when it is done
        }
    }

    /**
     * True if the wanted widget state differs from the applied one in what exists: the status layout counts only once
     * the popup is built (a popup built later starts from {@code SdkStatusPopup.wanted}).
     */
    private boolean widgetsNeedJob() {
        HmiWidgetState applied = widgets.applied();
        if (widgets.mainMenuRowNeedsJob(wantWidgets)) {
            return true;
        }
        return SdkStatusPopup.current != null ? !wantWidgets.equals(applied) : !wantWidgets.sameRows(applied);
    }

    /**
     * The one HMI job, on HMIEvent: runs the pages' pending navigation (always: a nav asked for while this job was
     * pending waits for it, F2), renders pages if they still need it, then applies the wanted widget state. Each part
     * is isolated. A replaced applier's job does nothing.
     */
    private void runHmiJob() {
        if (!isLive()) {
            return;
        }
        try {
            pages.runPendingNav();
        } catch (Throwable t) {
            // pages still get rendered
        }
        try {
            boolean retry = closeRetry || rebuildRetry;
            closeRetry = false;
            rebuildRetry = false;
            if (retry || needsRender(table.snapshot(), false)) {
                renderPages();
            }
        } catch (Throwable t) {
            // the widgets still get applied
        }
        try {
            widgets.apply(wantWidgets);
        } catch (Throwable t) {
            // stock widgets stay as they are
        }
    }

    private boolean needsRender(UiSnapshot s, boolean tick) {
        if (down) {
            // A close deferred by RVC/PDC is retried once per pass (AWAIT_MS), never in a loop of jobs.
            return !closedRendered || (tick && pages.closePending());
        }
        return isOpenRequest(s) || !samePages(s.pages(), renderedPages) || itemIcons != renderedIcons
                || (tick && pages.rebuildPending());
    }

    private boolean isOpenRequest(UiSnapshot s) {
        return s.openSeq() != renderedOpenSeq && s.openPageKey() != null;
    }

    /**
     * The page part of the HMI job: renders the snapshot current now, never the one that triggered the post; once down,
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
        ItemIcons icons = itemIcons;
        renderedIcons = icons;
        pages.itemIcons(icons); // the renderer draws with exactly the ids counted as rendered
        if (open) {
            // A request this thread has not seen yet was published just now: age 0.
            long age = s.openSeq() == seenOpenSeq ? clock.uptimeMs() - seenOpenAt : 0;
            pages.open(s.openPageKey(), s, age);
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

    /** Every field of every entry: a change in only {@code enabled}, the number, an option, the range or the icon. */
    static boolean samePage(UiPage x, UiPage y) {
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
                    || !eq(p.value(), q.value()) || !eq(p.targetPageKey(), q.targetPageKey())
                    || p.number() != q.number() || !Arrays.equals(p.options(), q.options()) || p.min() != q.min()
                    || p.max() != q.max() || p.step() != q.step() || p.centred() != q.centred()
                    || p.enabled() != q.enabled() || !eq(p.icon(), q.icon())) {
                return false;
            }
        }
        return true;
    }

    private static boolean eq(Object x, Object y) {
        return x == null ? y == null : x.equals(y);
    }
}
