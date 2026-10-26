package mibsdk.adapter.hqc.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import mibsdk.platform.PageStack;
import mibsdk.platform.UiDialog;
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
 * <p>Typed rows (Task 5): the layout's dropdown and slider rows ({@link PageLayout#typed}) follow the list, each on
 * the pooled model of its slot, which the applier writes on {@code mibsdk-ui} for the {@link Shown} layout published
 * here. A dropdown pick sends {@code VALUE_REQUESTED} with the option index. SELECT on a slider row opens the slider
 * edit screen {@link HqcUiIds#SLIDER_SCREEN_ID} ({@link #sliderPressed}); its knob turns accumulate in a
 * {@link SliderSession} and send the requested value. A sub-screen ({@link #openSub}) leaves the page open: no
 * {@code PAGE_CLOSED}, input still goes to the page's key; BACK on it ({@link #subBack}) re-shows the page with the
 * same rows and no stack pop. A stock screen connecting over a sub-screen is the user leaving the page.
 *
 * <p>Dialogs (Task 6): the snapshot's confirm or speller ({@link #dialogChanged}) is a sub-screen over its owner's page
 * ({@link HqcUiIds#CONFIRM_SCREEN_ID}, {@link HqcUiIds#SPELLER_SCREEN_ID}), shown only while that page (or a choice
 * page of it) is ours on screen and the screen may change; otherwise it is answered at once as not shown. Each is
 * answered exactly once with {@code DIALOG_DONE} (page key = the owner's): a confirm row (Yes 1, No 0), BACK (confirm
 * 0, the speller done with its text: no OK key is reachable), or its screen leaving unanswered (dismissed, the same
 * answers), always before the page-closed handling of a stock screen over it. A withdrawn dialog goes back to the page
 * with no answer. The page under a dialog is never reported closed by the dialog itself.
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
    /** How long a shown sub-screen may take to connect before it is given up (monotonic clock). */
    static final long SUB_CONNECT_MS = 3000;
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

        /**
         * A new slider edit screen ({@link HqcUiIds#SLIDER_SCREEN_ID}) for typed slot {@code slot} of {@code layout};
         * its BACK and connect/disconnect go to {@code owner}. Shown with {@link #show}.
         */
        Object buildSlider(int slot, PageLayout layout, PageScreens owner) throws Exception;

        /**
         * A new confirm screen ({@link HqcUiIds#CONFIRM_SCREEN_ID}) for {@code d}, its Yes/No rows written; its BACK
         * and connect/disconnect go to {@code owner}. Shown with {@link #show}.
         */
        Object buildConfirm(UiDialog d, PageScreens owner) throws Exception;

        /**
         * A new speller screen ({@link HqcUiIds#SPELLER_SCREEN_ID}) for {@code d}, the SpellerModel's max length and
         * initial text written; its BACK and connect/disconnect go to {@code owner}. Shown with {@link #show}.
         */
        Object buildSpeller(UiDialog d, PageScreens owner) throws Exception;

        /** The SpellerModel's text (what the GUI wrote last), or null. */
        String spellerText();
    }

    /** One requested navigation: BACK, a LINK (or CHOICE) to {@code target}, the slider screen, or BACK from it. */
    private static final class Nav {
        static final int PAGE = 0;
        static final int SLIDER = 1;
        static final int SUB_BACK = 2;

        final int kind;
        final String target; // PAGE: null is BACK
        final int slot; // SLIDER

        Nav(String target) {
            this(PAGE, target, -1);
        }

        Nav(int kind, String target, int slot) {
            this.kind = kind;
            this.target = target;
            this.slot = slot;
        }
    }

    /**
     * The page screen on show: its key, the layout it was built with (read by the row writes, the typed rows' input
     * and texts, and on {@code mibsdk-ui} by the applier's typed-model writes), and its generation: new at each build,
     * so the applier writes every typed model again for a new screen. Immutable.
     */
    static final class Shown {
        final String pageKey;
        final PageLayout layout;
        final int generation;

        Shown(String pageKey, PageLayout layout, int generation) {
            this.pageKey = pageKey;
            this.layout = layout == null ? PageLayout.EMPTY : layout;
            this.generation = generation;
        }
    }

    // HMIEvent only (the static ones span appliers: a restarted runtime's pages share the screen).
    /** The instance whose page was shown last: list selection goes to it. */
    private static volatile PageScreens live;
    /** The stock screen SDK pages return to: recorded when a page opens over a stock screen. */
    private static Object stockReturn;
    /** The title {@link SdkCarScreenFactory#getText} serves for {@link HqcUiIds#TEXT_PAGE_TITLE}. */
    private static volatile String title = "";
    /** The dialog whose texts {@link InputTextIds} serves: the one on show; null for none. */
    private static volatile UiDialog dialog;
    /** Counts page screen builds and adoptions: each {@link Shown} gets the next. */
    private static int generations;

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
    /**
     * The shown page needs a rebuild (title or layout) that the screen could not take (popup, parking display): the
     * applier retries on its tick. Set and cleared on HMIEvent.
     */
    private volatile boolean rebuildPending;
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
    /** The sub-screen over the page (609501..609503) shown last and not left; null for none. */
    private Object sub;
    private int subId = -1;
    /** {@link #sub} was shown and has not connected yet (read on {@code mibsdk-ui} by {@link #rebuildPending}). */
    private volatile boolean subWaiting;
    /** When {@link #sub} was shown ({@link UiInputs#now}; -1 without a clock: never given up). */
    private long subShownAt;
    /**
     * The sub-screen shown last, the page it was over and its typed slot (-1 for none): kept after the user left, so
     * the same screen connecting again re-adopts the page ({@link #subConnected}). Forgotten when a page is shown.
     */
    private Object lastSub;
    private int lastSubId = -1;
    private int lastSubSlot = -1;
    private Shown lastSubShown;
    /** The open slider edit screen's session; null unless the sub-screen is the slider. Read by getText. */
    private volatile SliderSession slider;
    /** The dialog seq {@link #dialogChanged} acted on last (shown, refused or withdrawn); 0 for none. */
    private long dialogSeq;
    /** The dialog whose screen is the sub-screen, until it is answered or withdrawn; null for none. */
    private UiDialog dialogUp;
    /** The open speller's session; null unless {@link #dialogUp} is a speller. */
    private SpellerSession spellerSession;

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

    /** An SDK screen: the page (609500) or a sub-screen over it (609501..609503). */
    static boolean isSdkScreen(int id) {
        return id >= HqcUiIds.PAGE_SCREEN_ID && id <= HqcUiIds.SUB_SCREEN_LAST;
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
        if (isSdkScreen(currentId)) {
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

    /** The page screen shown last by the live renderer, or null (HMIEvent, from the factory's getText). */
    static Shown shownNow() {
        PageScreens p = live;
        return p == null ? null : p.shownPage;
    }

    /** The live renderer's open slider session, or null (HMIEvent, from the factory's getText). */
    static SliderSession sliderNow() {
        PageScreens p = live;
        return p == null ? null : p.slider;
    }

    /** The dialog on show, or null (HMIEvent, from the factory's getText). */
    static UiDialog dialogNow() {
        return dialog;
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
            if (!isSdkScreen(current)) {
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
            if (subOverdue()) {
                giveUpSub();
            }
            String before = stack.top();
            if (before == null || shown == null) {
                rebuildPending = false;
                return; // no SDK page of ours on screen: nothing to redraw
            }
            stack.reconcile(s);
            String top = stack.top();
            UiPage p = pageOf(top, s);
            if (sub != null && (!top.equals(before) || !syncSlider(p))) {
                backFromSub(s); // the page or the slider's entry is gone: back to the page stack's top as it is now
                return;
            }
            PageLayout shownLayout = shownLayout();
            boolean rebuild = !top.equals(before) || !eq(titleOf(top, s), shownTitle)
                    || !PageLayout.of(p, itemIcons).equals(shownLayout);
            boolean current = sub == null && screens.isCurrent(shown); // never rebuilt from under a sub-screen
            boolean allowed = changeAllowed();
            rebuildPending = rebuild && current && !allowed;
            if (rebuild && current && allowed) {
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
            rebuildPending = false;
            down = true;
            boolean onScreen = shown != null || ownPageConnected();
            shown = null;
            dropDialog(); // the runtime is down: nothing to answer
            clearSub();
            lastSub = null;
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

    /** Also true while a shown sub-screen has not connected: the job that may give it up runs on the tick. */
    public boolean rebuildPending() {
        return rebuildPending || subWaiting;
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

    public Shown shown() {
        return shownPage;
    }

    /** One of this renderer's page screens is the connected screen. */
    private boolean ownPageConnected() {
        if (lastShown != null && screens.isCurrent(lastShown)) {
            return true;
        }
        return live == this && isSdkScreen(screens.currentId());
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
        if (stack.top() != null || prev == this || !isSdkScreen(screens.currentId())) {
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
        if (stack.top() != null || !isSdkScreen(screens.currentId())) {
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
        String key = sp == null ? null : sp.pageKey;
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

    /** A dropdown row's option {@code value} was picked (its pooled ChoiceModel's listener). Never throws. */
    static void typedChosen(int slot, int value) {
        try {
            PageScreens p = live;
            if (p != null) {
                p.chosen(slot, value);
            }
        } catch (Throwable t) {
            // never propagate into the HMI
        }
    }

    private void chosen(int slot, int value) {
        if (down || orphanPending) {
            poster.post(retryJob);
            return;
        }
        Shown sp = shownPage;
        PageLayout.TypedRow r = sp == null ? null : sp.layout.typedAt(slot);
        if (r == null || r.kind != UiEntry.DROPDOWN || value < 0 || value >= r.optionCount()) {
            return;
        }
        UiInputs.userPressed();
        table.input(new UiInput(UiInput.VALUE_REQUESTED, sp.pageKey, r.entryKey, false, value, null));
    }

    /**
     * SELECT on a slider row (its key handler consumed the key): opens the slider edit screen, in a job, if the slot
     * holds a live SLIDER entry and the screen may change. Never throws.
     */
    static void sliderPressed(int slot) {
        try {
            PageScreens p = live;
            if (p != null) {
                p.sliderPress(slot);
            }
        } catch (Throwable t) {
            // never propagate into the HMI
        }
    }

    private void sliderPress(int slot) {
        if (down || orphanPending) {
            poster.post(retryJob);
            return;
        }
        Shown sp = shownPage;
        if (sp == null || shown == null || sub != null || sliderEntry(sp, slot, table.snapshot()) == null
                || !changeAllowed()) {
            return; // no live slider there (sliders cannot be disabled), or a popup / parking display is up
        }
        UiInputs.userPressed();
        requestNav(new Nav(Nav.SLIDER, null, slot));
    }

    /** A knob turn of {@code n} on the slider screen (the RangeModel's listener, +/- by direction). Never throws. */
    static void sliderTurned(int slot, int n) {
        try {
            PageScreens p = live;
            if (p != null) {
                p.turned(slot, n);
            }
        } catch (Throwable t) {
            // never propagate into the HMI
        }
    }

    private void turned(int slot, int n) {
        if (down || orphanPending) {
            poster.post(retryJob);
            return;
        }
        SliderSession ss = slider;
        Shown sp = shownPage;
        PageLayout.TypedRow r = sp == null ? null : sp.layout.typedAt(slot);
        if (ss == null || sub == null || ss.slot() != slot || r == null || r.kind != UiEntry.SLIDER) {
            return; // not the open slider's model: the page's rotary row is a preview
        }
        int was = ss.index();
        int value = ss.onTurn(n);
        if (ss.index() == was) {
            return; // at an end already
        }
        UiInputs.userPressed();
        table.input(new UiInput(UiInput.VALUE_REQUESTED, sp.pageKey, r.entryKey, false, value, null));
    }

    /**
     * Shows {@code screen} (sub-screen {@code screenId}, 609501..609503) over the page, in a job: the page stays open
     * (its leaving is not the user leaving) and input keeps going to its key until {@link #subBack} or a stock screen.
     */
    void openSub(int screenId, Object screen) {
        sub = screen;
        subId = screenId;
        subShownAt = UiInputs.now();
        subWaiting = true;
        lastSub = screen;
        lastSubId = screenId;
        lastSubSlot = -1;
        lastSubShown = shownPage;
        try {
            screens.show(screen);
        } catch (Throwable t) {
            failed("show sub-screen " + screenId, t);
            clearSub();
        }
    }

    /**
     * BACK on a sub-screen (it consumed the key): a dialog up is answered (confirm No; the speller is done with its
     * text), then the page again, same rows, no stack pop, in a job. Never throws.
     */
    void subBack() {
        try {
            if (down || orphanPending) {
                poster.post(retryJob);
                return;
            }
            answerDialog(0);
            requestNav(new Nav(Nav.SUB_BACK, null, -1));
        } catch (Throwable t) {
            // never propagate into the HMI
        }
    }

    /**
     * A sub-screen connected. The one shown: nothing to do (the page stays open). The one shown last, after the user
     * had left the page (a stock screen came over it, and the HMI went back to it): the page it was over is the open
     * page again, as {@link #screenConnected} does for the page screen; a slider whose entry is gone goes back to the
     * page at once. Never throws.
     */
    void subConnected(Object screen) {
        try {
            if (down) {
                poster.post(retryJob);
                return;
            }
            if (screen != null && screen == sub) {
                subWaiting = false;
                return;
            }
            if (screen != null && screen == lastSub && sub == null && shown == null && lastSubShown != null) {
                readoptSub(screen);
            }
        } catch (Throwable t) {
            failed("sub connect", t);
        }
    }

    private void readoptSub(Object screen) {
        Shown was = lastSubShown;
        orphanPending = false;
        UiSnapshot s = table.snapshot();
        stack.open(was.pageKey, stockReturn);
        stack.reconcile(s);
        live = this;
        shown = screen; // the sub-screen stands for the page until BACK shows the page screen
        lastShown = screen;
        sub = screen;
        subId = lastSubId;
        subWaiting = false;
        shownPage = new Shown(was.pageKey, was.layout, ++generations);
        UiEntry e = lastSubId == HqcUiIds.SLIDER_SCREEN_ID && was.pageKey.equals(stack.top())
                ? sliderEntry(shownPage, lastSubSlot, s) : null;
        PageLayout.TypedRow r = was.layout.typedAt(lastSubSlot);
        if (e != null && PageLayout.TypedRow.of(e).equals(r)) {
            slider = new SliderSession(r.min, r.step, r.steps, e.number(), lastSubSlot);
        } else {
            requestNav(new Nav(Nav.SUB_BACK, null, -1)); // nothing to edit here now: the page
        }
    }

    /** The SDK owns {@code screen} (a sub-screen) now: its BACK and knob are the SDK's; otherwise stock's. */
    boolean ownsSub(Object screen) {
        return !down && screen != null && screen == sub;
    }

    /**
     * A shown sub-screen that has not connected within {@link #SUB_CONNECT_MS} (its show was dropped or overtaken),
     * is not on screen and no screen change is on its way to it.
     */
    private boolean subOverdue() {
        if (sub == null || !subWaiting || subShownAt < 0) {
            return false;
        }
        long now = UiInputs.now();
        return now >= 0 && now - subShownAt > SUB_CONNECT_MS && !screens.isCurrent(sub)
                && screens.pendingId() != subId;
    }

    /**
     * Gives up a sub-screen that never connected: with the page still on screen it is only forgotten; otherwise the
     * page's leaving (ignored for the sub-screen) was the user leaving. The same screen connecting later re-adopts.
     */
    private void giveUpSub() {
        log.append("INFO", SOURCE, "ui page: sub-screen " + subId + " never connected; given up");
        boolean pageOn = shown != null && shown != sub && screens.isCurrent(shown);
        clearSub();
        if (!pageOn) {
            shown = null;
            rebuildPending = false;
            clearNavs();
            stack.clear();
        }
    }

    /** A sub-screen is leaving. If it is ours and we did not replace it, the user left the page. Never throws. */
    void subDisconnecting(Object screen) {
        try {
            if (screen != null && screen == sub) {
                clearSub();
                shown = null;
                rebuildPending = false;
                clearNavs();
                stack.clear();
            }
        } catch (Throwable t) {
            failed("sub disconnect", t);
        }
    }

    /**
     * Forgets the sub-screen. A dialog's screen leaving unanswered (a stock screen over it, never connected, the page
     * rebuilt) is answered here as dismissed (confirm 0, the speller with its text): before any page-closed handling,
     * which the callers do after this.
     */
    private void clearSub() {
        answerDialog(0);
        sub = null;
        slider = null;
        subWaiting = false;
        dialog = null;
    }

    // ---- dialogs (Task 6): confirm 609502 and speller 609503, sub-screens over their owner's page --------------------

    public void dialogChanged(UiDialog d) {
        try {
            long seq = d == null ? 0 : d.seq();
            if (seq == dialogSeq) {
                return; // the same dialog (or none) again
            }
            dialogSeq = seq;
            boolean wasUp = dialogUp != null;
            dropDialog(); // withdrawn, or replaced by d: it gets no answer from us
            if (d != null && dialogAllowed(d)) {
                showDialog(d); // replaces whatever sub-screen is up
                return;
            }
            if (wasUp && sub != null) {
                subBack(); // its screen goes; the page again
            }
            if (d != null) {
                log.append("INFO", SOURCE, "ui dialog " + seq + " not shown: " + d.ownerPageKey()
                        + " is not on screen, or the screen may not change");
                answer(d, 0, null);
            }
        } catch (Throwable t) {
            failed("dialog", t);
        }
    }

    /** {@code d} may show now: its owner's page (or a {@code #} child of it) is ours on screen, and it may change. */
    private boolean dialogAllowed(UiDialog d) {
        Shown sp = shownPage;
        if (down || orphanPending || shown == null || sp == null) {
            return false;
        }
        String owner = d.ownerPageKey();
        boolean over = sp.pageKey.equals(owner) || sp.pageKey.startsWith(owner + "#");
        return over && changeAllowed();
    }

    /** Builds and shows {@code d}'s screen as the sub-screen; a build that fails answers it as not shown. */
    private void showDialog(UiDialog d) {
        boolean isSpeller = d.kind() == UiDialog.SPELLER;
        Object screen;
        try {
            screen = isSpeller ? screens.buildSpeller(d, this) : screens.buildConfirm(d, this);
        } catch (Throwable t) {
            failed("build dialog " + d.seq(), t);
            answer(d, 0, null);
            return;
        }
        clearSub(); // a slider under it closes
        dialog = d; // its texts, served at connect
        dialogUp = d;
        spellerSession = isSpeller ? new SpellerSession(d.seq(), d.text()) : null;
        openSub(isSpeller ? HqcUiIds.SPELLER_SCREEN_ID : HqcUiIds.CONFIRM_SCREEN_ID, screen);
    }

    /**
     * Answers the dialog up, once, and forgets it: a confirm with {@code value}; a speller (whatever {@code value}) is
     * done with the model's text, else the last typed, else its initial text. Nothing without one.
     */
    private void answerDialog(int value) {
        UiDialog d = dialogUp;
        SpellerSession ss = spellerSession;
        dropDialog();
        if (d == null) {
            return;
        }
        if (ss == null) {
            answer(d, value, null);
        } else if (ss.takeDone()) {
            answer(d, 0, ss.doneText(spellerModelText()));
        }
    }

    /** Forgets the dialog up with no answer. */
    private void dropDialog() {
        dialogUp = null;
        spellerSession = null;
    }

    private void answer(UiDialog d, int value, String text) {
        table.input(new UiInput(UiInput.DIALOG_DONE, d.ownerPageKey(), String.valueOf(d.seq()), false, value, text));
    }

    private String spellerModelText() {
        try {
            return screens.spellerText();
        } catch (Throwable t) {
            return null; // the session's last text then
        }
    }

    /** A row of the confirm list was selected (its ListListener): row 0 Yes (1), row 1 No (0). Never throws. */
    static void confirmSelected(int row) {
        try {
            PageScreens p = live;
            if (p != null) {
                p.confirmPick(row);
            }
        } catch (Throwable t) {
            // never propagate into the HMI
        }
    }

    private void confirmPick(int row) {
        if (down || orphanPending) {
            poster.post(retryJob);
            return;
        }
        UiDialog d = dialogUp;
        if (d == null || d.kind() != UiDialog.CONFIRM || sub == null || row < 0 || row > 1) {
            return; // answered already, or no confirm of ours
        }
        UiInputs.userPressed();
        answerDialog(row == 0 ? 1 : 0);
        subBack();
    }

    /** The speller's text changed (its SpellerListener). Never throws. */
    static void spellerTextChanged(String text) {
        try {
            PageScreens p = live;
            SpellerSession ss = p == null ? null : p.spellerSession;
            if (ss != null) {
                ss.onTextChanged(text);
            }
        } catch (Throwable t) {
            // never propagate into the HMI
        }
    }

    /** The speller's band opened or closed (its SpellerListener's {@code commandPressed}). Never throws. */
    static void spellerCommand(int command) {
        try {
            PageScreens p = live;
            SpellerSession ss = p == null ? null : p.spellerSession;
            if (ss != null) {
                ss.onCommand(command);
            }
        } catch (Throwable t) {
            // never propagate into the HMI
        }
    }

    /** The live SLIDER entry of typed slot {@code slot} of {@code sp} in {@code s}, or null. */
    private static UiEntry sliderEntry(Shown sp, int slot, UiSnapshot s) {
        PageLayout.TypedRow r = sp.layout.typedAt(slot);
        if (r == null || r.kind != UiEntry.SLIDER) {
            return null;
        }
        UiPage p = s == null ? null : s.pages().get(sp.pageKey);
        UiEntry e = p == null ? null : entry(p, r.entryKey);
        return e != null && e.kind() == UiEntry.SLIDER ? e : null;
    }

    private static UiEntry entry(UiPage p, String key) {
        List<UiEntry> all = p.entries();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).key().equals(key)) {
                return all.get(i);
            }
        }
        return null;
    }

    /**
     * In a refresh with a sub-screen up: the open slider follows the plugin's value. False if its entry is gone (the
     * slider must close); true otherwise (also for a sub-screen that is no slider).
     */
    private boolean syncSlider(UiPage p) {
        SliderSession ss = slider;
        Shown sp = shownPage;
        if (ss == null || sp == null) {
            return true;
        }
        PageLayout.TypedRow r = sp.layout.typedAt(ss.slot());
        UiEntry e = r == null ? null : entry(p, r.entryKey);
        if (e == null || e.kind() != UiEntry.SLIDER || !PageLayout.TypedRow.of(e).equals(r)) {
            return false;
        }
        ss.onModel(e.number());
        return true;
    }

    /** In a job: leaves the sub-screen for the page screen, rebuilt with the same stack top and the current rows. */
    private void backFromSub(UiSnapshot s) {
        clearSub();
        if (!changeAllowed()) {
            log.append("INFO", SOURCE, "ui page: back from the sub-screen dropped: a popup, parking display or"
                    + " screen change is under way");
            return;
        }
        showTop(s);
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
            if (screen == shown) {
                clearSub(); // the page is back: whatever sub-screen was over it is gone
                return;
            }
            if (shown != null) {
                return; // an older one on its way out
            }
            orphanPending = false; // our own page is up: whatever was orphaned is gone
            shown = screen;
            lastShown = screen;
            shownPage = new Shown(pageKey, layout, ++generations);
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
                if (sub != null) {
                    return; // our sub-screen replaced it: the page stays open
                }
                shown = null;
                rebuildPending = false;
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
                if (n.kind == Nav.SLIDER) {
                    showSlider(n.slot, s);
                } else if (n.kind == Nav.SUB_BACK) {
                    if (sub != null) {
                        backFromSub(s);
                    }
                } else if (n.target != null) {
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

    /** Builds and shows the slider screen of typed slot {@code slot}, if it still holds a live SLIDER entry. */
    private void showSlider(int slot, UiSnapshot s) throws Exception {
        Shown sp = shownPage;
        UiEntry e = sp == null || sub != null || !sp.pageKey.equals(stack.top()) ? null : sliderEntry(sp, slot, s);
        if (e == null) {
            return; // gone meanwhile
        }
        PageLayout.TypedRow r = sp.layout.typedAt(slot);
        slider = new SliderSession(r.min, r.step, r.steps, e.number(), slot); // its texts are served at connect
        try {
            openSub(HqcUiIds.SLIDER_SCREEN_ID, screens.buildSlider(slot, sp.layout, this));
            lastSubSlot = slot;
        } catch (Exception x) {
            clearSub();
            throw x;
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
            rebuildPending = false; // whatever was deferred is built now
            clearSub(); // a page shown replaces any sub-screen
            lastSub = null;
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
            shownPage = new Shown(key, layout, ++generations);
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
        dialog = null;
    }
}
