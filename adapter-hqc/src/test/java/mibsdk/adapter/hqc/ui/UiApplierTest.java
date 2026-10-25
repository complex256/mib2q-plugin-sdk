package mibsdk.adapter.hqc.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import de.esolutions.hmi.widgets.audi.evo.widgets.IconController;
import de.esolutions.hmi.widgets.audi.base.InitializationContext;
import de.esolutions.hmi.widgets.audi.evo.widgets.LabelController;
import de.esolutions.hmi.widgets.audi.evo.widgets.MainWizardController;
import de.esolutions.hmi.widgets.audi.evo.widgets.menu.MenuItemController;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The applier driven one pass at a time ({@code step(0)}), with fake rows, status line, pages, sink and clock. */
class UiApplierTest {

    private final UiTable table = new UiTable(8);
    private final List<String> writes = Collections.synchronizedList(new ArrayList<String>());
    private final HmiPosterTest.HeldSink sink = new HmiPosterTest.HeldSink();
    private final RingLog log = new RingLog(200);
    private volatile long now;
    private Runnable onOpen = () -> { };
    private final List<Long> ages = Collections.synchronizedList(new ArrayList<Long>());
    private UiApplier applier;
    private final IconIds ids = new IconIds(HqcUiIds.ICON_ID_FIRST, HqcUiIds.ICON_ID_LAST);
    private long version;

    private final RowModels rows = (slot, visible) -> writes.add("row" + slot + "=" + (visible ? "on" : "off"));
    private final StatusLineSink status = new StatusLineSink() {
        public void show(List<UiStatusPart> parts, String text) {
            writes.add("show:" + text);
        }

        public void hide() {
            writes.add("hide");
        }
    };
    private final PageRenderer pages = new PageRenderer() {
        public void open(String pageKey, UiSnapshot s, long requestAgeMs) {
            writes.add("open:" + pageKey + "@" + s.openSeq());
            ages.add(requestAgeMs);
            onOpen.run();
        }

        public void refresh(UiSnapshot s) {
            writes.add("refresh:" + new java.util.TreeSet<String>(s.pages().keySet()));
        }

        public void close() {
            writes.add("close");
        }

        public boolean closePending() {
            return closePending;
        }
    };
    private volatile boolean closePending;

    @BeforeEach
    @AfterEach
    void reset() {
        SdkGlobals.uiSlots = DrawerAssignment.EMPTY;
        SdkGlobals.uiRowModels = null;
        SdkGlobals.uiDown = false;
        SdkCarScreenFactory.sdkRows = null;
        SdkStatusPopup.current = null;
        SdkStatusPopup.wanted = StatusLayout.EMPTY;
        SdkGlobals.statusPopup = 0;
        SdkGlobals.mainMenu = SdkGlobals.MAIN_MENU_OFF;
        SdkGlobals.mainMenuRow = null;
        SdkGlobals.mainMenuWizard = null;
        MainMenuWrench.forgetForTests();
    }

    @BeforeEach
    void build() {
        UiApplier[] self = new UiApplier[1];
        applier = newApplier(self);
        self[0] = applier;
        applier.claimLive(); // what start() does; these tests drive step() by hand
    }

    private UiApplier newApplier(UiApplier[] self) {
        return newApplier(self, status, BuiltinIcons.NONE);
    }

    private UiApplier newApplier(UiApplier[] self, StatusLineSink s, BuiltinIcons builtins) {
        Clock clock = () -> now;
        HmiPoster poster = new HmiPoster(sink, () -> self[0].wake(), clock, HmiPoster.PENDING_DEADLINE_MS);
        return new UiApplier(table, rows, s, pages, poster, clock, log, ids, builtins, new HmiWidgets());
    }

    /** A live applier over {@code s} and {@code builtins}, replacing the default one. */
    private UiApplier applierWith(StatusLineSink s, BuiltinIcons builtins) {
        UiApplier[] self = new UiApplier[1];
        self[0] = newApplier(self, s, builtins);
        self[0].claimLive();
        return self[0];
    }

    private void publish(String status, List<String> rowKeys, Map<String, UiPage> pageMap, long openSeq,
                         String openKey) {
        List<UiRow> r = new ArrayList<UiRow>();
        for (String k : rowKeys) {
            r.add(new UiRow(k, k.toUpperCase(), k + ".page"));
        }
        table.publish(new UiSnapshot(++version, r, status, pageMap, openSeq, openKey));
    }

    private void publish(String status, String... rowKeys) {
        publish(status, Arrays.asList(rowKeys), null, 0, null);
    }

    private static Map<String, UiPage> pages(String... keys) {
        Map<String, UiPage> m = new HashMap<String, UiPage>();
        for (String k : keys) {
            m.put(k, new UiPage(k, k, Collections.singletonList(new UiEntry(UiEntry.INFO, "i", "info", null, false,
                    null))));
        }
        return m;
    }

