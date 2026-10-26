package mibsdk.adapter.hqc.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import mibsdk.adapter.hqc.SdkGlobals;
import mibsdk.platform.PageStack;
import mibsdk.platform.UiEntry;
import mibsdk.platform.UiInput;
import mibsdk.platform.UiPage;
import mibsdk.platform.UiSnapshot;
import mibsdk.platform.UiTable;
import mibsdk.runtime.RingLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SDK-owned navigation over a fake HMI: the open gate (age, screen), LINK and BACK, leaving and returning, close.
 * Everything here runs on HMIEvent on the unit; the widgets themselves are verified on the bench (Task 19).
 */
class PageScreensNavigationTest {

    private static final int CAR_SCREEN = 600044;
    private static final String STOCK = "stock:600044";

    private final UiTable table = new UiTable(64);
    private final RingLog log = new RingLog(100);
    private final HmiPosterTest.HeldSink sink = new HmiPosterTest.HeldSink();
    private final HmiPoster poster = new HmiPoster(sink, null, () -> 0L, HmiPoster.PENDING_DEADLINE_MS);
    private final FakeScreens hmi = new FakeScreens();
    private PageScreens pages;
    private long version;

    /** The HMI as PageScreens sees it: what is on screen, and the calls it made. */
    static final class FakeScreens implements PageScreens.Screens {
        final List<String> calls = new ArrayList<String>();
        int currentId = CAR_SCREEN;
        boolean blocked;
        int pendingId = -1;
        Object current = STOCK;
        int built;
        boolean failBuild;
        boolean failScreenManager;
        final List<PageLayout> layouts = new ArrayList<PageLayout>();
        PageRow[] lastRows;

        public int currentId() {
            if (failScreenManager) {
                throw new IllegalStateException("no screen manager");
            }
            return currentId;
        }

        public boolean currentBlocked() {
            return blocked;
        }

        public int pendingId() {
            return pendingId;
        }

        public Object currentData() {
            return current;
        }

        public boolean isCurrent(Object screen) {
            return screen != null && screen == current;
        }

        public Object build(String pageKey, String title, PageScreens owner, PageLayout layout) {
            if (failBuild) {
                throw new IllegalStateException("no factory");
            }
            calls.add("build " + pageKey + " '" + title + "'");
            layouts.add(layout);
            return "screen" + (++built) + ":" + pageKey;
        }

        public void show(Object screen) {
            calls.add("show " + screen);
            current = screen; // connects at once here; on the unit it connects later
            currentId = HqcUiIds.PAGE_SCREEN_ID;
        }

        public void showStock(Object data) {
            calls.add("stock " + data);
            current = data;
            currentId = CAR_SCREEN;
        }

        public void showCarScreen(int id) {
            calls.add("car " + id);
            current = "car:" + id;
            currentId = id;
        }

        public Object buildSlider(int slot, PageLayout layout, PageScreens owner) {
            if (failBuild) {
                throw new IllegalStateException("no factory");
            }
            calls.add("slider " + slot);
            return "slider" + (++built);
        }

        public void writeRows(PageRow[] rows) {
            List<String> labels = new ArrayList<String>();
            for (PageRow r : rows) {
                labels.add(r.label);
            }
            calls.add("rows " + labels);
            lastRows = rows;
        }
    }

    @AfterEach
    void reset() {
        PageScreens.forgetForTests();
    }

    /** The monotonic clock of user presses (UiInputs) and of this test. */
    private volatile long now;

    @BeforeEach
    void recentPress() {
        PageScreens.forgetForTests(); // shared state of PageScreens and UiInputs
        UiInputs.clock = () -> now;
        UiInputs.userPressed(); // most tests open right after a press on an SDK drawer row
    }

    @BeforeEach
    void build() {
        PageStack stack = new PageStack(new PageEvents(table), PageScreens.failuresTo(log));
        pages = new PageScreens(hmi, stack, table, poster, log);
    }

    private static UiPage page(String key, String title, UiEntry... entries) {
        return new UiPage(key, title, Arrays.asList(entries));
    }

    private static UiEntry item(String key, String label) {
        return new UiEntry(UiEntry.ITEM, key, label, null, false, null);
    }

    private static UiEntry toggle(String key, String label, boolean on) {
        return new UiEntry(UiEntry.TOGGLE, key, label, null, on, null);
    }

    private static UiEntry link(String key, String label, String target) {
        return new UiEntry(UiEntry.LINK, key, label, null, false, target);
    }

    private static UiEntry info(String text) {
        return new UiEntry(UiEntry.INFO, "i", text, null, false, null);
    }

    private UiSnapshot publish(UiPage... ps) {
        Map<String, UiPage> m = new HashMap<String, UiPage>();
        for (UiPage p : ps) {
            m.put(p.key(), p);
        }
        UiSnapshot s = new UiSnapshot(++version, null, "", m, version, ps.length == 0 ? null : ps[0].key());
        table.publish(s);
        return s;
    }

    private List<String> inputs() throws InterruptedException {
        List<String> out = new ArrayList<String>();
        UiInput in;
        while ((in = table.poll(0)) != null) {
            String k = in.kind() == UiInput.PAGE_SHOWN ? "shown" : in.kind() == UiInput.PAGE_CLOSED ? "closed"
                    : in.kind() == UiInput.ENTRY_SELECTED ? "selected" : in.kind() == UiInput.TOGGLE_REQUESTED
                    ? "toggle" : in.kind() == UiInput.VALUE_REQUESTED ? "value" : "other";
            out.add(k + " " + in.pageKey() + (in.entryKey() == null ? "" : "/" + in.entryKey())
                    + (in.kind() == UiInput.TOGGLE_REQUESTED ? "=" + in.on() : "")
                    + (in.kind() == UiInput.VALUE_REQUESTED ? "=" + in.value() : ""));
        }
        return out;
    }

    private List<String> calls() {
        List<String> l = new ArrayList<String>(hmi.calls);
        hmi.calls.clear();
        return l;
    }

    private void runJobs() {
        sink.runHeld();
    }

