package mibsdk.adapter.hqc.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import mibsdk.platform.PageStack;
import mibsdk.platform.UiEntry;
import mibsdk.platform.UiInput;
import mibsdk.platform.UiPage;
import mibsdk.platform.UiPageText;
import mibsdk.platform.UiSnapshot;
import mibsdk.platform.UiTable;
import mibsdk.runtime.RingLog;

/**
 * SDK pages as native screens (spike B, {@link HqcUiIds#PAGE_PATH} = SHOW_SCREEN), with SDK-owned navigation. This
 * class is the stock-free part: the open gate, the {@link PageStack}, inputs and BACK. {@link PageWidgets} builds and
 * shows the stock widgets behind {@link Screens}.
 *
 * <p>Everything runs on HMIEvent: {@link #open}/{@link #refresh}/{@link #close} inside the applier's
 * {@link HmiPoster} job; list selection, BACK and the screen's connect/disconnect from stock widget callbacks. Those
 * callbacks only send {@link UiInput}s and ask for a job: every screen change runs in a posted job, through the same
 * poster (at most one SDK job pending; a nav request made while a job is pending runs inside that job).
 *
 * <p>Rendering: one list row per entry, all from {@link UiPageText#line}. TOGGLE rows carry their state as text
 * ({@code [on] }/{@code [off] }): spike B's checkbox cell drew nothing. A page is built per show; {@code refresh}
 * rewrites the list rows only (a new title rebuilds the screen, and only while the page is on screen).
 *
 * <p>Opens: a request is honoured only within {@link #OPEN_TTL_MS} of the applier seeing it (monotonic clock), and
 * only while an SDK page is the connected screen, or on a Car screen (the Car drawer's) within {@link #OPEN_TTL_MS}
 * of a user press on an SDK drawer row or page entry ({@link UiInputs#sinceLastPress}); a plugin never opens a page
 * over an arbitrary screen. A dropped request is logged and sent as {@code PAGE_CLOSED}, never replayed.
 *
 * <p>Vehicle safety: every screen change the SDK makes (show, BACK, return to stock, close) first checks
 * {@link #screenChangeAllowed}: no popup or parking/RVC overlay on the current screen, and no screen change under way
 * to a screen that is neither Car nor an SDK page. If the check fails, navigation and rebuilds are dropped, and a
 * close leaves the screen alone and is retried when the page connects again.
 */
final class PageScreens implements PageRenderer {

    /** How old an open request may be when its job runs. */
    static final long OPEN_TTL_MS = 2000;
    /** List row kinds (the list model's id column picks the item factory's row). */
    static final int ROW_ACTION = 0;
    static final int ROW_TOGGLE = 1;
    static final int ROW_LABEL = 2;
    /** PageStack's notice key for a page that vanished; the runtime publishes no page under it. */
    static final String STOPPED_KEY = "mibsdk/stopped";
    static final String STOPPED_TITLE = "Plugin stopped";
    static final String STOPPED_TEXT = "This page is no longer available.";
    private static final String SOURCE = "mibsdk";
    private static final int MAX_NAV = 8;

    /** The stock HMI, as the page logic needs it. Every method runs on HMIEvent and may throw. */
    interface Screens {
        /** The connected screen's id, or -1. */
        int currentId();

        /** The connected screen is a popup, or carries an EarlyApps (parking/RVC) partial popup. */
        boolean currentBlocked();

        /** The screen a pending screen change goes to, or -1. */
        int pendingId();

        /** The connected screen's data, to return to; null if none. */
        Object currentData();

        /** {@code screen} (from {@link #build}) is the connected screen. */
        boolean isCurrent(Object screen);

        /** A new page screen for {@code pageKey}; its callbacks go to {@code owner}. */
        Object build(String pageKey, String title, PageScreens owner) throws Exception;

        void show(Object screen) throws Exception;

        /** Shows the stock screen {@code data} (from {@link #currentData}) again. */
        void showStock(Object data) throws Exception;

        /** Replaces the page list model's rows. */
        void writeRows(String[] texts, int[] kinds) throws Exception;
    }

    /** One requested navigation: BACK, or a LINK to {@code target}. */
    private static final class Nav {
        final String target; // null: BACK

        Nav(String target) {
            this.target = target;
        }
    }

    // HMIEvent only (the static ones span appliers: a restarted runtime's pages share the screen).
    /** The instance whose page was shown last: list selection goes to it. */
    private static volatile PageScreens live;
    /** The stock screen SDK pages return to: recorded when a page opens over a stock screen. */
    private static Object stockReturn;
    /** The title {@link SdkCarScreenFactory#getText} serves for {@link HqcUiIds#TEXT_PAGE_TITLE}. */
    private static volatile String title = "";

