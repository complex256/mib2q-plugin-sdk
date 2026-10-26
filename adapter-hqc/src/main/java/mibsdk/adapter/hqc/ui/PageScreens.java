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
 * <p>Rendering: one list row ({@link PageRow}) per list entry (DROPDOWN and SLIDER are not list rows, so list row
 * {@code i} is {@code rowEntries[i]}), with native cells (spike D): a checkbox, radio, progress bar, value column or
 * item icon beside the label ({@link UiPageText#label}/{@link UiPageText#valueText}, never text tags), greyed by the
 * enabled column. A page is built per show with its {@link PageLayout} (the item icons' bitmaps); {@code refresh}
 * rewrites the list rows only. A new title or a new layout rebuilds the screen, only while the page is on screen and
 * the screen may change; until then a row whose icon is not in the shown layout is written without its icon (B4).
 * A press on a disabled row is dropped. CHOICE opens its choice page; a RADIO pick there is sent and goes back.
 *
 * <p>Opens: a request is honoured only within {@link #OPEN_TTL_MS} of the applier seeing it (monotonic clock), and
 * only while an SDK page is the connected screen, or on a Car screen (the Car drawer's) or the main menu
 * ({@link HqcUiIds#SYS_SCREEN_MAINWIZARD}, the MENU ring's Plugins row) within {@link #OPEN_TTL_MS} of a user press
 * on an SDK row or page entry ({@link UiInputs#sinceLastPress}); a plugin never opens a page over an arbitrary
 * screen. BACK on the bottom page returns to the screen the page was opened from (the Car screen's or screen 46's
 * saved data). A dropped request is logged and sent as {@code PAGE_CLOSED}, never replayed.
 *
 * <p>Vehicle safety: every screen change the SDK makes (show, BACK, return to stock, close) first checks
 * {@link #screenChangeAllowed}: no popup or parking/RVC overlay on the current screen, and no screen change under way
 * to a screen that is neither Car nor an SDK page. If the check fails, navigation and rebuilds are dropped, and a
 * close leaves the screen alone and is retried when the page connects again.
 */
final class PageScreens implements PageRenderer {

    /** How old an open request may be when its job runs. */
    static final long OPEN_TTL_MS = 2000;
    /** List row kinds: the list model's record-set column (2) picks the item factory's row. */
    static final int ROW_ACTION = 0;
    static final int ROW_TOGGLE = 1;
    static final int ROW_LABEL = 2;
    static final int ROW_RADIO = 3;
    static final int ROW_PROGRESS = 4;
    static final int ROW_VALUE = 5;
    static final int ROW_ICON = 6;
    static final int ROW_ICON_VALUE = 7;
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

        /** A new page screen for {@code pageKey}, its cells built for {@code layout}; callbacks go to {@code owner}. */
        Object build(String pageKey, String title, PageScreens owner, PageLayout layout) throws Exception;

        void show(Object screen) throws Exception;

        /** Shows the stock screen {@code data} (from {@link #currentData}) again. */
        void showStock(Object data) throws Exception;

        /** Shows stock screen {@code id} (built by its module's factory), keeping the current partial popups. */
        void showCarScreen(int id) throws Exception;

        /** Replaces the page list model's rows. */
        void writeRows(PageRow[] rows) throws Exception;
    }

    /** One requested navigation: BACK, or a LINK (or CHOICE) to {@code target}. */
    private static final class Nav {
        final String target; // null: BACK

        Nav(String target) {
            this.target = target;
        }
    }

    /** The page screen on show: its key and the layout it was built with (read by the row writes). */
    private static final class Shown {
        final String key;
        final PageLayout layout;

        Shown(String key, PageLayout layout) {
            this.key = key;
            this.layout = layout == null ? PageLayout.EMPTY : layout;
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

    private final Runnable retryJob = new Runnable() {
        public void run() {
            retryClose();
        }
    };

    private final List<Nav> navs = new ArrayList<Nav>();
    /** Navigation was asked for and has not run yet (read on {@code mibsdk-ui}: the applier posts a job for it). */
    private volatile boolean navPending;
    /** The item icons' image ids, given on {@code mibsdk-ui} ({@link UiApplier#assignItemIcons}). */
    private volatile ItemIcons itemIcons = ItemIcons.NONE;
    /** The runtime went down (close ran) and no open came since: this renderer's pages are dead. */
    private boolean down;
    /**
     * The runtime is down but the screen could not be changed then (RVC/PDC). Retried on every applier tick (it reads
     * {@link #closePending()} from {@code mibsdk-ui}), on any press on the page, and when the page connects again.
     */
    private volatile boolean closePending;
    /** The first page job looks for an SDK page a previous applier left on screen. */
    private boolean orphanChecked;
    /** That orphan could not be sent back yet (RVC/PDC): retried on later jobs and presses; logged once. */
    private boolean orphanPending;
    private boolean orphanLogged;
    /** The last page screen this instance showed or adopted (kept after it left: it may connect again). */
    private Object lastShown;
    /** The page screen this instance showed and believes on screen; null once left or never shown. */
    private Object shown;
    /** The key and layout of the page screen shown last; null before the first. */
    private volatile Shown shownPage;
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

    /**
     * The list row kind of {@code e}. ITEM, LINK and CHOICE get the value column when they have a value text; an ITEM
     * whose icon is in the layout ({@code iconIndex >= 0}) gets the icon column, and with a value both, or (with
     * {@code iconValue} false, the F12 fallback) the value only.
     */
    static int rowKind(UiEntry e, int iconIndex, boolean iconValue) {
        switch (e.kind()) {
            case UiEntry.INFO:
                return ROW_LABEL;
            case UiEntry.TOGGLE:
                return ROW_TOGGLE;
            case UiEntry.RADIO:
                return ROW_RADIO;
            case UiEntry.PROGRESS:
                return ROW_PROGRESS;
            case UiEntry.ITEM: {
                boolean value = UiPageText.valueText(e) != null;
                if (iconIndex >= 0) {
                    return !value ? ROW_ICON : iconValue ? ROW_ICON_VALUE : ROW_VALUE;
                }
                return value ? ROW_VALUE : ROW_ACTION;
            }
            default: // LINK, CHOICE
                return UiPageText.valueText(e) != null ? ROW_VALUE : ROW_ACTION;
        }
    }

    /** True for an entry drawn as a list row: every kind but DROPDOWN and SLIDER (their own rows, Task 5). */
    static boolean isListEntry(UiEntry e) {
        return e.kind() != UiEntry.DROPDOWN && e.kind() != UiEntry.SLIDER;
    }

    /**
     * Where an open may happen: an SDK page is the connected screen, or a Car screen (the Car drawer's) or the main
     * menu ({@link HqcUiIds#SYS_SCREEN_MAINWIZARD}) is, within {@link #OPEN_TTL_MS} of an SDK press
     * ({@code sincePressMs}); and a screen change is allowed at all.
     */
    static boolean openAllowed(int currentId, boolean blocked, int pendingId, long sincePressMs) {
        if (!screenChangeAllowed(blocked, pendingId)) {
            return false;
        }
        if (currentId == HqcUiIds.PAGE_SCREEN_ID) {
            return true;
        }
        boolean origin = isCarScreen(currentId) || currentId == HqcUiIds.SYS_SCREEN_MAINWIZARD;
        return origin && sincePressMs >= 0 && sincePressMs <= OPEN_TTL_MS;
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
            clearNavs(); // the accepted open replaces whatever navigation was asked for before it
            down = false;
            closePending = false;
            orphanChecked = true;
            orphanPending = false;
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
            if (down) {
                retryClose();
                return;
            }
            closeOrphanOnce();
            if (orphanPending) {
                retryOrphan();
            }
            runNav(s);
            String before = stack.top();
            if (before == null || shown == null) {
                return; // no SDK page of ours on screen: nothing to redraw
            }
            stack.reconcile(s);
            String top = stack.top();
            UiPage p = pageOf(top, s);
            PageLayout shownLayout = shownLayout();
            boolean rebuild = !top.equals(before) || !eq(titleOf(top, s), shownTitle)
                    || !PageLayout.of(p, itemIcons).equals(shownLayout);
            if (rebuild && screens.isCurrent(shown) && changeAllowed()) {
                showTop(s);
            } else {
                // not connected yet, or the screen may not change: the title and layout follow on a later refresh
                writeRows(p, shownLayout);
            }
        } catch (Throwable t) {
            failed("refresh", t);
        }
    }

    /**
     * Runtime down: back to the stock screen the bottom page came from, if one of this renderer's pages is on screen.
     * While the screen may not change (RVC/PDC) it is left alone and {@link #closePending()} is set: the applier
     * retries on its tick, and a press on the page or the page connecting again retries too. A retry never pulls the
     * user back from another screen: with no page of ours on screen it only clears the flag (a reconnect retries).
     */
    public void close() {
        try {
            clearNavs();
            down = true;
            boolean onScreen = shown != null || ownPageConnected();
            shown = null;
            stack.clear();
            if (!onScreen) {
                closePending = false;
                return;
            }
            if (!changeAllowed()) {
                if (!closePending) {
                    log.append("INFO", SOURCE, "ui page close deferred: a popup, parking display or screen change"
                            + " is under way");
                }
                closePending = true;
                return;
            }
            closePending = false;
            returnToStock();
        } catch (Throwable t) {
            failed("close", t);
        }
    }

    public boolean closePending() {
        return closePending;
    }

    public boolean navPending() {
        return navPending;
    }

    public void runPendingNav() {
        try {
            if (navPending) {
                runNav(table.snapshot());
            }
        } catch (Throwable t) {
            failed("navigation", t);
        }
    }

    public void itemIcons(ItemIcons ids) {
        itemIcons = ids == null ? ItemIcons.NONE : ids;
    }

    /** One of this renderer's page screens is the connected screen. */
    private boolean ownPageConnected() {
        if (lastShown != null && screens.isCurrent(lastShown)) {
            return true;
        }
        return live == this && screens.currentId() == HqcUiIds.PAGE_SCREEN_ID;
    }

    /** In a job: retries a deferred close (runtime down) or orphan close (runtime up). Never throws. */
    private void retryClose() {
        try {
            if (down) {
                close();
            } else if (orphanPending) {
                retryOrphan();
            }
        } catch (Throwable t) {
            failed("retry", t);
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
        orphanPending = true;
        retryOrphan();
    }

    /** Sends the orphan back to stock if it is still on screen and the screen may change; logs a deferral once. */
    private void retryOrphan() {
        if (stack.top() != null || screens.currentId() != HqcUiIds.PAGE_SCREEN_ID) {
            orphanPending = false; // gone, or one of our own pages is up now
            return;
        }
        if (!changeAllowed()) {
            if (!orphanLogged) {
                orphanLogged = true;
                log.append("INFO", SOURCE, "ui page: an SDK page of a previous runtime stays until the screen may"
                        + " change; close deferred");
            }
            return;
        }
        orphanPending = false;
        orphanLogged = false;
        returnToStock();
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
        if (down || orphanPending) {
            poster.post(retryJob); // a dead page: no input; try to take it off screen
            return;
        }
        List<UiEntry> entries = rowEntries;
        Shown sp = shownPage;
        String key = sp == null ? null : sp.key;
        if (key == null || row < 0 || row >= entries.size()) {
            return;
        }
        UiEntry e = entries.get(row);
        if (!e.enabled()) {
            return; // greyed: the HMI still sends its press
        }
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
            case UiEntry.CHOICE:
                UiInputs.userPressed();
                requestNav(new Nav(e.targetPageKey()));
                break;
            case UiEntry.RADIO:
                UiInputs.userPressed();
                table.input(new UiInput(UiInput.ENTRY_SELECTED, key, e.key(), false));
                requestNav(new Nav(null)); // the pick closes the choice page
                break;
            default:
                break; // INFO, PROGRESS
        }
    }

    /** BACK on the page (its MenuController consumed the key). Never throws. */
    void backPressed() {
        try {
            if (down || orphanPending) {
                poster.post(retryJob);
                return;
            }
            PageScreens l = live;
            if (shown == null && l != null && l != this) {
                l.backPressed(); // a page a newer renderer took over (an orphan): the newer one handles it
                return;
            }
            requestNav(new Nav(null));
        } catch (Throwable t) {
            // never propagate into the HMI
        }
    }

    /**
     * A page screen connected. Ours, shown last: nothing to do. Ours, after the user had left it (a popup or the
     * RVC ended and the HMI went back to it): it is the open page again. Never throws.
     */
    void screenConnected(Object screen, String pageKey, PageLayout layout) {
        try {
            if (down) {
                poster.post(retryJob); // a dead page came back: take it off screen (a pending job retries too)
                return;
            }
            if (screen == shown || shown != null) {
                return; // the one we showed, or an older one on its way out
            }
            orphanPending = false; // our own page is up: whatever was orphaned is gone
            shown = screen;
            lastShown = screen;
            shownPage = new Shown(pageKey, layout);
            live = this;
            stack.open(pageKey, stockReturn);
            UiSnapshot s = table.snapshot();
            stack.reconcile(s);
            writeRows(pageOf(stack.top(), s), shownLayout());
        } catch (Throwable t) {
            failed("connect", t);
        }
    }

    /** A page screen is leaving. If it is the one on screen and we did not replace it, the user left. Never throws. */
    void screenDisconnecting(Object screen) {
        try {
            if (screen != null && screen == shown) {
                shown = null;
                clearNavs();
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
        navPending = true;
        poster.post(navJob); // false: a job is pending (the applier's combined job), and it runs the navigation
    }

    private void clearNavs() {
        navs.clear();
        navPending = false;
    }

    /** In a job: the navigation asked for since the last one, in order. */
    private void runNav(UiSnapshot s) {
        try {
            runNavs(s);
        } finally {
            navPending = !navs.isEmpty();
        }
    }

    private void runNavs(UiSnapshot s) {
        if (down || orphanPending) {
            clearNavs();
            retryClose();
            return;
        }
        while (!navs.isEmpty()) {
            Nav n = navs.remove(0);
            if (shown == null || stack.top() == null) {
                clearNavs();
                return; // the page was left meanwhile
            }
            if (!changeAllowed()) {
                log.append("INFO", SOURCE, "ui page navigation dropped: a popup, parking display or screen change"
                        + " is under way");
                clearNavs();
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
            PageLayout layout = PageLayout.of(p, itemIcons);
            writeRows(p, layout);
            title = t;
            Object screen = screens.build(key, t, this, layout);
            PageScreens prev = live;
            if (prev != null && prev != this) {
                prev.shown = null; // a replaced applier's page: its leaving is not the user leaving
            }
            live = this;
            shown = screen;
            lastShown = screen;
            shownPage = new Shown(key, layout);
            shownTitle = t;
            screens.show(screen);
        } catch (Throwable t) {
            failed("show " + key, t);
            shown = null;
            stack.clear();
        }
    }

    /** Back to the saved stock screen; without one, to the Car main screen. Behind the screen-change gate. */
    private void returnToStock() {
        Object to = stockReturn;
        try {
            if (!changeAllowed()) { // callers check first; this is the last guard before showScreen
                log.append("INFO", SOURCE, "ui page: stock screen not shown: the screen may not change now");
                return;
            }
            if (to == null) {
                log.append("WARN", SOURCE, "ui page: no saved stock screen; back to Car " + HqcUiIds.CAR_MAIN_SCREEN);
                screens.showCarScreen(HqcUiIds.CAR_MAIN_SCREEN);
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

    /** The layout of the page screen shown last ({@link PageLayout#EMPTY} before one). */
    private PageLayout shownLayout() {
        Shown sp = shownPage;
        return sp == null ? PageLayout.EMPTY : sp.layout;
    }

    /** Writes {@code p}'s list rows for a screen built with {@code layout}: an icon not in it is not drawn. */
    private void writeRows(UiPage p, PageLayout layout) {
        List<UiEntry> all = p.entries();
        List<UiEntry> entries = new ArrayList<UiEntry>(all.size());
        for (int i = 0; i < all.size(); i++) {
            if (isListEntry(all.get(i))) {
                entries.add(all.get(i));
            }
        }
        PageRow[] rows = new PageRow[entries.size()];
        for (int i = 0; i < rows.length; i++) {
            UiEntry e = entries.get(i);
            rows[i] = PageRow.of(e, e.kind() == UiEntry.ITEM ? layout.iconIndex(e.icon()) : -1);
        }
        rowEntries = entries;
        try {
            screens.writeRows(rows);
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