    @Test
    void openFromTheCarScreenShowsThePageAndSendsShown() throws Exception {
        UiSnapshot s = publish(page("a/p", "Alpha", info("hello"), item("go", "Go")));
        pages.open("a/p", s, 0);
        assertEquals(Arrays.asList("rows [hello, Go]", "build a/p 'Alpha'", "show screen1:a/p"), calls());
        assertEquals(Collections.singletonList("shown a/p"), inputs());
        assertEquals("Alpha", PageScreens.title());
    }

    /** Carried requirement: an open request older than about 2 s is dropped, never replayed later. */
    @Test
    void staleOpenRequestIsDropped() throws Exception {
        UiSnapshot s = publish(page("a/p", "Alpha", item("go", "Go")));
        pages.open("a/p", s, PageScreens.OPEN_TTL_MS + 1);
        assertEquals(Collections.emptyList(), calls(), "nothing shown");
        assertEquals(Collections.singletonList("closed a/p"), inputs(), "the runtime stops counting it open");
        assertTrue(log.snapshot().toString().contains("dropped"), log.snapshot().toString());
        pages.refresh(s);
        assertEquals(Collections.emptyList(), calls(), "and a later refresh does not replay it");

        pages.open("a/p", s, PageScreens.OPEN_TTL_MS);
        assertTrue(calls().contains("show screen1:a/p"), "a request just within the limit opens");
    }

    /** Carried requirement: only where the SDK already is (Car drawer's screen, SDK page); never RVC/PDC. */
    @Test
    void openElsewhereOrDuringParkingIsDropped() throws Exception {
        UiSnapshot s = publish(page("a/p", "Alpha", item("go", "Go")));
        hmi.currentId = 1300014; // another module
        pages.open("a/p", s, 0);
        hmi.currentId = 2100001; // the RVC popup
        pages.open("a/p", s, 0);
        hmi.currentId = CAR_SCREEN;
        hmi.blocked = true; // a parking overlay or popup over the Car screen
        pages.open("a/p", s, 0);
        hmi.blocked = false;
        hmi.pendingId = 2100001; // reverse gear: the RVC is coming
        pages.open("a/p", s, 0);
        assertEquals(Collections.emptyList(), calls());
        assertEquals(Arrays.asList("closed a/p", "closed a/p", "closed a/p", "closed a/p"), inputs());
    }

    @Test
    void itemAndToggleSendInputsAndInfoSendsNothing() throws Exception {
        UiSnapshot s = publish(page("a/p", "Alpha", info("hi"), item("go", "Go"), toggle("t", "Rack", true)));
        pages.open("a/p", s, 0);
        inputs();
        PageScreens.listSelected(0);
        PageScreens.listSelected(1);
        PageScreens.listSelected(2);
        PageScreens.listSelected(3); // out of range
        PageScreens.listSelected(-1);
        assertEquals(Arrays.asList("selected a/p/go", "toggle a/p/t=false"), inputs());
        assertEquals(0, sink.size(), "inputs post no job");
    }

    /** LINK navigates locally (posted job); BACK pops; BACK on the bottom page returns to the stock screen. */
    @Test
    void linkPushesAndBackReturnsToTheStockScreen() throws Exception {
        UiSnapshot s = publish(page("a/p", "Alpha", link("more", "More", "a/q")), page("a/q", "Beta", item("x", "X")));
        pages.open("a/p", s, 0);
        calls();
        inputs();

        PageScreens.listSelected(0);
        assertEquals(Collections.emptyList(), calls(), "the screen change is posted, not done in the listener");
        runJobs();
        assertEquals(Arrays.asList("rows [X]", "build a/q 'Beta'", "show screen2:a/q"), calls());
        assertEquals(Arrays.asList("closed a/p", "shown a/q"), inputs());

        pages.backPressed();
        runJobs();
        assertEquals(Arrays.asList("rows [More >]", "build a/p 'Alpha'", "show screen3:a/p"), calls());
        assertEquals(Arrays.asList("closed a/q", "shown a/p"), inputs());

        pages.backPressed();
        runJobs();
        assertEquals(Collections.singletonList("stock " + STOCK), calls());
        assertEquals(Collections.singletonList("closed a/p"), inputs());
        pages.screenDisconnecting("screen3:a/p");
        assertEquals(Collections.emptyList(), inputs(), "our own BACK is not 'the user left'");
    }

    /** A LINK to a page the snapshot lacks shows the stopped notice, never a transient shown(missing). */
    @Test
    void linkToMissingPageShowsTheStoppedNotice() throws Exception {
        UiSnapshot s = publish(page("a/p", "Alpha", link("more", "More", "a/gone")));
        pages.open("a/p", s, 0);
        calls();
        inputs();
        PageScreens.listSelected(0);
        runJobs();
        List<String> c = calls();
        assertTrue(c.get(1).startsWith("build mibsdk/stopped"), c.toString());
        assertEquals(Arrays.asList("closed a/p", "shown mibsdk/stopped"), inputs());
    }

    /** A nav request while another SDK job is pending runs inside that job (one SDK job pending at most). */
    @Test
    void navRequestedWhileAJobIsPendingRunsInThatJob() throws Exception {
        UiSnapshot s = publish(page("a/p", "Alpha", link("more", "More", "a/q")), page("a/q", "Beta", item("x", "X")));
        pages.open("a/p", s, 0);
        calls();
        assertTrue(poster.post(() -> pages.refresh(table.snapshot()))); // the applier's page job
        PageScreens.listSelected(0);
        assertEquals(1, sink.size());
        runJobs();
        assertTrue(calls().contains("show screen2:a/q"));
    }

    /** The open request replaces the stack; from an SDK page the bottom returnTo stays the stock screen. */
    @Test
    void openOverAnSdkPageKeepsTheStockReturn() throws Exception {
        UiSnapshot s = publish(page("a/p", "Alpha", item("x", "X")), page("b/p", "Bravo", item("y", "Y")));
        pages.open("a/p", s, 0);
        pages.open("b/p", s, 0);
        calls();
        assertEquals(Arrays.asList("shown a/p", "closed a/p", "shown b/p"), inputs());
        pages.backPressed();
        runJobs();
        assertEquals(Collections.singletonList("stock " + STOCK), calls());
    }