    private final Screens screens;
    private final PageStack stack;
    private final UiTable table;
    private final HmiPoster poster;
    private final RingLog log;
    private final Runnable navJob = new Runnable() {
        public void run() {
            runNav(table.snapshot());
        }
    };

    private final Runnable closeJob = new Runnable() {
        public void run() {
            close();
        }
    };

    private final List<Nav> navs = new ArrayList<Nav>();
    /** The runtime is down but the screen could not be changed then (RVC/PDC): retried when the page connects. */
    private boolean closePending;
    /** The first page job looks for an SDK page a previous applier left on screen. */
    private boolean orphanChecked;
    /** The page screen this instance showed and believes on screen; null once left or never shown. */
    private Object shown;
    private String shownKey;
    private String shownTitle;
    private List<UiEntry> rowEntries = Collections.<UiEntry>emptyList();

    PageScreens(Screens screens, PageStack stack, UiTable table, HmiPoster poster, RingLog log) {
        this.screens = screens;
        this.stack = stack;
        this.table = table;
        this.poster = poster;
        this.log = log;
    }

    /** The production renderer: stock widgets on {@code frameworkAccess}, page events into {@code table}. */
    static PageScreens create(Object frameworkAccess, UiTable table, HmiPoster poster, RingLog log) {
        PageStack stack = new PageStack(new PageEvents(table), failuresTo(log));
        return new PageScreens(new PageWidgets(frameworkAccess), stack, table, poster, log);
    }

    /** Logs each throwing page-change listener call; never throws. */
    static PageStack.Failures failuresTo(final RingLog log) {
        return new PageStack.Failures() {
            public void listenerFailed(String event, String pageKey, Throwable t) {
                log.append("ERROR", SOURCE, "ui page " + event + " listener failed for " + pageKey + ": " + t);
            }
        };
    }

    // ---- stock-free mapping -------------------------------------------------------------------------------------

    static int rowKind(UiEntry e) {
        switch (e.kind()) {
            case UiEntry.INFO:
                return ROW_LABEL;
            case UiEntry.TOGGLE:
                return ROW_TOGGLE;
            default:
                return ROW_ACTION; // ITEM, LINK
        }
    }

    static String rowText(UiEntry e) {
        return UiPageText.line(e);
    }

    /**
     * Where an open may happen: an SDK page is the connected screen, or a Car screen (the Car drawer's) is, within
     * {@link #OPEN_TTL_MS} of an SDK press ({@code sincePressMs}); and a screen change is allowed at all.
     */
    static boolean openAllowed(int currentId, boolean blocked, int pendingId, long sincePressMs) {
        if (!screenChangeAllowed(blocked, pendingId)) {
            return false;
        }
        if (currentId == HqcUiIds.PAGE_SCREEN_ID) {
            return true;
        }
        return isCarScreen(currentId) && sincePressMs >= 0 && sincePressMs <= OPEN_TTL_MS;
    }

    /**
     * May the SDK change the screen now: no popup or parking/RVC overlay on the current screen ({@code blocked}), and
     * no change under way to a screen that is neither Car nor an SDK page (reverse gear: the RVC is coming).
     */
    static boolean screenChangeAllowed(boolean blocked, int pendingId) {
        return !blocked && (pendingId < 0 || isCarScreen(pendingId));
    }

    static boolean isCarScreen(int id) {
        return id >= 0 && id / 100000 == HqcUiIds.CAR_MODULE;
    }

    /** An EarlyApps screen or partial popup: the parking display, RVC and their overlays. */
    static boolean isParkingOverlay(int id) {
        return id >= 0 && id / 100000 == HqcUiIds.EARLY_APPS_MODULE;
    }

    /** The shown page's title (HMIEvent, from the factory's getText). */
    static String title() {
        return title;
    }

    // ---- PageRenderer (the applier's job) -----------------------------------------------------------------------

    public void open(String pageKey, UiSnapshot s, long requestAgeMs) {
        try {
            if (requestAgeMs > OPEN_TTL_MS) {
                drop(pageKey, "the request is " + requestAgeMs + " ms old");
                return;
            }
            int current = screens.currentId();
            long sincePress = UiInputs.sinceLastPress();
            if (!openAllowed(current, screens.currentBlocked(), screens.pendingId(), sincePress)) {
                drop(pageKey, "screen " + current + " is no SDK page, or no SDK press within " + OPEN_TTL_MS
                        + " ms, or a popup, parking display or screen change is under way");
                return;
            }
            navs.clear(); // the accepted open replaces whatever navigation was asked for before it
            closePending = false;
            orphanChecked = true;
            if (current != HqcUiIds.PAGE_SCREEN_ID) {
                stockReturn = screens.currentData();
            }
            stack.open(pageKey, stockReturn);
            stack.reconcile(s);
            showTop(s);
        } catch (Throwable t) {
            failed("open " + pageKey, t);
        }
    }