    private List<String> drain() {
        synchronized (writes) {
            List<String> l = new ArrayList<String>(writes);
            writes.clear();
            return l;
        }
    }

    private static List<String> allRows(boolean... visible) {
        List<String> l = new ArrayList<String>();
        for (int s = 0; s < HqcUiIds.ROW_SLOTS; s++) {
            l.add("row" + s + "=" + (s < visible.length && visible[s] ? "on" : "off"));
        }
        return l;
    }

    @Test
    void writesOnlyChangedValues() throws Exception {
        SdkGlobals.uiRowModels = rows;
        publish("S", "plugins", "a");
        applier.step(0);
        List<String> first = new ArrayList<String>(allRows(true, true));
        first.add("show:S");
        assertEquals(first, drain(), "first pass writes every slot (state unknown) and the status");
        assertEquals("a", SdkGlobals.uiSlots.keyAt(1));

        publish("S", "plugins", "a"); // new version, same values
        applier.step(0);
        applier.step(0);
        assertEquals(Collections.emptyList(), drain());

        publish("S", "plugins", "a", "b");
        applier.step(0);
        assertEquals(Collections.singletonList("row2=on"), drain());

        publish("T", "plugins", "b");
        applier.step(0);
        assertEquals(Arrays.asList("row1=off", "show:T"), drain(), "b keeps slot 2");
    }

    @Test
    void emptyStatusLineHides() throws Exception {
        publish("S", "plugins");
        applier.step(0);
        assertEquals(Collections.singletonList("show:S"), drain());
        publish("", "plugins");
        applier.step(0);
        assertEquals(Collections.singletonList("hide"), drain());
        publish("", "plugins");
        applier.step(0);
        now += 60000;
        applier.step(0);
        assertEquals(Collections.emptyList(), drain(), "hidden stays hidden: no re-assert, no second hide");
    }

    @Test
    void statusReassertedEvery10s() throws Exception {
        publish("S", "plugins");
        applier.step(0);
        assertEquals(Collections.singletonList("show:S"), drain());
        now = 9999;
        applier.step(0);
        assertEquals(Collections.emptyList(), drain());
        now = 10000;
        applier.step(0);
        assertEquals(Collections.singletonList("show:S"), drain(), "a second show with the same text");
        now = 19999;
        applier.step(0);
        assertEquals(Collections.emptyList(), drain());
        now = 20000;
        applier.step(0);
        assertEquals(Collections.singletonList("show:S"), drain());
    }

    /** Review Focus 3: an open request published while a job is pending (or running) is never lost. */
    @Test
    void openRequestDuringPendingJobIsNotLost() throws Exception {
        SdkGlobals.uiRowModels = rows;
        Map<String, UiPage> both = pages("p1", "p2");
        publish("", Collections.singletonList("plugins"), both, 1, "p1");
        applier.step(0);
        assertEquals(1, sink.size(), "job 1 posted");
        drain();

        // Published while job 1 is running (after it read the snapshot): its after-hook posts job 2.
        onOpen = () -> {
            onOpen = () -> { };
            publish("", Collections.singletonList("plugins"), both, 2, "p2");
        };
        assertEquals(1, sink.runHeld());
        assertEquals(Collections.singletonList("open:p1@1"), drain());
        assertEquals(1, sink.size(), "job 2 posted by the after-hook");
        assertEquals(1, sink.runHeld());
        assertEquals(Collections.singletonList("open:p2@2"), drain());
        assertEquals(0, sink.size());

        // Published while job 3 is pending: the post is refused, and job 3 renders the latest request.
        publish("", Collections.singletonList("plugins"), both, 3, "p1");
        applier.step(0);
        publish("", Collections.singletonList("plugins"), both, 4, "p2");
        applier.step(0);
        assertEquals(1, sink.size(), "one pending job");
        sink.runHeld();
        assertEquals(Collections.singletonList("open:p2@4"), drain());
        applier.step(0);
        assertEquals(0, sink.size(), "nothing left to render");
    }

    @Test
    void pageContentChangeRefreshesOnce() throws Exception {
        SdkGlobals.uiRowModels = rows;
        publish("", Collections.singletonList("plugins"), pages("p1"), 0, null);
        applier.step(0);
        sink.runHeld();
        assertEquals(withRows(allRows(true), "refresh:[p1]"), drain());
        publish("", Collections.singletonList("plugins"), pages("p1"), 0, null); // equal content, new objects
        applier.step(0);
        assertEquals(0, sink.size());
        publish("", Collections.singletonList("plugins"), pages("p1", "p2"), 0, null);
        applier.step(0);
        sink.runHeld();
        assertEquals(Collections.singletonList("refresh:[p1, p2]"), drain());
    }