    /** The user leaving (another app, RVC) closes the page; the page coming back (popup ended) opens it again. */
    @Test
    void leavingAndReturningFollowTheScreen() throws Exception {
        UiSnapshot s = publish(page("a/p", "Alpha", item("x", "X")));
        pages.open("a/p", s, 0);
        calls();
        inputs();
        Object screen = hmi.current;
        hmi.current = "rvc";
        hmi.currentId = 2100001;
        pages.screenDisconnecting(screen);
        assertEquals(Collections.singletonList("closed a/p"), inputs());
        pages.refresh(publish(page("a/p", "Alpha 2", item("y", "Y"))));
        assertEquals(Collections.emptyList(), calls(), "never shows a page while the user is away");

        hmi.current = screen;
        hmi.currentId = HqcUiIds.PAGE_SCREEN_ID;
        pages.screenConnected(screen, "a/p", PageLayout.EMPTY);
        assertEquals(Collections.singletonList("shown a/p"), inputs());
        assertEquals(Collections.singletonList("rows [Y]"), calls(), "the rows catch up with the snapshot");
        pages.backPressed();
        runJobs();
        assertEquals(Collections.singletonList("stock " + STOCK), calls());
    }

    @Test
    void refreshRewritesRowsAndFollowsAVanishedPage() throws Exception {
        UiSnapshot s = publish(page("a/p", "Alpha", toggle("t", "Rack", false)));
        pages.open("a/p", s, 0);
        calls();
        inputs();
        pages.refresh(publish(page("a/p", "Alpha", toggle("t", "Rack", true))));
        assertEquals(Collections.singletonList("rows [Rack]"), calls());
        assertEquals(new PageRow("Rack", 1, PageScreens.ROW_TOGGLE, true, null), hmi.lastRows[0], "the checkbox is on");
        PageScreens.listSelected(0);
        assertEquals(Collections.singletonList("toggle a/p/t=false"), inputs(), "the new state is the truth");

        pages.refresh(publish(page("a/p", "Alpha (renamed)", toggle("t", "Rack", true))));
        List<String> c = calls();
        assertTrue(c.contains("build a/p 'Alpha (renamed)'"), "a new title rebuilds the screen: " + c);

        pages.refresh(publish(page("o/x", "Other")));
        c = calls();
        assertTrue(c.get(1).startsWith("build mibsdk/stopped"), c.toString());
        assertEquals(Arrays.asList("closed a/p", "shown mibsdk/stopped"), inputs());
    }

    /** Task 10 carry: close works with no SDK page on screen (touches no screen) and never throws. */
    @Test
    void closeWithoutAnSdkPageTouchesNoScreen() throws Exception {
        pages.close();
        assertEquals(Collections.emptyList(), calls());
        hmi.failBuild = true;
        UiSnapshot s = publish(page("a/p", "Alpha"));
        pages.open("a/p", s, 0); // build fails: logged, nothing on screen, the page is not left open
        assertEquals(Arrays.asList("shown a/p", "closed a/p"), inputs());
        assertTrue(log.snapshot().toString().contains("no factory"), log.snapshot().toString());
        calls();
        pages.close();
        assertEquals(Collections.emptyList(), calls());
    }

    /** Task 10 carry: close (and every renderer call) never throws, even when the HMI does. */
    @Test
    void rendererCallsNeverThrow() throws Exception {
        hmi.failScreenManager = true;
        UiSnapshot s = publish(page("a/p", "Alpha"));
        pages.open("a/p", s, 0);
        pages.refresh(s);
        pages.close();
        assertEquals(Collections.emptyList(), calls());
        assertTrue(log.snapshot().toString().contains("no screen manager"), log.snapshot().toString());
    }

    /** Task 10 carry: close returns to the bottom page's returnTo and closes the open page. */
    @Test
    void closeReturnsToTheBottomPagesReturnTo() throws Exception {
        UiSnapshot s = publish(page("a/p", "Alpha", link("more", "More", "a/q")), page("a/q", "Beta"));
        pages.open("a/p", s, 0);
        PageScreens.listSelected(0);
        runJobs();
        calls();
        inputs();
        pages.close();
        assertEquals(Collections.singletonList("stock " + STOCK), calls());
        assertEquals(Collections.singletonList("closed a/q"), inputs());
        pages.close();
        assertEquals(Collections.emptyList(), calls(), "idempotent");
    }

    /** Carried requirement: a throwing page listener is logged and navigation still works. */
    @Test
    void throwingPageListenerIsLoggedAndNavigationGoesOn() throws Exception {
        PageStack stack = new PageStack(new PageStack.Listener() {
            public void shown(String k) {
                throw new IllegalStateException("boom");
            }

            public void closed(String k) {
                throw new IllegalStateException("boom");
            }
        }, PageScreens.failuresTo(log));
        pages = new PageScreens(hmi, stack, table, poster, log);
        UiSnapshot s = publish(page("a/p", "Alpha", item("x", "X")));
        pages.open("a/p", s, 0);
        pages.backPressed();
        runJobs();
        assertEquals("stock " + STOCK, calls().get(3));
        assertTrue(log.snapshot().toString().contains("boom"), log.snapshot().toString());
        assertNull(stack.top());
    }

    /** A newer PageScreens (restarted runtime) showing a page takes over: the old one's screen leaving is ignored. */
    @Test
    void newerInstanceTakesOverTheScreen() throws Exception {
        UiSnapshot s = publish(page("a/p", "Alpha", item("x", "X")));
        pages.open("a/p", s, 0);
        Object oldScreen = hmi.current;
        PageScreens old = pages;
        build();
        pages.open("a/p", s, 0);
        calls();
        inputs();
        old.screenDisconnecting(oldScreen);
        assertEquals(Collections.emptyList(), inputs(), "no stale closed from the replaced instance");
        PageScreens.listSelected(0);
        assertEquals(Collections.singletonList("selected a/p/x"), inputs());
        pages.backPressed();
        runJobs();
        assertSame(STOCK, hmi.current, "the stock return survived the hand-over");
    }

    // ---- fix round 1 --------------------------------------------------------------------------------------------