    public void refresh(UiSnapshot s) {
        try {
            if (closePending) {
                close();
                return;
            }
            closeOrphanOnce();
            runNav(s);
            String before = stack.top();
            if (before == null || shown == null) {
                return; // no SDK page of ours on screen: nothing to redraw
            }
            stack.reconcile(s);
            String top = stack.top();
            boolean rebuild = !top.equals(before) || !eq(titleOf(top, s), shownTitle);
            if (rebuild && screens.isCurrent(shown) && changeAllowed()) {
                showTop(s);
            } else {
                writeRows(pageOf(top, s)); // not connected yet: the title follows on the next refresh
            }
        } catch (Throwable t) {
            failed("refresh", t);
        }
    }

    /**
     * Runtime down: back to the stock screen the bottom page came from, if an SDK page is on screen. While the screen
     * may not change (RVC/PDC), the screen is left alone and the return is retried when the page connects again.
     */
    public void close() {
        try {
            navs.clear();
            boolean onScreen = shown != null || closePending || screens.currentId() == HqcUiIds.PAGE_SCREEN_ID;
            shown = null;
            stack.clear();
            closePending = false;
            if (!onScreen) {
                return;
            }
            if (!changeAllowed()) {
                closePending = true;
                log.append("INFO", SOURCE, "ui page close deferred: a popup, parking display or screen change is"
                        + " under way");
                return;
            }
            returnToStock();
        } catch (Throwable t) {
            failed("close", t);
        }
    }

    /**
     * The first job of a renderer whose stack is empty: an SDK page still on screen belongs to a previous applier
     * (its teardown did not run); send it back to the stock screen.
     */
    private void closeOrphanOnce() {
        if (orphanChecked) {
            return;
        }
        orphanChecked = true;
        PageScreens prev = live;
        if (stack.top() != null || prev == this || screens.currentId() != HqcUiIds.PAGE_SCREEN_ID) {
            return;
        }
        if (prev != null) {
            prev.shown = null; // its page leaving is not the user leaving
        }
        live = this;
        if (changeAllowed()) {
            returnToStock();
        } else {
            closePending = true;
        }
    }

    // ---- callbacks from the page's widgets (HMIEvent) -----------------------------------------------------------

    /** A row of the page list was selected (the bank's ListListener). Never throws. */
    static void listSelected(int row) {
        try {
            PageScreens p = live;
            if (p != null) {
                p.selected(row);
            }
        } catch (Throwable t) {
            // never propagate into the HMI
        }
    }

    private void selected(int row) {
        List<UiEntry> entries = rowEntries;
        String key = shownKey;
        if (key == null || row < 0 || row >= entries.size()) {
            return;
        }
        UiEntry e = entries.get(row);
        switch (e.kind()) {
            case UiEntry.ITEM:
                UiInputs.userPressed();
                table.input(new UiInput(UiInput.ENTRY_SELECTED, key, e.key(), false));
                break;
            case UiEntry.TOGGLE:
                UiInputs.userPressed();
                table.input(new UiInput(UiInput.TOGGLE_REQUESTED, key, e.key(), !e.on()));
                break;
            case UiEntry.LINK:
                UiInputs.userPressed();
                requestNav(new Nav(e.targetPageKey()));
                break;
            default:
                break; // INFO
        }
    }

    /** BACK on the page (its MenuController consumed the key). Never throws. */
    void backPressed() {
        try {
            requestNav(new Nav(null));
        } catch (Throwable t) {
            // never propagate into the HMI
        }
    }

    /**
     * A page screen connected. Ours, shown last: nothing to do. Ours, after the user had left it (a popup or the
     * RVC ended and the HMI went back to it): it is the open page again. Never throws.
     */
    void screenConnected(Object screen, String pageKey) {
        try {
            if (closePending) {
                poster.post(closeJob); // the runtime is down: retry the return to stock (a pending job retries too)
                return;
            }
            if (screen == shown || shown != null) {
                return; // the one we showed, or an older one on its way out
            }
            shown = screen;
            shownKey = pageKey;
            live = this;
            stack.open(pageKey, stockReturn);
            UiSnapshot s = table.snapshot();
            stack.reconcile(s);
            writeRows(pageOf(stack.top(), s));
        } catch (Throwable t) {
            failed("connect", t);
        }
    }

    /** A page screen is leaving. If it is the one on screen and we did not replace it, the user left. Never throws. */
    void screenDisconnecting(Object screen) {
        try {
            if (screen != null && screen == shown) {
                shown = null;
                navs.clear();
                stack.clear();
            }
        } catch (Throwable t) {
            failed("disconnect", t);
        }
    }

