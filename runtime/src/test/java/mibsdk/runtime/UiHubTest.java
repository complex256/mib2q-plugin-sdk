package mibsdk.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import mibsdk.api.Listener;
import mibsdk.api.Page;
import mibsdk.api.PageSpec;
import mibsdk.api.Permissions;
import mibsdk.api.Scheduler;
import mibsdk.api.Subscription;
import mibsdk.api.UiAction;
import mibsdk.platform.UiEntry;
import mibsdk.platform.UiIcon;
import mibsdk.platform.UiInput;
import mibsdk.platform.UiPage;
import mibsdk.platform.UiRow;
import mibsdk.platform.UiSnapshot;
import mibsdk.platform.UiStatusPart;
import mibsdk.platform.UiTable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UiHubTest {

    private static final SerialExecutor.Listener QUIET = new SerialExecutor.Listener() {
        public void onFailure(String label, Throwable t) {
        }

        public void onSlow(String label, long ms) {
        }

        public void onHung(String label) {
        }
    };

    /** A Scheduler that never runs anything: publishes happen only through publishNow(). */
    private static final Scheduler NEVER = new Scheduler() {
        public Subscription after(long delayMs, Runnable task) {
            return NOOP;
        }

        public Subscription every(long periodMs, Runnable task) {
            return NOOP;
        }
    };

    private static final Subscription NOOP = new Subscription() {
        public void cancel() {
        }
    };

    @TempDir
    File tmp;

    private final FakeClock clock = new FakeClock();
    private final UiTable table = new UiTable(64);
    private final RingLog log = new RingLog(500);
    private final List<SerialExecutor> executors = new ArrayList<SerialExecutor>();
    private UiTestRuntime rtx;

    @AfterEach
    void tearDown() throws InterruptedException {
        for (SerialExecutor e : executors) {
            e.shutdown();
        }
        if (rtx != null) {
            rtx.stop();
        }
    }

    private SerialExecutor executor(String name) {
        SerialExecutor e = new SerialExecutor(name, clock, 5000, 30000, QUIET, false);
        executors.add(e);
        return e;
    }

    /** A hub whose trailing publishes run on a real PluginScheduler (FakeClock) posting to its own executor. */
    private UiHub hub(long minMs) {
        return new UiHub(table, log, clock, new PluginScheduler(executor("ui-publish"), clock), minMs);
    }

    private UiImpl ui(String id, UiHub hub) {
        return new UiImpl(id, hub, new LiveFeatures(UiTestRuntime.UI_FEATURES),
                new HashSet<String>(Arrays.asList(Permissions.UI_SLOT_CAR_MENU, Permissions.UI_SLOT_STATUS_BAR)),
                executor("plugin-" + id), new Registrations(), java.util.Collections.<String>emptyList(), null);
    }

    private UiSnapshot await(java.util.function.Predicate<UiSnapshot> p) throws InterruptedException {
        for (int i = 0; i < 500; i++) {
            UiSnapshot s = table.snapshot();
            if (p.test(s)) {
                return s;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("snapshot condition never met; last version " + table.snapshot().version());
    }

    // ---- snapshot content ----

    @Test
    void pluginPageAppearsInSnapshotUnderOwnerKey() throws Exception {
        UiHub hub = hub(0);
        hub.addPage("fruit", PageSpec.of("main", "Fruit").item("apple", "Apple", "3").toggle("ripe", "Ripe", true)
                .info("Fresh today"), null, executor("plugin-fruit"));
        hub.publishNow();

        UiPage p = table.snapshot().pages().get("fruit/main");
        assertNotNull(p, table.snapshot().pages().keySet().toString());
        assertEquals("fruit/main", p.key());
        assertEquals("Fruit", p.title());
        assertEquals(3, p.entries().size());
        UiEntry apple = p.entries().get(0);
        assertEquals(UiEntry.ITEM, apple.kind());
        assertEquals("apple", apple.key());
        assertEquals("Apple", apple.label());
        assertEquals("3", apple.value());
        assertEquals(UiEntry.TOGGLE, p.entries().get(1).kind());
        assertTrue(p.entries().get(1).on());
        assertEquals(UiEntry.INFO, p.entries().get(2).kind());
        assertEquals("Fresh today", p.entries().get(2).label());
        assertEquals(Collections.singletonList("fruit/main"), hub.pageKeysOf("fruit"));
    }

    @Test
    void carRowsKeepLoadOrderAfterPluginsRow() throws Exception {
        UiHub hub = hub(0);
        hub.registerOwner("zeta"); // enabled first...
        hub.registerOwner("alpha");
        hub.addPage("alpha", PageSpec.of("p", "A"), null, executor("plugin-alpha")); // ...but alpha contributes first
        hub.addCarRow("alpha", "r1", "Alpha", "alpha/p");
        hub.setStatusItem("alpha", "A");
        hub.addPage("zeta", PageSpec.of("p", "Z"), null, executor("plugin-zeta"));
        hub.addCarRow("zeta", "r1", "Zeta", "zeta/p");
        hub.setStatusItem("zeta", "Z");
        hub.publishNow();

        List<UiRow> rows = table.snapshot().carRows();
        assertEquals(3, rows.size());
        assertEquals("mibsdk/plugins", rows.get(0).key());
        assertEquals("Plugins", rows.get(0).label());
        assertEquals("mibsdk/plugins", rows.get(0).pageKey());
        assertEquals("Zeta", rows.get(1).label(), "rows follow enable order, not contribution order");
        assertEquals("zeta/p", rows.get(1).pageKey());
        assertEquals("Alpha", rows.get(2).label());
        assertEquals("A Z", table.snapshot().statusLine(), "status items follow plugin-id order, not enable order");

        hub.setPluginsRowLabel("Plugins (safe mode)");
        hub.publishNow();
        assertEquals("Plugins (safe mode)", table.snapshot().carRows().get(0).label());
        assertThrows(IllegalArgumentException.class, () -> hub.addCarRow("alpha", "r2", "x", "zeta/p"),
                "a row may only open a page of its own plugin");
    }

    @Test
    void strayContributionAfterRemoveOwnerKeepsTheNotice() throws Exception {
        UiHub hub = hub(0);
        SerialExecutor exec = executor("plugin-fruit");
        hub.registerOwner("fruit");
        hub.addPage("fruit", PageSpec.of("main", "Fruit"), null, exec);
        hub.requestOpen("fruit/main");
        hub.removeOwner("fruit", "Fruit stopped: test");

        hub.addPage("fruit", PageSpec.of("main", "Stray"), null, exec); // a callback still running after disable
        hub.setStatusItem("fruit", "stray");
        hub.updatePage("fruit", PageSpec.of("main", "Stray"));
        hub.publishNow();
        UiSnapshot s = table.snapshot();
        assertEquals("Fruit stopped: test", s.pages().get("fruit/main").entries().get(0).label());
        assertEquals("", s.statusLine());
        assertTrue(hub.pageKeysOf("fruit").isEmpty());

        hub.registerOwner("fruit"); // enabled again
        hub.addPage("fruit", PageSpec.of("main", "Again"), null, exec);
        hub.publishNow();
        assertEquals("Again", table.snapshot().pages().get("fruit/main").title());
    }

    @Test
    void linkToCancelledPageDoesNotOpen() throws Exception {
        UiHub hub = hub(0);
        UiImpl ui = ui("fruit", hub);
        Page target = ui.page(PageSpec.of("target", "T"), null);
        ui.page(PageSpec.of("main", "Fruit").link("go", "Go", target), null);
        target.cancel();
        hub.publishNow();
        long seq = table.snapshot().openSeq();

        hub.dispatch(new UiInput(UiInput.ENTRY_SELECTED, "fruit/main", "go", false));
        hub.publishNow();
        assertEquals(seq, table.snapshot().openSeq(), "a link to a page that is gone opens nothing");
    }

    @Test
    void statusMaxCharsIsClampedToFitTheMustShowIndicators() throws Exception {
        UiHub hub = hub(0);
        hub.setStatusItem("a", "ABCDEFGHIJKLMNOP");
        hub.setIndicators(Arrays.asList("SAFE", "PDC R OFF"));
        hub.setStatusMaxChars(1);
        hub.publishNow();
        assertEquals("SAFE PDC R OFF", table.snapshot().statusLine(), "never cut, at the smallest line");
        hub.setIndicators(null);
        hub.publishNow();
        assertEquals("ABCDEFGHI...", table.snapshot().statusLine(), "clamped to 14, not 1");
    }

    @Test
    void rowSelectedRequestsOpenOfItsPage() throws Exception {
        UiHub hub = hub(0);
        hub.addPage("fruit", PageSpec.of("main", "Fruit"), null, executor("plugin-fruit"));
        hub.addCarRow("fruit", "r1", "Fruit", "fruit/main");
        hub.publishNow();
        UiSnapshot before = table.snapshot();
        String rowKey = before.carRows().get(1).key();

        hub.dispatch(new UiInput(UiInput.ROW_SELECTED, rowKey, null, false));
        hub.publishNow();
        UiSnapshot after = table.snapshot();
        assertTrue(after.openSeq() > before.openSeq());
        assertEquals("fruit/main", after.openPageKey());

        hub.dispatch(new UiInput(UiInput.ROW_SELECTED, "mibsdk/plugins", null, false));
        hub.publishNow();
        assertTrue(table.snapshot().openSeq() > after.openSeq());
        assertEquals("mibsdk/plugins", table.snapshot().openPageKey());
    }

    @Test
    void pageOpenRequestsOpen() throws Exception {
        UiHub hub = hub(0);
        Page page = ui("fruit", hub).page(PageSpec.of("main", "Fruit"), null);
        UiSnapshot before = await(s -> s.pages().containsKey("fruit/main"));

        page.open();
        UiSnapshot after = await(s -> s.openSeq() > before.openSeq());
        assertEquals("fruit/main", after.openPageKey());
    }

    @Test
    void updateReplacesEntries() throws Exception {
        UiHub hub = hub(0);
        final Page page = ui("fruit", hub).page(PageSpec.of("main", "Fruit").item("a", "Apple", null), null);
        await(s -> s.pages().containsKey("fruit/main"));

        page.update(PageSpec.of("main", "Fruit 2").item("b", "Banana", "2").info("ok"));
        UiPage p = await(s -> "Fruit 2".equals(titleOf(s, "fruit/main"))).pages().get("fruit/main");
        assertEquals(2, p.entries().size());
        assertEquals("b", p.entries().get(0).key());
        assertEquals("Banana", p.entries().get(0).label());
        assertEquals("ok", p.entries().get(1).label());

        // Requirement: an update must name the page it updates.
        assertThrows(IllegalArgumentException.class, () -> page.update(PageSpec.of("other", "X")));
        assertThrows(IllegalArgumentException.class, () -> hub.updatePage("fruit", PageSpec.of("other", "X")),
                "the hub rejects an update for a page the owner does not have");
        assertThrows(IllegalArgumentException.class, () -> hub.updatePage("veg", PageSpec.of("main", "X")),
                "nor may another owner update it");
    }

    private static String titleOf(UiSnapshot s, String key) {
        UiPage p = s.pages().get(key);
        return p == null ? null : p.title();
    }

    // ---- input ----

    @Test
    void actionRunsOnPluginExecutorNotDispatcher() throws Exception {
        UiHub hub = hub(0);
        final AtomicReference<String> thread = new AtomicReference<String>();
        final AtomicReference<UiAction> got = new AtomicReference<UiAction>();
        hub.addPage("fruit", PageSpec.of("main", "Fruit").item("apple", "Apple", null).toggle("ripe", "Ripe", false),
                new Listener<UiAction>() {
                    public void on(UiAction a) {
                        thread.set(Thread.currentThread().getName());
                        got.set(a);
                    }
                }, executor("plugin-fruit"));
        UiDispatcher d = new UiDispatcher(table, hub);
        d.start();
        try {
            table.input(new UiInput(UiInput.TOGGLE_REQUESTED, "fruit/main", "ripe", true));
            for (int i = 0; i < 500 && got.get() == null; i++) {
                Thread.sleep(10);
            }
        } finally {
            d.stop();
        }
        assertNotNull(got.get(), "the action never arrived");
        assertEquals("mibsdk-plugin-fruit", thread.get());
        assertEquals("main", got.get().pageId());
        assertEquals("ripe", got.get().itemId());
        assertEquals(Boolean.TRUE, got.get().requestedOn());
    }

    // ---- lifecycle (real runtime) ----

    private static final String[] UI_PERMS = {Permissions.UI_SLOT_CAR_MENU, Permissions.UI_SLOT_STATUS_BAR};

    /** A plugin with page "main" (item "a"), a Car row to it and a status item; logs "action <item>". */
    private static final String FULL_UI = ""
            + "Page p = ctx.ui().page(PageSpec.of(\"main\", \"Fruit\").item(\"a\", \"Apple\", null),"
            + "  a -> ctx.log().info(\"action \" + a.itemId()));\n"
            + "ctx.ui().carMenuRow(\"Fruit\", p);\n"
            + "ctx.ui().statusItem(\"FR ok\");\n";

    private UiTestRuntime runtime() {
        rtx = new UiTestRuntime(tmp, UiTestRuntime.UI_FEATURES);
        return rtx;
    }

    @Test
    void disableRemovesRowsPagesAndStatusItem() throws Exception {
        UiTestRuntime r = runtime();
        r.plugin("fruit", "Fruit", UI_PERMS, FULL_UI, null);
        r.start();
        UiSnapshot on = r.await(s -> s.pages().containsKey("fruit/main") && s.carRows().size() == 2
                && "FR ok".equals(s.statusLine()), "shows fruit's UI");
        assertEquals("Fruit", on.carRows().get(1).label());

        r.rt.disable("fruit", "test");
        UiSnapshot off = r.await(s -> !s.pages().containsKey("fruit/main"), "drops fruit's page");
        assertEquals(1, off.carRows().size(), "only the Plugins row is left");
        assertEquals("mibsdk/plugins", off.carRows().get(0).key());
        assertEquals("", off.statusLine());
    }

    @Test
    void openPageOfDisabledPluginShowsStoppedNotice() throws Exception {
        UiTestRuntime r = runtime();
        r.plugin("fruit", "Fruit Basket", UI_PERMS, FULL_UI, null);
        r.start();
        UiSnapshot on = r.await(s -> s.carRows().size() == 2, "shows fruit's row");
        r.table.input(new UiInput(UiInput.ROW_SELECTED, on.carRows().get(1).key(), null, false));
        r.await(s -> "fruit/main".equals(s.openPageKey()), "opens fruit/main");
        r.table.input(new UiInput(UiInput.PAGE_SHOWN, "fruit/main", null, false));
        r.table.input(new UiInput(UiInput.ENTRY_SELECTED, "fruit/main", "a", false));
        r.awaitCount("fruit", "action a", 1);

        r.rt.disable("fruit", "test");
        UiSnapshot off = r.await(s -> UiTestRuntime.mentions(s, "stopped"), "shows the notice");
        UiPage notice = off.pages().get("fruit/main");
        assertNotNull(notice, "the notice replaces the open page under the same key");
        assertEquals(1, notice.entries().size());
        assertEquals(UiEntry.INFO, notice.entries().get(0).kind());
        assertEquals("Fruit Basket stopped: test", notice.entries().get(0).label());
        assertEquals(1, off.carRows().size(), "the row is gone");

        r.table.input(new UiInput(UiInput.ENTRY_SELECTED, "fruit/main", "a", false));
        r.table.input(new UiInput(UiInput.ENTRY_SELECTED, "fruit/main", "a", false));
        Thread.sleep(200);
        assertEquals(1, r.count("fruit", "action a"), "no input reaches the stopped plugin");
        int dropped = 0;
        for (String line : r.rt.log().snapshot()) {
            if (line.indexOf("ui input for fruit/main dropped") >= 0) {
                dropped++;
            }
        }
        assertEquals(1, dropped, "the drop is logged once per page\n" + r.rt.log().snapshot());

        r.table.input(new UiInput(UiInput.PAGE_CLOSED, "fruit/main", null, false));
        r.await(s -> !s.pages().containsKey("fruit/main"), "drops the notice once the page is closed");
    }

    @Test
    void reloadShowsNoStoppedNotice() throws Exception {
        UiTestRuntime r = runtime();
        r.plugin("fruit", "Fruit", UI_PERMS, FULL_UI, null);
        r.start();
        UiSnapshot on = r.await(s -> s.carRows().size() == 2, "shows fruit's row");
        r.table.input(new UiInput(UiInput.ROW_SELECTED, on.carRows().get(1).key(), null, false));
        final UiSnapshot opened = r.await(s -> "fruit/main".equals(s.openPageKey()), "opens fruit/main");
        r.table.input(new UiInput(UiInput.PAGE_SHOWN, "fruit/main", null, false));

        final List<UiSnapshot> seen = Collections.synchronizedList(new ArrayList<UiSnapshot>());
        final boolean[] stop = {false};
        Thread watcher = new Thread() {
            public void run() {
                long v = opened.version();
                while (!stop[0]) {
                    try {
                        UiSnapshot s = r.table.awaitNewer(v, 20);
                        if (s != null) {
                            seen.add(s);
                            v = s.version();
                        }
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        };
        watcher.start();
        try {
            r.rt.reload("fruit");
            r.awaitState("fruit", PluginState.ENABLED);
            r.await(s -> s.pages().containsKey("fruit/main") && s.carRows().size() == 2, "shows fruit again");
            Thread.sleep(100);
        } finally {
            stop[0] = true;
            watcher.join(2000);
        }
        assertFalse(seen.isEmpty(), "the reload published snapshots");
        for (UiSnapshot s : seen) {
            assertFalse(UiTestRuntime.mentions(s, "stopped:"), "v" + s.version() + " shows a stopped notice");
        }
    }

    @Test
    void throwingActionListenerCountsTowardQuarantine() throws Exception {
        UiTestRuntime r = runtime();
        r.plugin("bad", "Bad", new String[0], ""
                + "ctx.ui().page(PageSpec.of(\"main\", \"Bad\").item(\"a\", \"A\", null),"
                + "  a -> { throw new IllegalStateException(\"nope\"); });", null);
        r.start();
        r.await(s -> s.pages().containsKey("bad/main"), "shows bad's page");
        for (int i = 0; i < 3; i++) {
            r.table.input(new UiInput(UiInput.ENTRY_SELECTED, "bad/main", "a", false));
        }
        r.awaitState("bad", PluginState.QUARANTINED);
    }

    @Test
    void noInputReachesPluginsAfterStop() throws Exception {
        UiTestRuntime r = runtime();
        r.plugin("fruit", "Fruit", UI_PERMS, FULL_UI, null);
        r.start();
        r.await(s -> s.pages().containsKey("fruit/main"), "shows fruit's page");
        r.table.input(new UiInput(UiInput.ENTRY_SELECTED, "fruit/main", "a", false));
        r.awaitCount("fruit", "action a", 1);

        r.rt.stop();
        r.table.input(new UiInput(UiInput.ENTRY_SELECTED, "fruit/main", "a", false));
        Thread.sleep(300);
        assertEquals(1, r.count("fruit", "action a"));
        assertNotNull(r.table.poll(0), "the input stays in the table: nobody consumes it after stop");
    }

    @Test
    void publishesWhileAPluginBlocksTheControlThread() throws Exception {
        final UiTestRuntime r = runtime();
        r.plugin("apple", "Apple", UI_PERMS, FULL_UI.replace("fruit", "apple"), null);
        r.plugin("zslow", "Slow", new String[0], "ctx.log().info(\"blocking\"); Thread.sleep(8000L);", null);
        Thread starter = new Thread() {
            public void run() {
                try {
                    r.start(); // blocks until the load is done: zslow's onEnable holds the control thread
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };
        starter.setDaemon(true);
        starter.start();
        for (int i = 0; i < 500 && r.rt == null; i++) {
            Thread.sleep(10);
        }
        UiSnapshot on = r.await(s -> s.carRows().size() == 2, "shows apple's row while zslow is enabling");
        r.table.input(new UiInput(UiInput.ROW_SELECTED, on.carRows().get(1).key(), null, false));
        UiSnapshot opened = r.await(s -> s.openSeq() > on.openSeq(), "opens apple's page");
        assertEquals("apple/main", opened.openPageKey());
        for (PluginStatus st : r.rt.statuses()) {
            if (st.id().equals("zslow")) {
                assertTrue(st.state() != PluginState.ENABLED, "zslow's onEnable has not returned yet");
            }
        }
        starter.join(15000);
    }

    @Test
    void initialSnapshotAndInputInSafeMode() throws Exception {
        UiTestRuntime r = runtime();
        r.plugin("fruit", "Fruit", UI_PERMS, FULL_UI, null);
        r.safeModeMarker();
        r.start();
        assertTrue(r.rt.safeMode());
        UiSnapshot s = r.table.snapshot();
        assertEquals(1, s.carRows().size(), "the Plugins row is published at start, with no plugin UI");
        assertEquals("mibsdk/plugins", s.carRows().get(0).key());

        r.table.input(new UiInput(UiInput.ROW_SELECTED, "mibsdk/plugins", null, false));
        UiSnapshot opened = r.await(x -> x.openSeq() > s.openSeq(), "takes input in safe mode");
        assertEquals("mibsdk/plugins", opened.openPageKey());
    }

    @Test
    void dispatcherIsDownBeforeLedgerShutdown() throws Exception {
        final UiTestRuntime r = runtime();
        r.plugin("fruit", "Fruit", UI_PERMS, FULL_UI, null);
        r.start();
        r.await(s -> s.pages().containsKey("fruit/main"), "shows fruit's page");
        assertTrue(r.rt.uiDispatcherRunning());
        final Boolean[] running = {null};
        SdkRuntime.testBeforeLedgerShutdown = new Runnable() {
            public void run() {
                running[0] = Boolean.valueOf(r.rt.uiDispatcherRunning());
            }
        };
        try {
            r.rt.stop();
        } finally {
            SdkRuntime.testBeforeLedgerShutdown = null;
        }
        assertEquals(Boolean.FALSE, running[0], "the dispatcher was still running at ledger.beginShutdown()");
    }

    // ---- publishing ----

    @Test
    void publishCoalescesBursts() throws Exception {
        PluginScheduler timer = new PluginScheduler(executor("ui-publish"), clock);
        UiHub hub = new UiHub(table, log, clock, timer, 100);
        for (int i = 0; i < 10000; i++) {
            hub.setStatusItem("fruit", "n" + i);
            if (i % 200 == 199) {
                clock.advance(1); // 50 ms in all
            }
        }
        Thread.sleep(300); // let an immediate publish (if any) run
        long versions = table.snapshot().version();
        assertTrue(versions <= 2, "published " + versions + " versions for one burst");

        clock.advance(100);
        timer.runDue();
        UiSnapshot last = await(s -> "n9999".equals(s.statusLine()));
        assertTrue(last.version() <= 2, "published " + last.version() + " versions in all");
    }

    @Test
    void publishedVersionsStrictlyIncrease() throws Exception {
        final UiHub hub = new UiHub(table, log, clock, NEVER, 0);
        final List<Long> seen = Collections.synchronizedList(new ArrayList<Long>());
        final boolean[] stop = {false};
        Thread watcher = new Thread() {
            public void run() {
                long v = 0;
                while (!stop[0]) {
                    try {
                        UiSnapshot s = table.awaitNewer(v, 20);
                        if (s != null) {
                            seen.add(s.version());
                            v = s.version();
                        }
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        };
        watcher.start();
        Thread[] writers = new Thread[4];
        for (int t = 0; t < writers.length; t++) {
            final int id = t;
            writers[t] = new Thread() {
                public void run() {
                    for (int i = 0; i < 250; i++) {
                        hub.setStatusItem("p" + id, "v" + i);
                        hub.publishNow();
                    }
                }
            };
            writers[t].start();
        }
        for (Thread w : writers) {
            w.join(10000);
        }
        Thread.sleep(50);
        stop[0] = true;
        watcher.join(2000);

        assertEquals(1000, table.snapshot().version(), "the last publish carries the highest version");
        for (int i = 1; i < seen.size(); i++) {
            assertTrue(seen.get(i) > seen.get(i - 1), "version went " + seen.get(i - 1) + " -> " + seen.get(i));
        }
    }

    @Test
    void statusLineJoinsIndicatorsThenItemsInPluginIdOrder() throws Exception {
        UiHub hub = hub(0);
        hub.setStatusItem("b", "B up");
        hub.setStatusItem("a", "A up");
        hub.setIndicators(Arrays.asList("SAFE"));
        hub.publishNow();
        assertEquals("SAFE A up B up", table.snapshot().statusLine());
        hub.setStatusMaxChars(14);
        hub.setStatusItem("b", null);
        hub.setStatusItem("a", "A up and away");
        hub.publishNow();
        assertEquals("SAFE A up a...", table.snapshot().statusLine(), "the item keeps the room SAFE leaves");
        assertNull(table.snapshot().openPageKey());
    }

    @Test
    void statusPrefsOrderAndHideItemsWithoutTouchingThePlugin() throws Exception {
        UiHub hub = hub(0);
        hub.registerOwner("zeta");
        hub.registerOwner("alpha");
        hub.registerOwner("mid");
        hub.setStatusItem("zeta", "Z");
        hub.setStatusItem("alpha", "A");
        hub.setStatusItem("mid", "M");
        hub.publishNow();
        assertEquals("A M Z", table.snapshot().statusLine());
        assertEquals(new HashSet<String>(Arrays.asList("alpha", "mid", "zeta")), hub.statusOwners());

        hub.setStatusPrefs(Arrays.asList("zeta", "gone"), Collections.<String>emptySet());
        hub.publishNow();
        assertEquals("Z A M", table.snapshot().statusLine(), "saved order first, then plugin-id order");

        hub.setStatusPrefs(Arrays.asList("zeta"), new HashSet<String>(Arrays.asList("alpha")));
        hub.publishNow();
        assertEquals("Z M", table.snapshot().statusLine(), "a hidden item is not composed");
        hub.setStatusItem("alpha", "A2"); // the plugin's own API still works
        hub.publishNow();
        assertEquals("Z M", table.snapshot().statusLine());
        assertTrue(hub.statusOwners().contains("alpha"), "it still has an item");

        hub.setStatusPrefs(null, null);
        hub.publishNow();
        assertEquals("A2 M Z", table.snapshot().statusLine(), "shown again with its latest text");
    }

    @Test
    void rowsAndStatusPartsCarryIcons() throws Exception {
        UiHub hub = hub(0);
        UiIcon star = UiIcon.stock("star");
        UiIcon png = UiIcon.file("fruit", "/icons/fruit/0123456789abcdef.png", "0123456789abcdef", 20, 20);
        hub.addPage("fruit", PageSpec.of("p", "F"), null, executor("plugin-fruit"));
        hub.addCarRow("fruit", "r1", "Fruit", "fruit/p", png);
        hub.addCarRow("fruit", "r2", "Plain", "fruit/p");
        hub.setStatusItem("fruit", "FR ok", star);
        hub.setIndicators(Arrays.asList("SAFE"));
        hub.publishNow();
        UiSnapshot s = table.snapshot();
        assertNull(s.carRows().get(0).icon(), "the Plugins row has none");
        assertEquals(png, s.carRows().get(1).icon());
        assertNull(s.carRows().get(2).icon());
        List<UiStatusPart> parts = s.statusParts();
        assertEquals(2, parts.size());
        assertEquals("SAFE", parts.get(0).text());
        assertTrue(parts.get(0).indicator());
        assertEquals("FR ok", parts.get(1).text());
        assertEquals(star, parts.get(1).icon());

        hub.setStatusItem("fruit", "FR ok", null); // same text, icon cleared: still a change
        hub.publishNow();
        assertNull(table.snapshot().statusParts().get(1).icon());
        hub.setStatusItem("fruit", "FR ok", star);
        hub.publishNow();
        assertEquals(star, table.snapshot().statusParts().get(1).icon());
        hub.setStatusItem("fruit", "", star); // an empty text hides the item, icon or not
        hub.publishNow();
        assertEquals(1, table.snapshot().statusParts().size());
        assertEquals("SAFE", table.snapshot().statusLine());
    }

    @Test
    void textOnlyLineIgnoresIcons() throws Exception {
        UiHub hub = hub(0);
        UiIcon star = UiIcon.stock("star");
        hub.setStatusItem("a", "AA long text", star);
        hub.setStatusItem("b", "BB long text", star);
        hub.setIndicators(Arrays.asList("SAFE"));
        hub.publishNow();
        UiSnapshot s = table.snapshot();
        assertEquals("SAFE AA lon... BB lon...", s.statusLine(), "composed as if no item had an icon");
        // with icons: room 19, 9 each; 9 >= 2 + 3 keeps both icons, text cut to 7
        assertEquals("AA l...", s.statusParts().get(1).text());
        assertEquals(star, s.statusParts().get(1).icon());
        assertEquals("BB l...", s.statusParts().get(2).text());
    }

    // ---- input entries (api 5) ----

    private static final String[] LANGS = {"English", "Deutsch"};

    /** Collects the actions a page's listener receives (on its executor). */
    private static final class Actions implements Listener<UiAction> {
        final List<UiAction> got = java.util.Collections.synchronizedList(new ArrayList<UiAction>());

        public void on(UiAction a) {
            got.add(a);
        }

        List<UiAction> await(int n) throws InterruptedException {
            for (int i = 0; i < 500 && got.size() < n; i++) {
                Thread.sleep(10);
            }
            return new ArrayList<UiAction>(got);
        }

        /** Waits for {@code exec} to run everything queued, then the actions so far. */
        List<UiAction> drained(SerialExecutor exec) throws InterruptedException {
            final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            exec.submit("drain", new Runnable() {
                public void run() {
                    done.countDown();
                }
            });
            assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS));
            return new ArrayList<UiAction>(got);
        }
    }

    /** Blocks {@code exec} until the returned latch is counted down. */
    private static java.util.concurrent.CountDownLatch pause(SerialExecutor exec) {
        final java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
        exec.submit("pause", new Runnable() {
            public void run() {
                try {
                    gate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        return gate;
    }

    private static UiInput value(String page, String entry, int v) {
        return new UiInput(UiInput.VALUE_REQUESTED, page, entry, false, v, null);
    }

    @Test
    void choiceKeyJoinsPageAndItem() {
        assertEquals("p/main#lang", UiHub.choiceKey("p/main", "lang"));
    }

    @Test
    void choicePublishesItsSubPage() throws Exception {
        UiHub hub = hub(0);
        hub.addPage("p", PageSpec.of("main", "Main").choice("lang", "Language", LANGS, 0), null,
                executor("plugin-p"));
        hub.publishNow();
        UiSnapshot s = table.snapshot();
        UiEntry choice = s.pages().get("p/main").entries().get(0);
        assertEquals(UiEntry.CHOICE, choice.kind());
        assertEquals("p/main#lang", choice.targetPageKey());
        assertEquals(Arrays.asList(LANGS), Arrays.asList(choice.options()));
        assertEquals(0, choice.number());
        UiPage sub = s.pages().get("p/main#lang");
        assertNotNull(sub, s.pages().keySet().toString());
        assertEquals("p/main#lang", sub.key());
        assertEquals("Language", sub.title());
        assertEquals(2, sub.entries().size());
        UiEntry r0 = sub.entries().get(0);
        UiEntry r1 = sub.entries().get(1);
        assertEquals(UiEntry.RADIO, r0.kind());
        assertEquals("0", r0.key());
        assertEquals("English", r0.label());
        assertTrue(r0.on());
        assertEquals(UiEntry.RADIO, r1.kind());
        assertEquals("1", r1.key());
        assertEquals("Deutsch", r1.label());
        assertFalse(r1.on());

        hub.updatePage("p", PageSpec.of("main", "Main").choice("lang", "Language", LANGS, 1));
        hub.publishNow();
        sub = table.snapshot().pages().get("p/main#lang");
        assertFalse(sub.entries().get(0).on());
        assertTrue(sub.entries().get(1).on(), "the sub-page follows the update");
        hub.updatePage("p", PageSpec.of("main", "Main"));
        hub.publishNow();
        assertNull(table.snapshot().pages().get("p/main#lang"), "gone with its entry");
    }

    @Test
    void choicePickIsAnIndexAction() throws Exception {
        UiHub hub = hub(0);
        Actions a = new Actions();
        SerialExecutor exec = executor("plugin-p");
        hub.addPage("p", PageSpec.of("main", "Main").choice("lang", "Language", LANGS, 0), a, exec);
        hub.dispatch(new UiInput(UiInput.ENTRY_SELECTED, "p/main#lang", "1", false));
        hub.dispatch(new UiInput(UiInput.ENTRY_SELECTED, "p/main#lang", "2", false));
        hub.dispatch(new UiInput(UiInput.ENTRY_SELECTED, "p/main#lang", "x", false));
        hub.dispatch(new UiInput(UiInput.ENTRY_SELECTED, "p/main#lang", "-1", false));
        hub.dispatch(new UiInput(UiInput.ENTRY_SELECTED, "p/main#nope", "0", false));
        hub.dispatch(new UiInput(UiInput.TOGGLE_REQUESTED, "p/main#lang", "0", true));
        List<UiAction> got = a.drained(exec);
        assertEquals(1, got.size(), got.toString());
        assertEquals("main", got.get(0).pageId());
        assertEquals("lang", got.get(0).itemId());
        assertEquals(Integer.valueOf(1), got.get(0).value());
        assertNull(got.get(0).requestedOn());
        assertNull(got.get(0).text());
    }

    @Test
    void valueRequestedAsTheAdapterSendsIt() throws Exception {
        UiHub hub = hub(0);
        Actions a = new Actions();
        hub.addPage("p", PageSpec.of("main", "Main").dropdown("lvl", "Level", new String[] {"Off", "Low", "Mid"}, 0),
                a, executor("plugin-p"));
        UiDispatcher d = new UiDispatcher(table, hub);
        d.start();
        List<UiAction> got;
        try {
            table.input(new UiInput(UiInput.VALUE_REQUESTED, "p/main", "lvl", false, 2, null));
            got = a.await(1);
        } finally {
            d.stop();
        }
        assertEquals(1, got.size());
        assertEquals("lvl", got.get(0).itemId());
        assertEquals(Integer.valueOf(2), got.get(0).value());
    }

    @Test
    void dropdownIndexOutOfRangeIsDropped() throws Exception {
        UiHub hub = hub(0);
        Actions a = new Actions();
        SerialExecutor exec = executor("plugin-p");
        hub.addPage("p", PageSpec.of("main", "Main").dropdown("lvl", "Level", new String[] {"Off", "Low", "Mid"}, 0),
                a, exec);
        hub.dispatch(value("p/main", "lvl", 3));
        hub.dispatch(value("p/main", "lvl", -1));
        hub.dispatch(value("p/main", "lvl", 0));
        List<UiAction> got = a.drained(exec);
        assertEquals(1, got.size(), got.toString());
        assertEquals(Integer.valueOf(0), got.get(0).value());
    }

    @Test
    void sliderValuesAreClampedSnappedAndCoalesced() throws Exception {
        UiHub hub = hub(0);
        Actions a = new Actions();
        SerialExecutor exec = executor("plugin-p");
        hub.addPage("p", PageSpec.of("main", "Main").slider("bal", "Balance", -6, 6, 1, 0, true)
                .slider("vol", "Volume", 0, 10, 2, 4, false), a, exec);

        java.util.concurrent.CountDownLatch gate = pause(exec);
        hub.dispatch(value("p/main", "bal", 3));
        hub.dispatch(value("p/main", "bal", 9));
        hub.dispatch(value("p/main", "bal", 4));
        gate.countDown();
        List<UiAction> got = a.drained(exec);
        assertEquals(1, got.size(), "coalesced into one waiting action: " + got);
        assertEquals("bal", got.get(0).itemId());
        assertEquals(Integer.valueOf(4), got.get(0).value(), "the newest value");

        a.got.clear();
        hub.dispatch(value("p/main", "vol", 9)); // between 8 and 10: the tie goes toward the current 4
        assertEquals(Integer.valueOf(8), a.drained(exec).get(0).value());
        a.got.clear();
        hub.dispatch(value("p/main", "vol", 12));
        assertEquals(Integer.valueOf(10), a.drained(exec).get(0).value(), "clamped to max");
        a.got.clear();
        hub.dispatch(value("p/main", "vol", -3));
        assertEquals(Integer.valueOf(0), a.drained(exec).get(0).value(), "clamped to min");
        a.got.clear();
        hub.updatePage("p", PageSpec.of("main", "Main").slider("bal", "Balance", -6, 6, 1, 0, true)
                .slider("vol", "Volume", 0, 10, 2, 10, false));
        hub.dispatch(value("p/main", "vol", 9)); // the tie now goes toward the current 10
        assertEquals(Integer.valueOf(10), a.drained(exec).get(0).value());
        a.got.clear();

        gate = pause(exec);
        hub.dispatch(value("p/main", "bal", 1));
        hub.dispatch(value("p/main", "vol", 2));
        gate.countDown();
        got = a.drained(exec);
        assertEquals(2, got.size(), "one waiting action per slider: " + got);
        hub.dispatch(value("p/main", "bal", -2));
        got = a.drained(exec);
        assertEquals(3, got.size(), "a value after the waiting one ran is a new action: " + got);
        assertEquals(Integer.valueOf(-2), got.get(2).value());
    }

    @Test
    void inputOnDisabledEntryIsDropped() throws Exception {
        UiHub hub = hub(0);
        Actions a = new Actions();
        SerialExecutor exec = executor("plugin-p");
        hub.addPage("p", PageSpec.of("main", "Main").toggle("wifi", "Wi-Fi", false).enabled("wifi", false)
                .item("go", "Go", null).enabled("go", false)
                .choice("lang", "Language", LANGS, 0).enabled("lang", false), a, exec);
        hub.dispatch(new UiInput(UiInput.TOGGLE_REQUESTED, "p/main", "wifi", true));
        assertEquals(0, a.drained(exec).size());
        assertEquals(1, logged("ui input for p/main dropped: disabled"));
        hub.dispatch(new UiInput(UiInput.ENTRY_SELECTED, "p/main", "go", false));
        hub.dispatch(new UiInput(UiInput.ENTRY_SELECTED, "p/main#lang", "1", false));
        assertEquals(0, a.drained(exec).size(), "an item, and a pick on a disabled choice's sub-page");
    }

    private int logged(String msg) {
        int n = 0;
        for (String line : log.snapshot()) {
            if (line.indexOf(msg) >= 0) {
                n++;
            }
        }
        return n;
    }

    @Test
    void progressAndInfoInputsDoNothing() throws Exception {
        UiHub hub = hub(0);
        Actions a = new Actions();
        SerialExecutor exec = executor("plugin-p");
        hub.addPage("p", PageSpec.of("main", "Main").progress("dl", "Download", 60).info("note")
                .choice("lang", "Language", LANGS, 0), a, exec);
        hub.dispatch(new UiInput(UiInput.ENTRY_SELECTED, "p/main", "dl", false));
        hub.dispatch(value("p/main", "dl", 10));
        hub.dispatch(new UiInput(UiInput.ENTRY_SELECTED, "p/main", null, false));
        hub.dispatch(new UiInput(UiInput.ENTRY_SELECTED, "p/main", "lang", false)); // the adapter navigates
        hub.dispatch(value("p/main", "lang", 1));
        assertEquals(0, a.drained(exec).size());
        assertEquals(0, logged("dropped"), log.snapshot().toString());
    }

    @Test
    void shownPageKeyIsTheLastShownParent() {
        UiHub hub = hub(0);
        assertNull(hub.shownPageKey());
        hub.dispatch(new UiInput(UiInput.PAGE_SHOWN, "a/x", null, false));
        assertEquals("a/x", hub.shownPageKey());
        hub.dispatch(new UiInput(UiInput.PAGE_SHOWN, "b/y#x", null, false));
        assertEquals("b/y", hub.shownPageKey());
        hub.dispatch(new UiInput(UiInput.PAGE_CLOSED, "a/x", null, false));
        assertEquals("b/y", hub.shownPageKey(), "a stale close leaves it");
        hub.dispatch(new UiInput(UiInput.PAGE_CLOSED, "b/y#x", null, false));
        assertNull(hub.shownPageKey());
    }

    /** F16: the adapter's stack sends CLOSED/SHOWN pairs around a choice; the hub keeps the parent open. */
    @Test
    void choicePushAndPopShowThePluginNoClose() throws Exception {
        UiHub hub = hub(0);
        Actions a = new Actions();
        SerialExecutor exec = executor("plugin-p");
        hub.addPage("p", PageSpec.of("main", "Main").choice("lang", "Language", LANGS, 0), a, exec);
        hub.dispatch(new UiInput(UiInput.PAGE_SHOWN, "p/main", null, false));
        hub.dispatch(new UiInput(UiInput.PAGE_CLOSED, "p/main", null, false)); // push
        hub.dispatch(new UiInput(UiInput.PAGE_SHOWN, "p/main#lang", null, false));
        assertEquals("p/main", hub.shownPageKey());
        hub.dispatch(new UiInput(UiInput.ENTRY_SELECTED, "p/main#lang", "1", false));
        hub.dispatch(new UiInput(UiInput.PAGE_CLOSED, "p/main#lang", null, false)); // pop
        hub.dispatch(new UiInput(UiInput.PAGE_SHOWN, "p/main", null, false));
        assertEquals("p/main", hub.shownPageKey());
        assertEquals(1, a.drained(exec).size());

        hub.dispatch(new UiInput(UiInput.PAGE_CLOSED, "p/main", null, false)); // push again, then stop
        hub.dispatch(new UiInput(UiInput.PAGE_SHOWN, "p/main#lang", null, false));
        hub.removeOwner("p", "P stopped");
        hub.publishNow();
        UiPage notice = table.snapshot().pages().get("p/main");
        assertNotNull(notice, "the parent counts as open while its choice is shown");
        assertEquals("P stopped", notice.entries().get(0).label());
        assertEquals("Main", notice.title());
    }

    private UiImpl ui(String id, UiHub hub, String... features) {
        return new UiImpl(id, hub, new LiveFeatures(new HashSet<String>(Arrays.asList(features))),
                new HashSet<String>(Arrays.asList(Permissions.UI_SLOT_CAR_MENU, Permissions.UI_SLOT_STATUS_BAR)),
                executor("plugin-" + id), new Registrations(), java.util.Collections.<String>emptyList(), null);
    }

    /** Task 1 review: with UI_INPUTS every new kind reaches the hub as itself (no LINK fall-through). */
    @Test
    void inputKindsReachTheHubWithUiInputs() throws Exception {
        UiHub hub = hub(0);
        UiImpl ui = ui("p", hub, mibsdk.api.Features.UI_PAGES, mibsdk.api.Features.UI_INPUTS,
                mibsdk.api.Features.UI_ICONS);
        ui.page(PageSpec.of("main", "Main")
                .choice("lang", "Language", LANGS, 1)
                .dropdown("lvl", "Level", new String[] {"Off", "a very long option text over twenty"}, 0)
                .slider("bal", "Balance", -6, 6, 2, 2, true)
                .progress("dl", "Download", 60)
                .item("star", "Star", "v", ui.stockIcon(mibsdk.api.StockIcons.STAR))
                .toggle("wifi", "Wi-Fi", true).enabled("wifi", false), null);
        hub.publishNow();
        List<UiEntry> e = table.snapshot().pages().get("p/main").entries();
        assertEquals(UiEntry.CHOICE, e.get(0).kind());
        assertEquals(1, e.get(0).number());
        assertEquals(UiEntry.DROPDOWN, e.get(1).kind());
        assertEquals(PageSpec.MAX_VALUE, e.get(1).options()[1].length(), "options are cut to MAX_VALUE");
        assertEquals(UiEntry.SLIDER, e.get(2).kind());
        assertEquals(2, e.get(2).number());
        assertEquals(-6, e.get(2).min());
        assertEquals(6, e.get(2).max());
        assertEquals(2, e.get(2).step());
        assertTrue(e.get(2).centred());
        assertEquals(UiEntry.PROGRESS, e.get(3).kind());
        assertEquals(60, e.get(3).number());
        assertEquals(UiEntry.ITEM, e.get(4).kind());
        assertEquals(UiIcon.stock(mibsdk.api.StockIcons.STAR), e.get(4).icon());
        assertTrue(e.get(4).enabled());
        assertFalse(e.get(5).enabled());
        assertNotNull(table.snapshot().pages().get("p/main#lang"));
    }

    /** Task 1 review: disabled rows reach the hub without UI_INPUTS too, so the hub drops their input either way. */
    @Test
    void disabledRowsAreDroppedWithAndWithoutUiInputs() throws Exception {
        String[][] sets = {{mibsdk.api.Features.UI_PAGES},
                {mibsdk.api.Features.UI_PAGES, mibsdk.api.Features.UI_INPUTS}};
        for (int i = 0; i < sets.length; i++) {
            UiHub hub = hub(0);
            Actions a = new Actions();
            UiImpl ui = ui("p" + i, hub, sets[i]);
            ui.page(PageSpec.of("main", "Main").item("go", "Go", null).enabled("go", false)
                    .toggle("wifi", "Wi-Fi", false).enabled("wifi", false).item("ok", "Ok", null), a);
            hub.publishNow();
            assertFalse(table.snapshot().pages().get("p" + i + "/main").entries().get(0).enabled());
            hub.dispatch(new UiInput(UiInput.ENTRY_SELECTED, "p" + i + "/main", "go", false));
            hub.dispatch(new UiInput(UiInput.TOGGLE_REQUESTED, "p" + i + "/main", "wifi", true));
            hub.dispatch(new UiInput(UiInput.ENTRY_SELECTED, "p" + i + "/main", "ok", false));
            List<UiAction> got = a.await(1);
            Thread.sleep(50);
            assertEquals(1, a.got.size(), Arrays.toString(sets[i]) + ": " + a.got);
            assertEquals("ok", got.get(0).itemId());
        }
    }
}