    /** Important 3: from a Car screen an open needs a recent SDK press; on an SDK page it does not. */
    @Test
    void openFromACarScreenNeedsARecentSdkPress() throws Exception {
        UiSnapshot s = publish(page("a/p", "Alpha", item("x", "X")), page("b/p", "Bravo", item("y", "Y")));
        now = PageScreens.OPEN_TTL_MS + 1; // the press is stale: a plugin opening by itself
        pages.open("a/p", s, 0);
        assertEquals(Collections.emptyList(), calls());
        assertEquals(Collections.singletonList("closed a/p"), inputs());

        UiInputs.rowPressed(table, DrawerAssignment.EMPTY, 3); // an empty slot is no SDK press
        pages.open("a/p", s, 0);
        assertEquals(Collections.emptyList(), calls());
        inputs();

        UiInputs.userPressed();
        now += PageScreens.OPEN_TTL_MS; // just within
        pages.open("a/p", s, 0);
        assertTrue(calls().contains("show screen1:a/p"));
        now += 10 * PageScreens.OPEN_TTL_MS; // long after: but an SDK page is on screen
        pages.open("b/p", s, 0);
        assertTrue(calls().contains("show screen2:b/p"));
    }

    /**
     * Task 7: the main menu (screen 46) is an origin like a Car screen, under the same recent-press rule; BACK returns
     * to the saved screen-46 data. Screen 45 is not an origin.
     */
    @Test
    void openFromTheMainMenuNeedsARecentPress() throws Exception {
        UiSnapshot s = publish(page("a/p", "Alpha", item("x", "X")));
        UiInputs.forgetForTests(); // no press yet
        UiInputs.clock = () -> now;
        hmi.currentId = HqcUiIds.SYS_SCREEN_MAINWIZARD;
        hmi.current = "stock:46";
        pages.open("a/p", s, 0);
        assertEquals(Collections.emptyList(), calls(), "screen 46 with no press: dropped");
        assertEquals(Collections.singletonList("closed a/p"), inputs());

        hmi.currentId = HqcUiIds.SYS_SCREEN_MAINWIZARD - 1;
        UiInputs.userPressed();
        now += 500;
        pages.open("a/p", s, 0);
        assertEquals(Collections.emptyList(), calls(), "screen 45: dropped");
        assertEquals(Collections.singletonList("closed a/p"), inputs());

        hmi.currentId = HqcUiIds.SYS_SCREEN_MAINWIZARD;
        pages.open("a/p", s, 0); // the press was 500 ms ago
        assertTrue(calls().contains("show screen1:a/p"));
        assertEquals(Collections.singletonList("shown a/p"), inputs());

        pages.backPressed();
        runJobs();
        assertEquals(Collections.singletonList("stock stock:46"), calls(), "BACK: the saved screen-46 data");
    }

    /** Important 3: selecting an entry on an SDK page counts as an SDK press. */
    @Test
    void anEntryPressCountsAsAnSdkPress() throws Exception {
        UiSnapshot s = publish(page("a/p", "Alpha", item("x", "X")));
        pages.open("a/p", s, 0);
        Object screen = hmi.current;
        now = 10 * PageScreens.OPEN_TTL_MS;
        PageScreens.listSelected(0);
        assertEquals(0, UiInputs.sinceLastPress());
        hmi.current = STOCK;
        hmi.currentId = CAR_SCREEN;
        pages.screenDisconnecting(screen);
        calls();
        pages.open("a/p", s, 0); // e.g. the item's handler opens a page from the Car screen
        assertTrue(calls().contains("show screen2:a/p"));
    }

    /** Important 1: while the RVC is coming (pending change) no LINK, BACK, rebuild or close changes the screen. */
    @Test
    void noScreenChangeWhileTheRvcIsComing() throws Exception {
        UiSnapshot s = publish(page("a/p", "Alpha", link("more", "More", "a/q")), page("a/q", "Beta"));
        pages.open("a/p", s, 0);
        Object screen = hmi.current;
        calls();
        inputs();
        hmi.pendingId = 2100001;

        PageScreens.listSelected(0);
        runJobs();
        pages.backPressed();
        runJobs();
        pages.refresh(publish(page("a/p", "Alpha renamed", link("more", "More", "a/q")), page("a/q", "Beta")));
        assertEquals(Collections.singletonList("rows [More >]"), calls(), "rows only: no build, show or stock");
        assertEquals(Collections.emptyList(), inputs(), "the stack did not move");

        pages.close();
        assertEquals(Collections.emptyList(), calls(), "close leaves the screen alone");
        assertEquals(Collections.singletonList("closed a/p"), inputs());

        hmi.pendingId = -1; // the RVC ended; the HMI goes back to the page
        pages.screenConnected(screen, "a/p", PageLayout.EMPTY);
        runJobs();
        assertEquals(Collections.singletonList("stock " + STOCK), calls(), "the close is retried");
        assertEquals(Collections.emptyList(), inputs(), "not adopted again: the runtime is down");
    }

    /** Important 1: the same with a parking overlay or popup on the page itself. */
    @Test
    void noScreenChangeUnderAParkingOverlay() throws Exception {
        UiSnapshot s = publish(page("a/p", "Alpha", link("more", "More", "a/q")), page("a/q", "Beta"));
        pages.open("a/p", s, 0);
        calls();
        hmi.blocked = true;
        PageScreens.listSelected(0);
        runJobs();
        pages.backPressed();
        runJobs();
        pages.close();
        assertEquals(Collections.emptyList(), calls());
    }

    /** Minor 1: a new applier's first page job sends an SDK page left by the previous one back to the stock screen. */
    @Test
    void orphanedPageOfAPreviousApplierIsClosed() throws Exception {
        UiSnapshot s = publish(page("a/p", "Alpha", item("x", "X")));
        pages.open("a/p", s, 0);
        Object oldScreen = hmi.current;
        PageScreens old = pages;
        calls();
        inputs();
        build(); // the restarted runtime's renderer; its first job is a refresh
        pages.refresh(s);
        assertEquals(Collections.singletonList("stock " + STOCK), calls());
        old.screenDisconnecting(oldScreen);
        assertEquals(Collections.emptyList(), inputs(), "the old instance sends nothing stale");
        pages.refresh(s);
        assertEquals(Collections.emptyList(), calls(), "only the first job looks");
    }

    /** Minor 2: the stock return is forgotten once used. */
    @Test
    void stockReturnIsClearedAfterUse() throws Exception {
        UiSnapshot s = publish(page("a/p", "Alpha", item("x", "X")));
        pages.open("a/p", s, 0);
        pages.backPressed();
        runJobs();
        assertNull(PageScreens.stockReturnForTests());
    }