    /** Review Focus 5: the drawer cap is logged once per overflowing key, not once per snapshot. */
    @Test
    void overflowWarnsOncePerKey() throws Exception {
        String[] keys = {"plugins", "r1", "r2", "r3", "r4", "r5", "r6", "r7", "r8", "r9"};
        publish("", keys);
        applier.step(0);
        publish("", keys);
        applier.step(0);
        int warns = 0;
        for (String l : log.snapshot()) {
            if (l.contains("WARN") && l.contains("has no drawer slot")) {
                warns++;
                assertTrue(l.contains("mibsdk ui car row r9 has no drawer slot; reachable from the Plugins menu"), l);
            }
        }
        assertEquals(1, warns);
    }

    @Test
    void rowVisibilityAppliedWhenModelsAppearLate() throws Exception {
        publish("", "plugins", "a", "b");
        applier.step(0);
        assertEquals(Collections.emptyList(), drain(), "no row writes before the drawer exists; no status ever shown");
        assertEquals("b", SdkGlobals.uiSlots.keyAt(2), "the assignment is published regardless");

        SdkGlobals.uiRowModels = rows; // the drawer was built on HMIEvent
        applier.step(0); // no new snapshot
        assertEquals(allRows(true, true, true), drain());
        applier.step(0);
        assertEquals(Collections.emptyList(), drain());
    }

    /** Carried requirement 2: on stop no SDK row, status text or page stays on screen. */
    @Test
    void stopTearsDownRowsStatusAndPages() throws Exception {
        SdkGlobals.uiRowModels = rows;
        publish("S", Arrays.asList("plugins", "a"), pages("p1"), 1, "p1");
        applier.start();
        assertTrue(eventually(() -> sink.size() == 1));
        sink.runHeld();
        assertTrue(eventually(() -> writes.contains("open:p1@1")));
        drain();

        applier.stop();
        assertFalse(threadAlive("mibsdk-ui"), "stop waits for the teardown");
        assertSame(DrawerAssignment.EMPTY, SdkGlobals.uiSlots);
        List<String> expected = new ArrayList<String>(Arrays.asList("row0=off", "row1=off", "hide"));
        assertEquals(expected, drain(), "visible rows hidden, status hidden");
        assertEquals(1, sink.runHeld(), "a final page job");
        assertEquals(Collections.singletonList("close"), drain(), "back to the stock screen, not the stopped notice");
        assertTrue(SdkGlobals.uiDown, "a drawer built from now on starts with the Plugins row hidden");
        applier.stop(); // idempotent
        applier.start(); // ignored after stop
        assertFalse(threadAlive("mibsdk-ui"));
    }

