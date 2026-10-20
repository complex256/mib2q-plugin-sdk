package mibsdk.adapter.hqc.ui;

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
import mibsdk.adapter.hqc.SdkGlobals;
import mibsdk.platform.UiEntry;
import mibsdk.platform.UiPage;
import mibsdk.platform.UiRow;
import mibsdk.platform.UiSnapshot;
import mibsdk.platform.UiTable;
import mibsdk.runtime.Clock;
import mibsdk.runtime.RingLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The applier driven one pass at a time ({@code step(0)}), with fake rows, status line, pages, sink and clock. */
class UiApplierTest {

    private final UiTable table = new UiTable(8);
    private final List<String> writes = Collections.synchronizedList(new ArrayList<String>());
    private final HmiPosterTest.HeldSink sink = new HmiPosterTest.HeldSink();
    private final RingLog log = new RingLog(200);
    private volatile long now;
    private Runnable onOpen = () -> { };
    private UiApplier applier;
    private long version;

    private final RowModels rows = (slot, visible) -> writes.add("row" + slot + "=" + (visible ? "on" : "off"));
    private final StatusLineSink status = new StatusLineSink() {
        public void show(String text) {
            writes.add("show:" + text);
        }

        public void hide() {
            writes.add("hide");
        }
    };
    private final PageRenderer pages = new PageRenderer() {
        public void open(String pageKey, UiSnapshot s) {
            writes.add("open:" + pageKey + "@" + s.openSeq());
            onOpen.run();
        }

        public void refresh(UiSnapshot s) {
            writes.add("refresh:" + new java.util.TreeSet<String>(s.pages().keySet()));
        }

        public void close() {
            writes.add("close");
        }
    };

    @BeforeEach
    @AfterEach
    void reset() {
        SdkGlobals.uiSlots = DrawerAssignment.EMPTY;
        SdkGlobals.uiRowModels = null;
        SdkGlobals.uiDown = false;
    }

    @BeforeEach
    void build() {
        UiApplier[] self = new UiApplier[1];
        applier = newApplier(self);
        self[0] = applier;
    }

    private UiApplier newApplier(UiApplier[] self) {
        Clock clock = () -> now;
        HmiPoster poster = new HmiPoster(sink, () -> self[0].wake(), clock, HmiPoster.PENDING_DEADLINE_MS);
        return new UiApplier(table, rows, status, pages, poster, clock, log);
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
        applier.step(0);
        applier.stop(); // not started: no teardown thread, nothing to write
        assertEquals(Collections.emptyList(), drain());
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
            public void show(String text) {
                throw new IllegalStateException("popup gone");
            }

            public void hide() {
            }
        };
        self[0] = new UiApplier(table, failing, failingStatus, pages, poster, clock, log);
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
        oldJob.run();
        assertEquals(Collections.emptyList(), drain(), "the old applier's job renders nothing");
        self[0].step(0);
        sink.runHeld();
        assertTrue(drain().contains("open:p1@1"), "the new applier renders");
    }
}