    /** Minor 3: a dropped open does not cancel navigation the user asked for. */
    @Test
    void droppedOpenKeepsQueuedNavigation() throws Exception {
        UiSnapshot s = publish(page("a/p", "Alpha", link("more", "More", "a/q")), page("a/q", "Beta"));
        pages.open("a/p", s, 0);
        calls();
        assertTrue(poster.post(() -> pages.refresh(table.snapshot())));
        PageScreens.listSelected(0); // queued behind the pending job
        pages.open("o/x", s, PageScreens.OPEN_TTL_MS + 1); // stale: dropped
        runJobs();
        assertTrue(calls().contains("show screen2:a/q"));
    }

    // ---- fix round 2 --------------------------------------------------------------------------------------------

    /** An applier over this test's fake screens, with its own poster (wake() is the poster's after-hook). */
    private UiApplier applierOverPages() {
        UiApplier[] self = new UiApplier[1];
        HmiPoster p = new HmiPoster(sink, () -> self[0].wake(), () -> now, HmiPoster.PENDING_DEADLINE_MS);
        PageStack stack = new PageStack(new PageEvents(table), PageScreens.failuresTo(log));
        pages = new PageScreens(hmi, stack, table, p, log);
        StatusLineSink noStatus = new StatusLineSink() {
            public void show(java.util.List<mibsdk.platform.UiStatusPart> parts, String text) {
            }

            public void hide() {
            }
        };
        self[0] = new UiApplier(table, (slot, visible) -> { }, noStatus, pages, p, () -> now, log,
                new IconIds(HqcUiIds.ICON_ID_FIRST, HqcUiIds.ICON_ID_LAST), BuiltinIcons.NONE, new HmiWidgets());
        self[0].claimLive();
        return self[0];
    }

    /**
     * Round 2, Important: the runtime goes down while an OPS partial popup is on the page and the RVC is coming. The
     * close is deferred; the applier retries it on its tick (not in a busy loop of jobs), and once the popup is gone
     * the page goes back to the stock screen within one tick.
     */
    @Test
    void deferredCloseIsRetriedOnTheAppliersTick() throws Exception {
        SdkGlobals.uiRowModels = (slot, visible) -> { };
        try {
            UiApplier a = applierOverPages();
            publish(page("a/p", "Alpha", item("x", "X")));
            a.step(0);
            runJobs();
            assertTrue(calls().contains("show screen1:a/p"));
            inputs();

            hmi.blocked = true; // OPS partial popup over the page
            hmi.pendingId = 2100001; // and reverse gear
            table.publish(UiSnapshot.down(++version));
            a.step(0);
            runJobs();
            assertEquals(Collections.emptyList(), calls(), "deferred: the screen is left alone");
            assertTrue(pages.closePending());
            assertEquals(0, sink.size(), "no busy loop: the job's after-hook does not retry");
            a.step(0);
            runJobs();
            assertEquals(Collections.emptyList(), calls(), "still blocked on the next tick");

            hmi.blocked = false; // the partial popup is hidden; the page never reconnected
            hmi.pendingId = -1;
            a.step(0); // one tick
            runJobs();
            assertEquals(Collections.singletonList("stock " + STOCK), calls());
            assertFalse(pages.closePending());
            a.step(0);
            assertEquals(0, sink.size(), "done: no more jobs");
        } finally {
            SdkGlobals.uiRowModels = null;
            SdkGlobals.uiDown = false;
            SdkGlobals.uiSlots = DrawerAssignment.EMPTY;
        }
    }

    /** Round 2, Important: on a dead page every press retries the close and sends no input. */
    @Test
    void pressesOnADeadPageRetryTheCloseAndSendNothing() throws Exception {
        UiSnapshot s = publish(page("a/p", "Alpha", info("i"), item("x", "X"), toggle("t", "T", false)));
        pages.open("a/p", s, 0);
        calls();
        hmi.blocked = true;
        pages.close();
        inputs();
        for (int row = 0; row < 3; row++) {
            PageScreens.listSelected(row);
            assertEquals(1, sink.size(), "row " + row + " posts the retry");
            runJobs();
        }
        pages.backPressed();
        runJobs();
        assertEquals(Collections.emptyList(), inputs(), "no input for a dead page");
        assertEquals(Collections.emptyList(), calls());
        hmi.blocked = false;
        PageScreens.listSelected(1);
        runJobs();
        assertEquals(Collections.singletonList("stock " + STOCK), calls());
        assertEquals(Collections.emptyList(), inputs());
    }

    /**
     * Round 2, Minor: a pending close never pulls the user back from another screen; if the page comes back later,
     * the close runs then.
     */
    @Test
    void pendingCloseNeverPullsTheUserBack() throws Exception {
        UiSnapshot s = publish(page("a/p", "Alpha", item("x", "X")));
        pages.open("a/p", s, 0);
        Object screen = hmi.current;
        calls();
        hmi.blocked = true;
        pages.close();
        assertTrue(pages.closePending());

        hmi.blocked = false;
        hmi.current = "nav";
        hmi.currentId = 1300014;
        pages.close(); // the applier's retry
        assertEquals(Collections.emptyList(), calls(), "the user is on NAV: left alone");
        assertFalse(pages.closePending());

        hmi.current = screen;
        hmi.currentId = HqcUiIds.PAGE_SCREEN_ID;
        pages.screenConnected(screen, "a/p", PageLayout.EMPTY); // the HMI went back to the dead page
        runJobs();
        assertEquals(Collections.singletonList("stock " + STOCK), calls());
        inputs();
    }

    /** Round 2, Minor: a deferred orphan close logs once and does not stop the new renderer adopting its pages. */
    @Test
    void deferredOrphanCloseLogsOnceAndAdoptionGoesOn() throws Exception {
        UiSnapshot s = publish(page("a/p", "Alpha", item("x", "X")));
        pages.open("a/p", s, 0);
        calls();
        build(); // the restarted runtime's renderer
        hmi.blocked = true;
        pages.refresh(s);
        pages.refresh(s);
        pages.refresh(s);
        int deferred = 0;
        for (String l : log.snapshot()) {
            deferred += l.contains("deferred") ? 1 : 0;
        }
        assertEquals(1, deferred, log.snapshot().toString());
        assertEquals(Collections.emptyList(), calls());
        inputs();

        Object mine = "mine:a/p";
        pages.screenConnected(mine, "a/p", PageLayout.EMPTY); // one of the new renderer's own pages connects
        assertEquals(Collections.singletonList("shown a/p"), inputs(), "adopted");

        hmi.blocked = false;
        pages.refresh(s);
        assertFalse(calls().contains("stock " + STOCK), "the adopted page is not closed as an orphan");
    }