    // ---- navigation ---------------------------------------------------------------------------------------------

    private void requestNav(Nav n) {
        if (navs.size() < MAX_NAV) {
            navs.add(n);
        }
        poster.post(navJob); // false: a job is pending, and it runs the navigation first
    }

    /** In a job: the navigation asked for since the last one, in order. */
    private void runNav(UiSnapshot s) {
        if (closePending) {
            close();
            return;
        }
        while (!navs.isEmpty()) {
            Nav n = navs.remove(0);
            if (shown == null || stack.top() == null) {
                navs.clear();
                return; // the page was left meanwhile
            }
            if (!changeAllowed()) {
                log.append("INFO", SOURCE, "ui page navigation dropped: a popup, parking display or screen change"
                        + " is under way");
                navs.clear();
                return;
            }
            try {
                if (n.target != null) {
                    stack.push(n.target, null, s);
                    showTop(s);
                } else {
                    stack.reconcile(s);
                    stack.back();
                    if (stack.top() == null) {
                        shown = null;
                        returnToStock();
                    } else {
                        showTop(s);
                    }
                }
            } catch (Throwable t) {
                failed("navigation", t);
            }
        }
    }

    /** Builds and shows the stack's top page. On failure nothing of ours is on screen and the stack is empty. */
    private void showTop(UiSnapshot s) {
        String key = stack.top();
        try {
            if (!changeAllowed()) { // callers check first; this is the last guard before showScreen
                log.append("INFO", SOURCE, "ui page " + key + " not shown: the screen may not change now");
                return;
            }
            UiPage p = pageOf(key, s);
            String t = p.title() == null ? "" : p.title();
            writeRows(p);
            title = t;
            Object screen = screens.build(key, t, this);
            PageScreens prev = live;
            if (prev != null && prev != this) {
                prev.shown = null; // a replaced applier's page: its leaving is not the user leaving
            }
            live = this;
            shown = screen;
            shownKey = key;
            shownTitle = t;
            screens.show(screen);
        } catch (Throwable t) {
            failed("show " + key, t);
            shown = null;
            stack.clear();
        }
    }

    private void returnToStock() {
        Object to = stockReturn;
        if (to == null) {
            log.append("WARN", SOURCE, "ui page: no stock screen to return to");
            return;
        }
        try {
            if (!changeAllowed()) { // callers check first; this is the last guard before showScreen
                log.append("INFO", SOURCE, "ui page: stock screen not shown: the screen may not change now");
                return;
            }
            screens.showStock(to);
            stockReturn = null; // used: a later open records its own
        } catch (Throwable t) {
            failed("return", t);
        }
    }

    /** {@link #screenChangeAllowed} on the screen now. */
    private boolean changeAllowed() {
        return screenChangeAllowed(screens.currentBlocked(), screens.pendingId());
    }

    private void writeRows(UiPage p) {
        List<UiEntry> entries = p.entries();
        String[] texts = new String[entries.size()];
        int[] kinds = new int[texts.length];
        for (int i = 0; i < texts.length; i++) {
            texts[i] = rowText(entries.get(i));
            kinds[i] = rowKind(entries.get(i));
        }
        rowEntries = entries;
        try {
            screens.writeRows(texts, kinds);
        } catch (Throwable t) {
            failed("rows", t);
        }
    }

    private void drop(String pageKey, String why) {
        log.append("INFO", SOURCE, "ui open of " + pageKey + " dropped: " + why);
        if (!pageKey.equals(stack.top())) {
            table.input(new UiInput(UiInput.PAGE_CLOSED, pageKey, null, false));
        }
    }

    private void failed(String what, Throwable t) {
        log.append("ERROR", SOURCE, "ui page " + what + " failed: " + t);
    }

    /** The page under {@code key}, or the stopped notice (the runtime publishes none under its key). */
    private static UiPage pageOf(String key, UiSnapshot s) {
        UiPage p = key == null ? null : s.pages().get(key);
        if (p != null) {
            return p;
        }
        return new UiPage(STOPPED_KEY, STOPPED_TITLE, Collections.singletonList(
                new UiEntry(UiEntry.INFO, "stopped", STOPPED_TEXT, null, false, null)));
    }

    private static String titleOf(String key, UiSnapshot s) {
        String t = pageOf(key, s).title();
        return t == null ? "" : t;
    }

    private static boolean eq(Object x, Object y) {
        return x == null ? y == null : x.equals(y);
    }

    /** Tests: forget the state shared across instances. */
    static Object stockReturnForTests() {
        return stockReturn;
    }

    static void forgetForTests() {
        UiInputs.forgetForTests();
        live = null;
        stockReturn = null;
        title = "";
    }
}