    private static boolean threadAlive(String name) {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (name.equals(t.getName()) && t.isAlive()) {
                return true;
            }
        }
        return false;
    }

    private static boolean eventually(BooleanSupplier c) throws InterruptedException {
        long end = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < end) {
            if (c.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10);
        }
        return c.getAsBoolean();
    }

    @Test
    void samePagesComparesContent() {
        assertTrue(UiApplier.samePages(pages("a"), pages("a")));
        assertTrue(UiApplier.samePages(Collections.<String, UiPage>emptyMap(), null));
        assertFalse(UiApplier.samePages(pages("a"), null));
        assertFalse(UiApplier.samePages(pages("a"), pages("b")));
        Map<String, UiPage> on = new HashMap<String, UiPage>();
        on.put("a", new UiPage("a", "a", Collections.singletonList(new UiEntry(UiEntry.TOGGLE, "t", "T", null, true,
                null))));
        Map<String, UiPage> off = new HashMap<String, UiPage>();
        off.put("a", new UiPage("a", "a", Collections.singletonList(new UiEntry(UiEntry.TOGGLE, "t", "T", null, false,
                null))));
        assertFalse(UiApplier.samePages(on, off));
    }

    private static List<String> withRows(List<String> rowWrites, String... more) {
        List<String> l = new ArrayList<String>(rowWrites);
        l.addAll(Arrays.asList(more));
        return l;
    }

    /** Minor 2: popup 65 belongs to stock EngineeringApp until the SDK has shown text on it. */
    @Test
    void neverShownStatusIsNeverHidden() throws Exception {
        publish("", "plugins");
        applier.start();
        assertTrue(eventually(() -> SdkGlobals.uiSlots.keyAt(0) != null), "a pass ran");
        applier.stop(); // the exit teardown runs on the started thread
        assertFalse(threadAlive("mibsdk-ui"));
        assertTrue(SdkGlobals.uiDown, "the teardown ran");
        assertFalse(drain().contains("hide"), "popup 65 untouched: the SDK never showed text");
    }

    /** Round 2: an applier created but never started does not take page rendering from the running one. */
    @Test
    void createdButUnstartedApplierDoesNotStealRendering() throws Exception {
        SdkGlobals.uiRowModels = rows;
        UiApplier[] other = new UiApplier[1];
        other[0] = newApplier(other); // e.g. created on a waiter thread whose start was already stopped
        publish("", Collections.singletonList("plugins"), pages("p1"), 1, "p1");
        applier.step(0);
        sink.runHeld();
        assertTrue(drain().contains("open:p1@1"), "the running applier still renders");
    }

    /** Round 2: a replaced applier's exit teardown leaves the new applier's rows, slots and uiDown alone. */
    @Test
    void replacedAppliersExitTeardownTouchesNothing() throws Exception {
        SdkGlobals.uiRowModels = rows;
        publish("S", "plugins", "a");
        UiApplier[] old = new UiApplier[1];
        old[0] = newApplier(old);
        old[0].start();
        assertTrue(eventually(() -> writes.contains("show:S")));
        applier.claimLive(); // the restarted runtime's applier
        applier.step(0);
        drain();
        DrawerAssignment slots = SdkGlobals.uiSlots;
        old[0].stop();
        assertEquals(Collections.emptyList(), drain(), "no row or status writes from the old teardown");
        assertSame(slots, SdkGlobals.uiSlots);
        assertFalse(SdkGlobals.uiDown);
        assertEquals(0, sink.size());
    }

    /** Critical 1 (a): before the drawer exists the HMI may not have its first screen; no page job is posted. */
    @Test
    void noPageJobBeforeTheDrawerExists() throws Exception {
        publish("", Collections.singletonList("plugins"), pages("p1"), 1, "p1");
        applier.step(0);
        assertEquals(0, sink.size(), "no job while SdkGlobals.uiRowModels is null");
        SdkGlobals.uiRowModels = rows;
        applier.step(0);
        assertEquals(1, sink.size());
        sink.runHeld();
        assertTrue(drain().contains("open:p1@1"));
    }

    /** Critical 1 (b): a job the dispatcher silently dropped is re-posted after the deadline; the open is not lost. */
    @Test
    void silentlyDroppedPageJobIsRepostedAfterTheDeadline() throws Exception {
        SdkGlobals.uiRowModels = rows;
        publish("", Collections.singletonList("plugins"), pages("p1"), 1, "p1");
        applier.step(0);
        assertEquals(1, sink.size());
        sink.held.clear(); // StartupFilter discarded it
        now = HmiPoster.PENDING_DEADLINE_MS - 1;
        applier.step(0);
        assertEquals(0, sink.size(), "within the deadline: still waiting");
        now = HmiPoster.PENDING_DEADLINE_MS;
        applier.step(0);
        assertEquals(1, sink.size(), "re-posted");
        sink.runHeld();
        assertTrue(drain().contains("open:p1@1"));
    }

    /** Important 1: one failing phase never starves the others; each distinct failure is logged once. */
    @Test
    void failingPhaseDoesNotStarveTheOthers() throws Exception {
        SdkGlobals.uiRowModels = (slot, visible) -> {
            throw new IllegalStateException("row model gone");
        };
        UiApplier[] self = new UiApplier[1];
        Clock clock = () -> now;
        HmiPoster poster = new HmiPoster(sink, () -> self[0].wake(), clock, HmiPoster.PENDING_DEADLINE_MS);
        RowModels failing = (slot, visible) -> {
            throw new IllegalStateException("row model gone");
        };
        StatusLineSink failingStatus = new StatusLineSink() {
            public void show(List<UiStatusPart> parts, String text) {
                throw new IllegalStateException("popup gone");
            }

            public void hide() {
            }
        };
        self[0] = new UiApplier(table, failing, failingStatus, pages, poster, clock, log, ids, BuiltinIcons.NONE,
                new HmiWidgets());
        self[0].claimLive();
        publish("S", Arrays.asList("plugins", "a"), pages("p1"), 1, "p1");
        self[0].step(0);
        self[0].step(0);
        assertEquals(1, sink.size(), "pages still posted although rows and status failed");
        sink.runHeld();
        assertTrue(drain().contains("open:p1@1"));
        assertEquals("a", SdkGlobals.uiSlots.keyAt(1), "slots still assigned");
        int rowErrors = 0;
        int statusErrors = 0;
        for (String l : log.snapshot()) {
            rowErrors += l.contains("ERROR") && l.contains("row model gone") ? 1 : 0;
            statusErrors += l.contains("ERROR") && l.contains("popup gone") ? 1 : 0;
        }
        assertEquals(1, rowErrors, "once per phase and message: " + log.snapshot());
        assertEquals(1, statusErrors);
    }

    /**
     * Important 2: the runtime's down snapshot takes every piece of SDK UI off screen: all rows (the Plugins row too),
     * a shown status line, and an open page goes back to the stock screen. A running runtime's next snapshot resumes.
     */
    @Test
    void downSnapshotTearsEverythingDownAndARunningRuntimeResumes() throws Exception {
        SdkGlobals.uiRowModels = rows;
        publish("S", Arrays.asList("plugins", "a"), pages("p1"), 1, "p1");
        applier.step(0);
        sink.runHeld();
        drain();

        table.publish(UiSnapshot.down(++version));
        applier.step(0);
        assertEquals(Arrays.asList("row0=off", "row1=off", "hide"), drain());
        assertSame(DrawerAssignment.EMPTY, SdkGlobals.uiSlots);
        assertTrue(SdkGlobals.uiDown);
        assertEquals(1, sink.size());
        sink.runHeld();
        assertEquals(Collections.singletonList("close"), drain(), "the stock screen, not the stopped notice");
        applier.step(0);
        assertEquals(0, sink.size(), "closed once");

        version = 0; // a restarted runtime counts from 1 again
        publish("", Arrays.asList("plugins"), null, 0, null);
        applier.step(0);
        assertFalse(SdkGlobals.uiDown);
        assertEquals(Collections.singletonList("row0=on"), drain());
    }

    /** Important 2: a late job of a replaced applier never touches the new runtime's pages. */
    @Test
    void oldAppliersLateJobIsIgnored() throws Exception {
        SdkGlobals.uiRowModels = rows;
        publish("", Collections.singletonList("plugins"), pages("p1"), 1, "p1");
        applier.step(0);
        Runnable oldJob = sink.held.remove(0);
        drain();

        UiApplier[] self = new UiApplier[1];
        self[0] = newApplier(self); // a restarted runtime's applier
        self[0].claimLive();
        oldJob.run();
        assertEquals(Collections.emptyList(), drain(), "the old applier's job renders nothing");
        assertEquals(0, sink.size(), "and does not re-post itself from its after-hook");
        applier.wake();
        assertEquals(0, sink.size(), "a replaced applier never posts again");
        self[0].step(0);
        sink.runHeld();
        assertTrue(drain().contains("open:p1@1"), "the new applier renders");
    }

    /**
     * Carried requirement (open requests expire): the renderer gets the request's age, measured on the monotonic
     * clock from when the applier first saw that openSeq, so a request held back (no drawer yet, a dropped job) is
     * old when its job finally runs, and PageScreens drops it.
     */
    @Test
    void openRequestAgeRunsFromWhenTheApplierSawIt() throws Exception {
        now = 1000;
        publish("", Collections.singletonList("plugins"), pages("p1"), 1, "p1");
        applier.step(0); // seen at 1000; no drawer yet, so no job
        assertEquals(0, sink.size());
        now = 1000 + PageScreens.OPEN_TTL_MS + 500;
        SdkGlobals.uiRowModels = rows;
        applier.step(0);
        sink.runHeld();
        assertTrue(drain().contains("open:p1@1"));
        assertEquals(Collections.singletonList(PageScreens.OPEN_TTL_MS + 500), ages);

        ages.clear();
        publish("", Collections.singletonList("plugins"), pages("p1"), 2, "p1"); // a new request
        applier.step(0);
        now += 300;
        sink.runHeld();
        assertEquals(Collections.singletonList(300L), ages);
    }

    /** Carried requirement (hand-over): an old applier's close job does nothing once a newer one claimed the HMI. */
    @Test
    void oldAppliersLateCloseJobIsIgnored() throws Exception {
        SdkGlobals.uiRowModels = rows;
        publish("", Collections.singletonList("plugins"), pages("p1"), 1, "p1");
        applier.step(0);
        sink.runHeld();
        table.publish(UiSnapshot.down(++version));
        applier.step(0);
        assertEquals(1, sink.size(), "the close job is pending");
        Runnable closeJob = sink.held.remove(0);
        drain();

        UiApplier[] self = new UiApplier[1];
        self[0] = newApplier(self);
        self[0].claimLive();
        closeJob.run();
        assertFalse(drain().contains("close"), "the new applier's page stays");
    }

    /**
     * Round 2: while the renderer reports a deferred close (RVC/PDC), the down applier posts the close again on each
     * tick, never from the job's after-hook (no busy loop on HMIEvent), and stops once it is done.
     */
    @Test
    void deferredCloseIsRetriedOncePerTick() throws Exception {
        SdkGlobals.uiRowModels = rows;
        publish("", Collections.singletonList("plugins"), pages("p1"), 1, "p1");
        applier.step(0);
        sink.runHeld();
        table.publish(UiSnapshot.down(++version));
        closePending = true;
        applier.step(0);
        assertEquals(1, sink.size());
        sink.runHeld(); // close (deferred); its after-hook wakes the applier
        assertEquals(0, sink.size(), "no retry from the after-hook");
        drain();
        applier.step(0);
        assertEquals(1, sink.size(), "one retry per tick");
        sink.runHeld();
        assertEquals(Collections.singletonList("close"), drain());
        closePending = false;
        applier.step(0);
        assertEquals(0, sink.size(), "done");
    }

    private static UiIcon icon(String sha16) {
        return UiIcon.file("hello", HqcUiIds.INSTALL_ROOT + "/icons/hello/" + sha16 + ".png", sha16, 72, 72);
    }

    private void publishIcon(UiIcon icon) {
        List<UiRow> r = new ArrayList<UiRow>();
        r.add(new UiRow("plugins", "PLUGINS", "plugins.page"));
        r.add(new UiRow("a", "A", "a.page", icon));
        table.publish(new UiSnapshot(++version, r, "", null, 0, null));
    }

    /**
     * Task 4: a row's new icon content posts one HMI job (no pages to render), which sets that row's bitmaps once; an
     * unchanged snapshot posts nothing, and the new content gets a new id.
     */
    @Test
    void slotIconChangePostsOneJobAndAppliesBitmapsOnce() throws Exception {
        List<String> set = Collections.synchronizedList(new ArrayList<String>());
        MenuItemController[] drawer = new MenuItemController[HqcUiIds.ROW_SLOTS];
        for (int i = 0; i < drawer.length; i++) {
            final int slot = i;
            drawer[i] = new MenuItemController() {
                public void setBitmaps(int[] b) {
                    set.add(slot + "=" + Arrays.toString(b));
                    super.setBitmaps(b);
                }
            };
        }
        SdkCarScreenFactory.sdkRows = drawer;
        SdkGlobals.uiRowModels = rows;

        publishIcon(icon("aaaa000000000000"));
        applier.step(0);
        assertEquals(1, sink.size(), "one job");
        sink.runHeld();
        assertEquals(Collections.singletonList("1=[609100, 609100]"), set);
        assertEquals(Collections.emptyList(), drain().stream().filter(w -> !w.startsWith("row")).collect(
                java.util.stream.Collectors.toList()), "no page render");

        publishIcon(icon("aaaa000000000000")); // same content, new snapshot
        applier.step(0);
        assertEquals(0, sink.size(), "nothing changed: no job");

        publishIcon(icon("bbbb000000000000"));
        applier.step(0);
        assertEquals(1, sink.size());
        sink.runHeld();
        assertEquals(0, sink.size(), "the after-hook posts nothing more");
        assertEquals(Arrays.asList("1=[609100, 609100]", "1=[609101, 609101]"), set);
        assertArrayEquals(new int[] {609101, 609101}, drawer[1].getBitmapIndices());
        assertEquals(HqcUiIds.INSTALL_ROOT + "/icons/hello/aaaa000000000000.png", ids.fileFor(609100),
                "the old id keeps its file");

        publishIcon(null); // the icon goes away
        applier.step(0);
        sink.runHeld();
        assertEquals("1=null", set.get(set.size() - 1));
    }

    /** No drawer yet: the wanted icons are not posted (the StartupFilter would discard the job). */
    @Test
    void noIconJobBeforeTheDrawerExists() throws Exception {
        publishIcon(icon("cccc000000000000"));
        applier.step(0);
        assertEquals(0, sink.size());
    }

    // ---- Task 5: the status popup 609600 ----

    @TempDir
    java.io.File iconDir;

    private final List<String> popupCalls = Collections.synchronizedList(new ArrayList<String>());

    /** A built status popup whose widgets record their bounds and bitmaps. */
    private SdkStatusPopup recordingPopup() {
        HostWidgets.init();
        LabelController[] labels = new LabelController[HqcUiIds.STATUS_CELLS + 1];
        IconController[] idIcons = new IconController[HqcUiIds.STATUS_CELLS];
        IconController[] fileIcons = new IconController[HqcUiIds.STATUS_CELLS];
        for (int i = 0; i < labels.length; i++) {
            final String n = "label" + i;
            labels[i] = new LabelController() {
                public void setBounds(int x, int y, int w, int h) {
                    popupCalls.add(n + ".bounds");
                    super.setBounds(x, y, w, h);
                }
            };
        }
        for (int i = 0; i < HqcUiIds.STATUS_CELLS; i++) {
            final String n = "id" + i;
            final String f = "file" + i;
            idIcons[i] = new IconController() {
                public void setBounds(int x, int y, int w, int h) {
                    popupCalls.add(n + ".bounds");
                    super.setBounds(x, y, w, h);
                }

                public void setBitmaps(int[] b) {
                    popupCalls.add(n + ".bitmaps=" + Arrays.toString(b));
                    super.setBitmaps(b);
                }
            };
            fileIcons[i] = new IconController() {
                public void setBounds(int x, int y, int w, int h) {
                    popupCalls.add(f + ".bounds");
                    super.setBounds(x, y, w, h);
                }
            };
        }
        SdkStatusPopup p = new SdkStatusPopup(labels, idIcons, fileIcons);
        SdkStatusPopup.current = p;
        return p;
    }

    private void publishParts(String line, UiStatusPart... parts) {
        List<UiRow> r = Collections.singletonList(new UiRow("plugins", "PLUGINS", "plugins.page"));
        table.publish(new UiSnapshot(++version, r, line, null, 0, null, Arrays.asList(parts)));
    }

    private static UiStatusPart item(String text, UiIcon icon) {
        return new UiStatusPart(text, icon, false);
    }

    private List<String> drainPopup() {
        synchronized (popupCalls) {
            List<String> l = new ArrayList<String>(popupCalls);
            popupCalls.clear();
            return l;
        }
    }

    private static boolean anyBounds(List<String> calls) {
        for (String c : calls) {
            if (c.endsWith(".bounds")) {
                return true;
            }
        }
        return false;
    }

    @Test
    void stockIconSwapOfSameLengthAppliesBitmaps() throws Exception {
        UiApplier a = applierWith(status, StatusLayoutTest.builtins(iconDir));
        recordingPopup();
        publishParts("TS up", item("TS up", UiIcon.stock("dot")));
        a.step(0);
        assertEquals(1, sink.size());
        sink.runHeld();
        List<String> first = drainPopup();
        assertTrue(anyBounds(first), "the first layout places the cells: " + first);
        assertTrue(first.contains("id0.bitmaps=[609100]"), first.toString());

        publishParts("TS dn", item("TS dn", UiIcon.stock("cross")));
        a.step(0);
        assertEquals(1, sink.size(), "one job");
        sink.runHeld();
        assertEquals(Collections.singletonList("id0.bitmaps=[609101]"), drainPopup(), "bitmaps, no bounds");

        publishParts("TS dn", item("TS dn", null)); // the icon goes: the label moves left, the id icon is emptied
        a.step(0);
        sink.runHeld();
        List<String> gone = drainPopup();
        assertTrue(gone.contains("label1.bounds"), gone.toString());
        assertEquals("id0.bitmaps=[]", gone.get(gone.size() - 1), "an empty array hides it");
    }

    @Test
    void sinkSwitchReshowsWithinOnePass() throws Exception {
        StatusSinksTest.RecordingSink sdk = new StatusSinksTest.RecordingSink();
        StatusSinksTest.RecordingSink p65 = new StatusSinksTest.RecordingSink();
        int[] state = {2};
        UiApplier a = applierWith(new StatusSinks(sdk, p65, () -> state[0]), BuiltinIcons.NONE);
        publishParts("TS up", item("TS up", null));
        a.step(0);
        assertEquals(Arrays.asList("show TS up"), sdk.calls);
        a.step(0);
        assertEquals(1, sdk.calls.size(), "unchanged: no re-show");

        state[0] = -1; // the build failed
        a.step(0);
        assertEquals(Arrays.asList("show TS up"), p65.calls, "the next pass, with an unchanged line");
        assertEquals(Arrays.asList("show TS up", "hide"), sdk.calls);
        a.step(0);
        assertEquals(1, p65.calls.size());
    }

    @Test
    void layoutChangePostsOneJobTextChangeOnlyWritesModels() throws Exception {
        recordingPopup();
        UiStatusPart safe = new UiStatusPart("SAFE", null, true);
        publishParts("SAFE TS up MQ", safe, item("TS up", null), item("MQ", null));
        applier.step(0);
        assertEquals(Collections.singletonList("show:SAFE TS up MQ"), drain());
        assertEquals(1, sink.size(), "a new layout: one job");
        sink.runHeld();
        assertTrue(anyBounds(drainPopup()));

        publishParts("SAFE TS dn MQ", safe, item("TS dn", null), item("MQ", null));
        applier.step(0);
        assertEquals(Collections.singletonList("show:SAFE TS dn MQ"), drain(), "the text goes to the models");
        assertEquals(0, sink.size(), "same geometry: no job");

        publishParts("SAFE TS down MQ", safe, item("TS down", null), item("MQ", null));
        applier.step(0);
        assertEquals(Collections.singletonList("show:SAFE TS down MQ"), drain());
        assertEquals(1, sink.size(), "a longer text moves the next cell: one job");
        sink.runHeld();
        List<String> calls = drainPopup();
        assertTrue(calls.contains("label2.bounds"), calls.toString());
        assertEquals(0, sink.size(), "the after-hook posts nothing more");
    }

    @Test
    void aFileIconChangeWithTheSameTextIsShownAgain() throws Exception {
        UiIcon a = UiIcon.file("hello", "/i/a.png", "aaaa000000000000", 20, 20);
        UiIcon b = UiIcon.file("hello", "/i/b.png", "bbbb000000000000", 20, 20);
        publishParts("TS up", item("TS up", a));
        applier.step(0);
        publishParts("TS up", item("TS up", b));
        applier.step(0);
        assertEquals(Arrays.asList("show:TS up", "show:TS up"), drain(), "the new path reaches the locator model");
    }

    // ---- Task 7: the main menu's Plugins row ----

    /** The {@code row…} entries of {@link #writes} (drawer row visibility). */
    private List<String> rowWrites() {
        synchronized (writes) {
            List<String> l = new ArrayList<String>();
            for (String w : writes) {
                if (w.startsWith("row")) {
                    l.add(w);
                }
            }
            return l;
        }
    }

    /** A main-menu Plugins row that records its visibility writes into {@link #writes} as {@code menu=on|off}. */
    private MenuItemController menuRow() {
        HostWidgets.init();
        return new MenuItemController() {
            public void setVisible(boolean v) {
                writes.add("menu=" + (v ? "on" : "off"));
                super.setVisible(v);
            }
        };
    }

    /** A main wizard whose connection the test controls; counts what the wrench reads of it. */
    static final class Wizard extends MainWizardController {
        volatile boolean connected;
        int wrenched;

        public boolean isConnected() {
            return connected;
        }

        public InitializationContext getInitContext() {
            wrenched++; // MainMenuWrench.apply reads the layout through it once per call
            return super.getInitContext();
        }
    }

    /** Review Focus 3: screen 46 fails after the drawer hid its Plugins row; the row is back within one pass. */
    @Test
    void drawerPluginsRowReturnsWhenTheMainMenuFails() throws Exception {
        SdkGlobals.uiRowModels = rows;
        publish("", "plugins");
        SdkGlobals.mainMenu = SdkGlobals.MAIN_MENU_PENDING;
        applier.step(0); // snapshot with the Plugins row
        assertTrue(writes.contains("row0=on"), "PENDING: the drawer keeps it until screen 46 has the row");
        writes.clear();
        SdkGlobals.mainMenu = SdkGlobals.MAIN_MENU_ACTIVE; // screen 46 built with the SDK row
        applier.step(0);
        assertEquals(Arrays.asList("row0=off"), rowWrites()); // hidden: the MENU ring has it
        sink.runHeld(); // the main-menu row's job
        writes.clear();
        SdkGlobals.mainMenu = SdkGlobals.MAIN_MENU_FAILED; // screen 46 threw: refs cleared, ring stock
        applier.step(0); // no new snapshot needed
        assertEquals(Arrays.asList("row0=on"), rowWrites());
        assertEquals(0, sink.size(), "no main-menu row to apply: no job");
    }

    @Test
    void mainMenuOffKeepsTheDrawerRow() throws Exception {
        SdkGlobals.uiRowModels = rows;
        publish("", "plugins", "a");
        applier.step(0);
        assertEquals(allRows(true, true), rowWrites());
        applier.step(0);
        assertEquals(allRows(true, true), rowWrites(), "nothing written again");
        assertEquals(0, sink.size());
    }

    @Test
    void wrenchIsAppliedOncePerConnectedWizard() throws Exception {
        SdkGlobals.uiRowModels = rows;
        SdkGlobals.mainMenu = SdkGlobals.MAIN_MENU_ACTIVE;
        SdkGlobals.mainMenuRow = menuRow();
        Wizard first = new Wizard();
        SdkGlobals.mainMenuWizard = first;
        publish("", "plugins");
        applier.step(0);
        assertEquals(1, sink.size(), "the row and the wrench need a job");
        sink.runHeld();
        assertEquals(0, first.wrenched, "not connected yet: no wrench");
        assertTrue(writes.contains("menu=on"));
        assertEquals(0, sink.size(), "the after-hook does not loop on HMIEvent while the wizard connects");

        applier.step(0);
        assertEquals(1, sink.size(), "the next pass retries");
        first.connected = true;
        sink.runHeld();
        assertEquals(1, first.wrenched);
        applier.step(0);
        assertEquals(0, sink.size(), "applied: no more jobs");
        sink.runHeld();
        assertEquals(1, first.wrenched, "once");

        Wizard second = new Wizard(); // screen 46 rebuilt
        second.connected = true;
        SdkGlobals.mainMenuWizard = second;
        applier.step(0);
        assertEquals(1, sink.size());
        sink.runHeld();
        assertEquals(1, second.wrenched, "the new wizard gets its own call");
        assertEquals(1, first.wrenched);
        applier.step(0);
        assertEquals(0, sink.size());
    }

    @Test
    void downHidesTheMainMenuRowAndClearsStatusCells() throws Exception {
        HostWidgets.init();
        UiApplier a = applierWith(status, StatusLayoutTest.builtins(iconDir));
        recordingPopup();
        SdkGlobals.mainMenu = SdkGlobals.MAIN_MENU_ACTIVE;
        SdkGlobals.mainMenuRow = menuRow();
        publishParts("TS up", item("TS up", UiIcon.stock("dot")));
        a.step(0);
        sink.runHeld();
        assertTrue(drainPopup().contains("id0.bitmaps=[609100]"));
        assertTrue(drain().contains("menu=on"));

        table.publish(UiSnapshot.down(++version));
        a.step(0);
        sink.runHeld();
        List<String> w = drain();
        assertTrue(w.contains("menu=off"), w.toString());
        assertTrue(w.contains("hide"), w.toString());
        assertTrue(drainPopup().contains("id0.bitmaps=[]"), "the id icon cell is emptied");

        publishParts("TS up", item("TS up", UiIcon.stock("dot"))); // a running runtime resumes
        a.step(0);
        sink.runHeld();
        assertTrue(drain().contains("menu=on"));
        assertTrue(drainPopup().contains("id0.bitmaps=[609100]"));
    }
}