    /** Round 2 (cheap fallback): with no saved stock screen, BACK and close go to the Car screen, behind the gate. */
    @Test
    void noSavedStockScreenFallsBackToTheCarScreen() throws Exception {
        hmi.current = null; // currentData() gives nothing to return to
        UiSnapshot s = publish(page("a/p", "Alpha", item("x", "X")));
        pages.open("a/p", s, 0);
        calls();
        hmi.pendingId = 2100001;
        pages.backPressed();
        runJobs();
        assertEquals(Collections.emptyList(), calls(), "gated");
        hmi.pendingId = -1;
        pages.backPressed();
        runJobs();
        assertEquals(Collections.singletonList("car " + HqcUiIds.CAR_MAIN_SCREEN), calls());
    }

    // ---- native inputs (Task 4): disabled rows, the choice sub-page, item icons --------------------------------

    private static final mibsdk.platform.UiIcon ICON_A = mibsdk.platform.UiIcon.stock("a");
    private static final mibsdk.platform.UiIcon ICON_B = mibsdk.platform.UiIcon.stock("b");

    private static UiEntry iconItem(String key, mibsdk.platform.UiIcon icon, String value) {
        return new UiEntry(UiEntry.ITEM, key, key, value, false, null).withIcon(icon);
    }

    private static ItemIcons icons(mibsdk.platform.UiIcon... is) {
        Map<mibsdk.platform.UiIcon, Integer> m = new HashMap<mibsdk.platform.UiIcon, Integer>();
        for (int i = 0; i < is.length; i++) {
            m.put(is[i], 609100 + i);
        }
        return new ItemIcons(m);
    }

    private static UiPage languages(boolean english) {
        return page("p/main#lang", "Language", new UiEntry(UiEntry.RADIO, "0", "Deutsch", null, !english, null),
                new UiEntry(UiEntry.RADIO, "1", "English", null, english, null));
    }

    private static UiEntry language(int selected) {
        return new UiEntry(UiEntry.CHOICE, "lang", "Language", null, false, "p/main#lang")
                .withOptions(new String[] {"Deutsch", "English"}).withNumber(selected);
    }

    private long builds() {
        long n = 0;
        for (String c : calls()) {
            n += c.startsWith("build ") ? 1 : 0;
        }
        return n;
    }

    /** The HMI still sends a press on a greyed row: the adapter drops it (no input, no job). */
    @Test
    void disabledRowPressIsDropped() throws Exception {
        UiSnapshot s = publish(page("a/p", "Alpha", item("go", "Go").withEnabled(false),
                toggle("t", "Rack", true).withEnabled(false), link("more", "More", "a/q").withEnabled(false),
                language(0).withEnabled(false)), page("a/q", "Beta", item("x", "X")), languages(false));
        pages.open("a/p", s, 0);
        calls();
        inputs();
        for (int row = 0; row < 4; row++) {
            PageScreens.listSelected(row);
        }
        assertEquals(Collections.emptyList(), inputs());
        assertEquals(0, sink.size(), "no navigation job");
        assertFalse(pages.navPending());
        assertFalse(hmi.lastRows[0].enabled, "written greyed");
    }

    /** F26: DROPDOWN and SLIDER are not list rows, so list row i is the i-th list entry. */
    @Test
    void listRowsSkipDropdownAndSlider() throws Exception {
        UiEntry dropdown = new UiEntry(UiEntry.DROPDOWN, "d", "Mode", null, false, null)
                .withOptions(new String[] {"a", "b"});
        UiEntry slider = new UiEntry(UiEntry.SLIDER, "s", "Bass", null, false, null).withRange(-6, 6, 1, true);
        UiSnapshot s = publish(page("a/p", "Alpha", dropdown, item("go", "Go"), slider, toggle("t", "Rack", false)));
        pages.open("a/p", s, 0);
        assertEquals(Arrays.asList("rows [Go, Rack]", "build a/p 'Alpha'", "show screen1:a/p"), calls());
        inputs();
        PageScreens.listSelected(0);
        PageScreens.listSelected(1);
        assertEquals(Arrays.asList("selected a/p/go", "toggle a/p/t=true"), inputs());
    }

    /**
     * F16: a CHOICE row opens its choice page; a pick there is sent on the choice page's key and goes back. The page
     * events are CLOSED(old) before SHOWN(new) both ways; the hub's {@code #} mapping (Task 2) keeps {@code p/main}
     * open, so the plugin sees no PAGE_CLOSED for it.
     */
    @Test
    void choiceOpensSubPageAndPickReturns() throws Exception {
        UiSnapshot s = publish(page("p/main", "Main", language(0)), languages(false));
        pages.open("p/main", s, 0);
        assertEquals(new PageRow("Language", 0, PageScreens.ROW_VALUE, true, "Deutsch"), hmi.lastRows[0]);
        calls();
        inputs();

        PageScreens.listSelected(0);
        runJobs();
        assertEquals(Arrays.asList("rows [Deutsch, English]", "build p/main#lang 'Language'",
                "show screen2:p/main#lang"), calls());
        assertEquals(Arrays.asList("closed p/main", "shown p/main#lang"), inputs());
        assertEquals(new PageRow("Deutsch", 1, PageScreens.ROW_RADIO, true, null), hmi.lastRows[0]);

        PageScreens.listSelected(1);
        assertTrue(pages.navPending(), "the back is posted");
        runJobs();
        assertEquals(Arrays.asList("selected p/main#lang/1", "closed p/main#lang", "shown p/main"), inputs());
        assertEquals(Arrays.asList("rows [Language]", "build p/main 'Main'", "show screen3:p/main"), calls());
        assertFalse(pages.navPending());
    }

