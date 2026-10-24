package mibsdk.adapter.hqc.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

        public Object build(String pageKey, String title, PageScreens owner) {
            if (failBuild) {
                throw new IllegalStateException("no factory");
            }
            calls.add("build " + pageKey + " '" + title + "'");
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

        public void writeRows(String[] texts, int[] kinds) {
            calls.add("rows " + Arrays.asList(texts));
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
                    ? "toggle" : "other";
            out.add(k + " " + in.pageKey() + (in.entryKey() == null ? "" : "/" + in.entryKey())
                    + (in.kind() == UiInput.TOGGLE_REQUESTED ? "=" + in.on() : ""));
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
        pages.screenConnected(screen, "a/p");
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
        assertEquals(Collections.singletonList("rows [[on] Rack]"), calls());
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
        pages.screenConnected(screen, "a/p");
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
}