    @Test
    void choiceBackReturnsWithoutAction() throws Exception {
        UiSnapshot s = publish(page("p/main", "Main", language(1)), languages(true));
        pages.open("p/main", s, 0);
        PageScreens.listSelected(0);
        runJobs();
        calls();
        inputs();
        pages.backPressed();
        runJobs();
        assertEquals(Arrays.asList("closed p/main#lang", "shown p/main"), inputs(), "no ENTRY_SELECTED");
        assertTrue(calls().contains("show screen3:p/main"));
    }

    /** C7: a row's icon indexes the screen's icon bitmaps, so a new icon id needs a new screen; a new index not. */
    @Test
    void iconSetChangeRebuildsScreen() throws Exception {
        pages.itemIcons(icons(ICON_A, ICON_B));
        UiSnapshot s = publish(page("a/p", "Alpha", iconItem("x", ICON_A, null), item("y", "Y")));
        pages.open("a/p", s, 0);
        assertEquals(1, builds());
        assertArrayEquals(new int[] {609100}, hmi.layouts.get(0).iconIds());
        assertEquals(new PageRow("x", 0, PageScreens.ROW_ICON, true, null), hmi.lastRows[0]);

        pages.refresh(publish(page("a/p", "Alpha", iconItem("x", ICON_A, null), iconItem("y", ICON_B, null),
                iconItem("z", ICON_A, null))));
        assertEquals(1, builds(), "a new icon id: a new screen");
        assertArrayEquals(new int[] {609100, 609101}, hmi.layouts.get(1).iconIds());
        assertEquals(new PageRow("y", 1, PageScreens.ROW_ICON, true, null), hmi.lastRows[1]);

        pages.refresh(publish(page("a/p", "Alpha", iconItem("x", ICON_A, null), iconItem("y", ICON_B, null),
                iconItem("z", ICON_B, null))));
        assertEquals(0, builds(), "only a new index: rows only");
        assertEquals(new PageRow("z", 1, PageScreens.ROW_ICON, true, null), hmi.lastRows[2]);
    }

    /** B4: while the screen may not change, a row whose icon the shown screen lacks is written without it. */
    @Test
    void iconChangeWhileBlockedWritesNoIconKind() throws Exception {
        pages.itemIcons(icons(ICON_A, ICON_B));
        UiSnapshot s = publish(page("a/p", "Alpha", iconItem("x", ICON_A, null)));
        pages.open("a/p", s, 0);
        calls();
        hmi.blocked = true; // a popup or the parking display
        UiSnapshot next = publish(page("a/p", "Alpha", iconItem("x", ICON_A, null), iconItem("y", ICON_B, "on"),
                iconItem("z", ICON_B, null)));
        pages.refresh(next);
        assertEquals(0, builds(), "no rebuild while blocked");
        assertEquals(new PageRow("x", 0, PageScreens.ROW_ICON, true, null), hmi.lastRows[0], "a shown icon stays");
        assertEquals(new PageRow("y", 0, PageScreens.ROW_VALUE, true, "on"), hmi.lastRows[1]);
        assertEquals(new PageRow("z", 0, PageScreens.ROW_ACTION, true, null), hmi.lastRows[2]);

        assertTrue(pages.rebuildPending(), "the applier retries it on its tick");
        pages.refresh(next);
        assertEquals(0, builds(), "still blocked");
        assertTrue(pages.rebuildPending());

        hmi.blocked = false;
        pages.refresh(next);
        assertEquals(1, builds(), "the deferred rebuild");
        assertFalse(pages.rebuildPending());
        assertEquals(new PageRow("y", 1, PageScreens.ROW_ICON_VALUE, true, "on"), hmi.lastRows[1]);
        assertEquals(new PageRow("z", 1, PageScreens.ROW_ICON, true, null), hmi.lastRows[2]);
    }

    // ---- typed rows and the slider screen (Task 5) -----------------------------------------------------------------

    private static UiEntry dropdown(String key, String label, int selected, String... options) {
        return new UiEntry(UiEntry.DROPDOWN, key, label, null, false, null).withOptions(options).withNumber(selected);
    }

    private static UiEntry slider(String key, String label, int min, int max, int value) {
        return new UiEntry(UiEntry.SLIDER, key, label, null, false, null).withRange(min, max, 1, min < 0)
                .withNumber(value);
    }

    /** Opens {@code p}'s slider at typed slot {@code slot}: SELECT, the job, the page leaves, the slider connects. */
    private Object openSlider(int slot) {
        Object page = hmi.current;
        PageScreens.sliderPressed(slot);
        assertTrue(pages.navPending(), "the slider screen opens in a job");
        runJobs();
        pages.screenDisconnecting(page); // the slider screen replaced the page
        pages.subConnected(hmi.current);
        return hmi.current;
    }

    @Test
    void subScreenKeepsPageOpen() throws Exception {
        UiSnapshot s = publish(page("a/p", "Alpha", item("go", "Go"), slider("bass", "Bass", -6, 6, 0)));
        pages.open("a/p", s, 0);
        calls();
        inputs();
        Object page = hmi.current;
        pages.openSub(HqcUiIds.SLIDER_SCREEN_ID, "sub");
        assertEquals(Collections.singletonList("show sub"), calls());
        pages.screenDisconnecting(page);
        pages.subConnected("sub");
        assertEquals(Collections.emptyList(), inputs(), "no PAGE_CLOSED: the page stays open");
        assertSame(pages.shown(), PageScreens.shownNow(), "the page's key keeps routing input");
        assertEquals("a/p", pages.shown().pageKey);

        pages.refresh(publish(page("a/p", "Alpha 2", item("go", "Go"), slider("bass", "Bass", -6, 6, 0))));
        assertEquals(Collections.singletonList("rows [Go]"), calls(), "no rebuild under the sub-screen");
        assertEquals(Collections.emptyList(), inputs());
    }

    @Test
    void backFromSubScreenReshowsPageWithoutPop() throws Exception {
        UiSnapshot s = publish(page("a/p", "Alpha", link("more", "More", "a/q")),
                page("a/q", "Beta", item("x", "X"), slider("bass", "Bass", -6, 6, 0)));
        pages.open("a/p", s, 0);
        PageScreens.listSelected(0);
        runJobs();
        calls();
        inputs();
        int before = pages.shown().generation;

        Object sub = openSlider(0);
        assertEquals(Collections.singletonList("slider 0"), calls().subList(0, 1));
        assertEquals("slider3", sub);
        assertEquals(Collections.emptyList(), inputs());

        pages.subBack();
        runJobs();
        assertEquals(Arrays.asList("rows [X]", "build a/q 'Beta'", "show screen4:a/q"), calls(),
                "the page again, its rows written");
        pages.subDisconnecting(sub); // the slider leaves: we replaced it, not the user
        assertEquals(Collections.emptyList(), inputs(), "no stack pop, no page events");
        assertTrue(pages.shown().generation > before, "a new screen: a new generation");
        assertNull(PageScreens.sliderNow());

        pages.backPressed();
        runJobs();
        assertEquals(Arrays.asList("closed a/q", "shown a/p"), inputs(), "the stack still holds a/p under a/q");
    }

    @Test
    void sliderTurnsAccumulateAndAStockScreenLeavesThePage() throws Exception {
        UiSnapshot s = publish(page("a/p", "Alpha", dropdown("mode", "Mode", 0, "Off", "On"),
                slider("bass", "Bass", -6, 6, 0)));
        pages.open("a/p", s, 0);
        calls();
        inputs();
        Object sub = openSlider(1);
        SliderSession ss = PageScreens.sliderNow();
        assertEquals(1, ss.slot());
        assertEquals(6, ss.index());

        PageScreens.sliderTurned(1, 2);
        PageScreens.sliderTurned(1, 3);
        PageScreens.sliderTurned(0, 1); // not the open slider's slot
        assertEquals(Arrays.asList("value a/p/bass=2", "value a/p/bass=5"), inputs(), "from the last request");
        PageScreens.sliderTurned(1, 10);
        PageScreens.sliderTurned(1, 1); // at the end already: nothing to ask
        assertEquals(Collections.singletonList("value a/p/bass=6"), inputs());

        pages.refresh(publish(page("a/p", "Alpha", dropdown("mode", "Mode", 0, "Off", "On"),
                slider("bass", "Bass", -6, 6, 3))));
        assertEquals(9, ss.index(), "the plugin's value resets the index");
        calls();

        hmi.current = "stock:radio";
        hmi.currentId = 1300014;
        pages.subDisconnecting(sub);
        assertEquals(Collections.singletonList("closed a/p"), inputs(), "a stock screen over it: the user left");
        assertNull(PageScreens.sliderNow());
        PageScreens.sliderTurned(1, 1);
        assertEquals(Collections.emptyList(), inputs());
    }

    @Test
    void sliderEntryGoneClosesTheSliderScreen() throws Exception {
        UiSnapshot s = publish(page("a/p", "Alpha", item("go", "Go"), slider("bass", "Bass", -6, 6, 0)));
        pages.open("a/p", s, 0);
        calls();
        openSlider(0);
        calls();
        pages.refresh(publish(page("a/p", "Alpha", item("go", "Go"))));
        assertEquals(Arrays.asList("rows [Go]", "build a/p 'Alpha'", "show screen3:a/p"), calls());
        assertNull(PageScreens.sliderNow());
    }

    @Test
    void dropdownPickSendsTheIndex() throws Exception {
        UiSnapshot s = publish(page("a/p", "Alpha", dropdown("mode", "Mode", 0, "Off", "Low", "High")));
        pages.open("a/p", s, 0);
        inputs();
        PageScreens.typedChosen(0, 2);
        PageScreens.typedChosen(0, 3); // no such option
        PageScreens.typedChosen(1, 0); // no such slot
        assertEquals(Collections.singletonList("value a/p/mode=2"), inputs());
    }

    @Test
    void typedRowChangeRebuildsScreen() throws Exception {
        UiSnapshot s = publish(page("a/p", "Alpha", item("go", "Go")));
        pages.open("a/p", s, 0);
        calls();
        pages.refresh(publish(page("a/p", "Alpha", item("go", "Go"), slider("bass", "Bass", -6, 6, 0))));
        assertEquals(1, builds(), "a new typed row: a new screen");
        assertEquals(1, hmi.layouts.get(1).typed().length);
        pages.refresh(publish(page("a/p", "Alpha", item("go", "Go"), slider("bass", "Bass", -6, 6, 4))));
        assertEquals(0, builds(), "a new value only: the model (the applier's), no rebuild");
        pages.refresh(publish(page("a/p", "Alpha", item("go", "Go"), slider("bass", "Treble", -6, 6, 4))));
        assertEquals(1, builds(), "a new label: its text id is the screen's");
    }

    /** F26: list row indexes skip typed entries. */
    @Test
    void typedEntryBetweenListRowsKeepsRowIndexes() throws Exception {
        UiSnapshot s = publish(page("a/p", "Alpha", toggle("t", "Rack", false), dropdown("mode", "Mode", 0, "a"),
                item("go", "Go")));
        pages.open("a/p", s, 0);
        inputs();
        PageScreens.listSelected(1);
        assertEquals(Collections.singletonList("selected a/p/go"), inputs());
    }

    @Test
    void sliderPressOnStaleSlotDoesNothing() throws Exception {
        UiSnapshot s = publish(page("a/p", "Alpha", dropdown("mode", "Mode", 0, "a"),
                slider("bass", "Bass", -6, 6, 0)));
        pages.open("a/p", s, 0);
        calls();
        PageScreens.sliderPressed(0); // a dropdown
        PageScreens.sliderPressed(2); // no such slot
        publish(page("a/p", "Alpha", dropdown("mode", "Mode", 0, "a"))); // the slider is gone (no refresh yet)
        PageScreens.sliderPressed(1);
        assertFalse(pages.navPending());
        runJobs();
        assertEquals(Collections.emptyList(), calls());
    }

    @Test
    void sliderPressWhileBlockedDoesNothing() throws Exception {
        UiSnapshot s = publish(page("a/p", "Alpha", slider("bass", "Bass", -6, 6, 0)));
        pages.open("a/p", s, 0);
        calls();
        hmi.blocked = true;
        PageScreens.sliderPressed(0);
        assertFalse(pages.navPending());
        runJobs();
        assertEquals(Collections.emptyList(), calls());
        assertNull(PageScreens.sliderNow());
    }
}
