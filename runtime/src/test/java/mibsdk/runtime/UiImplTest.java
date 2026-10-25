package mibsdk.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import mibsdk.api.Features;
import mibsdk.api.Icon;
import mibsdk.api.Page;
import mibsdk.api.PageSpec;
import mibsdk.api.PermissionDeniedException;
import mibsdk.api.Permissions;
import mibsdk.api.StatusItem;
import mibsdk.api.StockIcons;
import mibsdk.api.Subscription;
import mibsdk.platform.UiEntry;
import mibsdk.platform.UiIcon;
import mibsdk.platform.UiPage;
import mibsdk.platform.UiSnapshot;
import mibsdk.platform.UiTable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UiImplTest {

    private static final SerialExecutor.Listener QUIET = new SerialExecutor.Listener() {
        public void onFailure(String label, Throwable t) {
        }

        public void onSlow(String label, long ms) {
        }

        public void onHung(String label) {
        }
    };

    private static final Set<String> ALL_PERMS =
            new HashSet<String>(Arrays.asList(Permissions.UI_SLOT_CAR_MENU, Permissions.UI_SLOT_STATUS_BAR));

    @TempDir
    File tmp;

    private final FakeClock clock = new FakeClock();
    private final UiTable table = new UiTable(64);
    private final List<SerialExecutor> executors = new ArrayList<SerialExecutor>();
    private final RingLog log = new RingLog(100);
    private final UiHub hub;
    private UiTestRuntime rtx;

    UiImplTest() {
        SerialExecutor e = executor("ui-publish");
        hub = new UiHub(table, log, clock, new PluginScheduler(e, clock), 0);
    }

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

    private UiImpl ui(String id, Set<String> features, Set<String> perms, Registrations regs) {
        return ui(id, features, perms, regs, Collections.<String>emptyList(), new File(tmp, "icons/" + id));
    }

    private UiImpl ui(String id, Set<String> features, Set<String> perms, Registrations regs,
            List<String> declaredIcons, File iconDir) {
        return new UiImpl(id, hub, new LiveFeatures(features), perms, executor("plugin-" + id), regs,
                declaredIcons, iconDir);
    }

    private UiImpl ui(String id) {
        return ui(id, UiTestRuntime.UI_FEATURES, ALL_PERMS, new Registrations());
    }

    private UiSnapshot published() {
        hub.publishNow();
        return table.snapshot();
    }

    // ---- features and permissions ----

    @Test
    void carRowNeedsPermissionAndFeature() {
        Set<String> pagesOnly = Collections.singleton(Features.UI_PAGES);
        UiImpl noFeature = ui("a", pagesOnly, ALL_PERMS, new Registrations());
        final Page pa = noFeature.page(PageSpec.of("main", "A"), null);
        Subscription row = noFeature.carMenuRow("A", pa); // no slot on this unit: an inert row, not an error
        row.cancel();
        StatusItem item = noFeature.statusItem("x");
        item.set("y");
        item.cancel();

        UiImpl noPerm = ui("b", UiTestRuntime.UI_FEATURES, Collections.<String>emptySet(), new Registrations());
        final Page pb = noPerm.page(PageSpec.of("main", "B"), null);
        PermissionDeniedException d = assertThrows(PermissionDeniedException.class, () -> noPerm.carMenuRow("B", pb));
        assertEquals(Permissions.UI_SLOT_CAR_MENU, d.permission());
        d = assertThrows(PermissionDeniedException.class, () -> noPerm.statusItem("x"));
        assertEquals(Permissions.UI_SLOT_STATUS_BAR, d.permission());

        UiSnapshot s = published();
        assertEquals(1, s.carRows().size(), "nothing was registered by the inert or refused calls");
        assertTrue(s.statusLine() == null || s.statusLine().length() == 0, s.statusLine());
    }

    @Test
    void uiOffGivesInertHandles() {
        UiImpl off = ui("c", Collections.<String>emptySet(), ALL_PERMS, new Registrations());
        assertThrows(IllegalArgumentException.class, () -> off.page(null, null), "a missing spec is still an error");
        final Page p = off.page(PageSpec.of("main", "C"), null);
        assertEquals("main", p.id());
        p.update(PageSpec.of("main", "C2"));
        p.open();
        assertThrows(IllegalArgumentException.class, () -> p.update(PageSpec.of("other", "C")), "id change");
        Subscription row = off.carMenuRow("C", p);
        StatusItem item = off.statusItem("x");
        item.set("y");
        assertThrows(IllegalArgumentException.class, () -> off.carMenuRow("C", null), "a missing page");

        for (int i = 1; i < 8; i++) {
            off.page(PageSpec.of("p" + i, "P"), null);
        }
        assertThrows(IllegalStateException.class, () -> off.page(PageSpec.of("p8", "P"), null),
                "count limits hold with the UI off too");
        off.carMenuRow("C2", p);
        assertThrows(IllegalStateException.class, () -> off.carMenuRow("C3", p), "3rd Car row");
        assertThrows(IllegalStateException.class, () -> off.statusItem("z"), "2nd status item");
        row.cancel();
        item.cancel();
        p.cancel();
        p.cancel();
        assertThrows(IllegalArgumentException.class, () -> off.carMenuRow("C3", p), "a cancelled page");
        final Page again = off.page(PageSpec.of("main", "again"), null); // a cancelled inert page frees its slot
        off.carMenuRow("C3", again);
        off.statusItem("z");

        UiSnapshot s = published();
        assertEquals(1, s.carRows().size(), "only the Plugins row");
        assertNull(s.pages().get("c/main"));
        assertTrue(s.statusLine() == null || s.statusLine().length() == 0, s.statusLine());
    }

    @Test
    void featureLearnedLateIsSeen() throws Exception {
        rtx = new UiTestRuntime(tmp, Collections.singleton(Features.UI_PAGES));
        rtx.plugin("late", "Late", new String[] {Permissions.UI_SLOT_CAR_MENU}, ""
                + "final Page p = ctx.ui().page(PageSpec.of(\"main\", \"Late\"), null);\n"
                + "ctx.events().on(IgnitionChanged.class, e -> {\n"
                + "  ctx.ui().carMenuRow(\"Late\", p);\n"
                + "  ctx.log().info(\"row added, slot \" + ctx.has(Features.SLOT_CAR_MENU));\n"
                + "});", null);
        rtx.start();
        rtx.fake.setIgnition(true);
        rtx.awaitCount("late", "row added, slot false", 1); // inert: the slot is not there yet

        Set<String> more = new HashSet<String>(rtx.fake.features());
        more.add(Features.SLOT_CAR_MENU);
        rtx.fake.setFeatures(more);
        for (int i = 0; i < 500 && !featuresLogged("slot.carmenu"); i++) {
            Thread.sleep(10); // the runtime logs STATUS features=[...] once it has re-read them
        }
        rtx.fake.setIgnition(false);
        rtx.awaitCount("late", "row added, slot true", 1);
        UiSnapshot s = rtx.await(x -> x.carRows().size() == 2, "shows the late row");
        assertEquals("late/main", s.carRows().get(1).pageKey());
    }

    private boolean featuresLogged(String feature) {
        for (String line : rtx.rt.log().snapshot()) {
            if (line.indexOf(" features=[") >= 0 && line.indexOf(feature) >= 0) {
                return true;
            }
        }
        return false;
    }

    // ---- limits ----

    @Test
    void limitsArePerPlugin() {
        UiImpl a = ui("a");
        Page first = null;
        for (int i = 0; i < 8; i++) {
            Page p = a.page(PageSpec.of("p" + i, "P"), null);
            if (first == null) {
                first = p;
            }
        }
        assertThrows(IllegalStateException.class, () -> a.page(PageSpec.of("p8", "P"), null), "9th page");
        UiImpl b = ui("b");
        Page pb = null;
        for (int i = 0; i < 8; i++) {
            pb = b.page(PageSpec.of("p" + i, "P"), null); // another plugin has its own 8
        }
        first.cancel();
        final Page p8 = a.page(PageSpec.of("p8", "P"), null); // a cancelled page frees its slot

        Subscription r1 = a.carMenuRow("one", p8);
        a.carMenuRow("two", p8);
        assertThrows(IllegalStateException.class, () -> a.carMenuRow("three", p8), "3rd Car row");
        r1.cancel();
        a.carMenuRow("three", p8);

        StatusItem s = a.statusItem("x");
        assertThrows(IllegalStateException.class, () -> a.statusItem("y"), "2nd status item");
        s.cancel();
        a.statusItem("y");

        b.carMenuRow("one", pb);
        b.carMenuRow("two", pb);
        b.statusItem("z");
        assertEquals(1 + 4, published().carRows().size());
        assertEquals("y z", published().statusLine());
    }

    @Test
    void duplicatePageIdIsRejectedUntilCancelled() {
        UiImpl a = ui("a");
        Page p = a.page(PageSpec.of("main", "A"), null);
        assertThrows(IllegalArgumentException.class, () -> a.page(PageSpec.of("main", "again"), null));
        p.cancel();
        a.page(PageSpec.of("main", "again"), null);
        assertEquals("again", published().pages().get("a/main").title());
    }

    // ---- hostile text ----

    @Test
    void hostileTextNeverThrows() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 10240; i++) {
            big.append((char) ('a' + i % 26));
        }
        String huge = big.toString();
        UiImpl a = ui("a");
        Page p = a.page(PageSpec.of("main", huge).item("n", null, null).item("h", huge, huge).info(huge)
                .toggle("t", "line\nbreak‏😀", true), null);
        a.carMenuRow(null, p);
        a.carMenuRow(huge, p);
        StatusItem s = a.statusItem(null);
        s.set(huge);

        UiSnapshot snap = published();
        UiPage page = snap.pages().get("a/main");
        assertEquals(PageSpec.MAX_TITLE, page.title().length());
        assertTrue(page.title().endsWith("..."));
        assertEquals("", page.entries().get(0).label());
        assertEquals(PageSpec.MAX_LABEL, page.entries().get(1).label().length());
        assertEquals(PageSpec.MAX_VALUE, page.entries().get(1).value().length());
        assertEquals(PageSpec.MAX_INFO, page.entries().get(2).label().length());
        assertEquals("line break?", page.entries().get(3).label());
        assertEquals("", snap.carRows().get(1).label());
        assertEquals(PageSpec.MAX_LABEL, snap.carRows().get(2).label().length());
        assertEquals(StatusItem.MAX_TEXT, snap.statusLine().length());
        assertTrue(snap.statusLine().endsWith("..."));

        s.set(null);
        assertEquals("", published().statusLine());
        s.set("");
        assertEquals("", published().statusLine());
    }

    // ---- link targets ----

    /** A Page the plugin made itself: the SDK must neither accept it nor call it. */
    private static final class HandMade implements Page {
        final List<String> calls = Collections.synchronizedList(new ArrayList<String>());

        public String id() {
            calls.add("id on " + Thread.currentThread().getName());
            return "main";
        }

        public void update(PageSpec spec) {
            calls.add("update");
        }

        public void open() {
            calls.add("open");
        }

        public void cancel() {
            calls.add("cancel");
        }
    }

    @Test
    void linkTargetsMustBeLivePagesOfTheSamePlugin() {
        UiImpl a = ui("a");
        UiImpl b = ui("b");
        Page target = a.page(PageSpec.of("target", "T"), null);
        Page foreign = b.page(PageSpec.of("main", "B"), null);
        Page gone = a.page(PageSpec.of("gone", "G"), null);
        gone.cancel();
        HandMade fake = new HandMade();

        Page main = a.page(PageSpec.of("main", "A").link("t", "Target", target), null);
        UiEntry link = published().pages().get("a/main").entries().get(0);
        assertEquals(UiEntry.LINK, link.kind());
        assertEquals("a/target", link.targetPageKey());

        assertThrows(IllegalArgumentException.class,
                () -> a.page(PageSpec.of("x1", "X").link("f", "Foreign", foreign), null), "another plugin's page");
        assertThrows(IllegalArgumentException.class,
                () -> a.page(PageSpec.of("x2", "X").link("g", "Gone", gone), null), "a cancelled page");
        assertThrows(IllegalArgumentException.class,
                () -> a.page(PageSpec.of("x3", "X").link("h", "Hand", fake), null), "a hand-made page");
        assertThrows(IllegalArgumentException.class,
                () -> main.update(PageSpec.of("main", "A").link("f", "Foreign", foreign)), "update: foreign");
        assertThrows(IllegalArgumentException.class,
                () -> main.update(PageSpec.of("main", "A").link("h", "Hand", fake)), "update: hand-made");
        assertThrows(IllegalArgumentException.class, () -> a.carMenuRow("Hand", fake), "row: hand-made");
        assertThrows(IllegalArgumentException.class, () -> a.carMenuRow("Foreign", foreign), "row: foreign");
        assertThrows(IllegalArgumentException.class, () -> a.carMenuRow("Gone", gone), "row: cancelled");

        main.update(PageSpec.of("main", "A").link("self", "Me", main));
        assertEquals("a/main", published().pages().get("a/main").entries().get(0).targetPageKey());
        assertEquals(Collections.<String>emptyList(), fake.calls, "the SDK never calls a plugin-made Page");
        UiSnapshot s = published();
        assertFalse(s.pages().containsKey("a/x1") || s.pages().containsKey("a/x2") || s.pages().containsKey("a/x3"));
        assertNotNull(s.pages().get("a/main"));
    }

    @Test
    void cancelAllRemovesEverything() {
        Registrations regs = new Registrations();
        UiImpl a = ui("a", UiTestRuntime.UI_FEATURES, ALL_PERMS, regs);
        Page p = a.page(PageSpec.of("main", "A"), null);
        a.carMenuRow("A", p);
        a.statusItem("on");
        assertEquals(2, published().carRows().size());

        regs.cancelAll(); // closes the UiImpl first: from here its handles leave the hub alone
        hub.removeOwner("a", null); // as the runtime does right after cancelAll
        UiSnapshot s = published();
        assertEquals(1, s.carRows().size());
        assertTrue(s.pages().isEmpty());
        assertTrue(s.statusLine() == null || s.statusLine().length() == 0, s.statusLine());
        p.update(PageSpec.of("main", "late")); // a cancelled handle is inert, never throws
        p.open();
        assertTrue(published().pages().isEmpty());
        a.page(PageSpec.of("late", "L"), null); // registered after close: cancelled at once
        assertTrue(published().pages().isEmpty());
    }

    /**
     * Controller requirement 3 (stopgap for the id-keyed reload race): once the old run's registrations are cancelled
     * its UiImpl is closed, so a callback of the old run still hung after a reload cannot add to, change or cancel the
     * new run's UI under the same plugin id.
     */
    @Test
    void closedOldRunCannotTouchTheNewRunsUi() {
        Registrations oldRegs = new Registrations();
        UiImpl old = ui("a", UiTestRuntime.UI_FEATURES, ALL_PERMS, oldRegs);
        Page oldPage = old.page(PageSpec.of("main", "Old"), null);
        StatusItem oldStatus = old.statusItem("old");

        // the runtime disables the old run (cancelAll, then removeOwner) and enables the new one
        oldRegs.cancelAll();
        hub.removeOwner("a", null);
        hub.registerOwner("a");
        UiImpl fresh = ui("a", UiTestRuntime.UI_FEATURES, ALL_PERMS, new Registrations());
        Page newPage = fresh.page(PageSpec.of("main", "New"), null);
        fresh.carMenuRow("New", newPage);
        fresh.statusItem("new");

        // the old run's hung callback wakes up
        Page stray = old.page(PageSpec.of("main", "Stray"), null); // same id: would remove the new page on cancel
        assertNotNull(stray);
        stray.cancel();
        Page other = old.page(PageSpec.of("other", "Stray"), null);
        other.open();
        old.carMenuRow("Stray", other).cancel();
        old.statusItem("stray").set("stray");
        oldPage.update(PageSpec.of("main", "Stray"));
        oldPage.open();
        oldPage.cancel();
        oldStatus.set("stray");
        oldStatus.cancel();

        UiSnapshot s = published();
        assertEquals(1, s.pages().size(), s.pages().keySet().toString());
        assertEquals("New", s.pages().get("a/main").title());
        assertEquals(2, s.carRows().size());
        assertEquals("New", s.carRows().get(1).label());
        assertEquals("new", s.statusLine());
        assertNull(s.openPageKey(), "a stray open() requests nothing");
    }

    // ---- icons ----

    private static final List<String> DECLARED = Arrays.asList("hello", "hello-20", "big", "tall");

    private static Set<String> withIcons(Set<String> features) {
        Set<String> s = new HashSet<String>(features);
        s.add(Features.UI_ICONS);
        return s;
    }

    private static void writeFile(File f, String s) throws IOException {
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(s.getBytes("ISO-8859-1"));
        } finally {
            out.close();
        }
    }

    /** Writes the index and, for every line, its {@code <sha16>.png} (the content is not read). */
    private File installIcons(String id, String index) throws IOException {
        File dir = new File(tmp, "icons/" + id);
        dir.mkdirs();
        writeFile(new File(dir, "index.properties"), index);
        for (int at = 0; at < index.length();) {
            int nl = index.indexOf('\n', at);
            String line = index.substring(at, nl < 0 ? index.length() : nl);
            int eq = line.indexOf('=');
            if (eq > 0 && line.length() >= eq + 17) {
                writeFile(new File(dir, line.substring(eq + 1, eq + 17) + ".png"), "png");
            }
            at = nl < 0 ? index.length() : nl + 1;
        }
        return dir;
    }

    private static UiIcon held(Icon i) {
        return ((UiImpl.IconHandle) i).icon;
    }

    private int warnings(String what) {
        int n = 0;
        for (String line : log.snapshot()) {
            if (line.contains(" WARN ") && line.contains(what)) {
                n++;
            }
        }
        return n;
    }

    @Test
    void declaredButNotInstalledIconIsInert() throws Exception {
        // a "sdk push" plugin: the jar is there, its icon dir is not
        UiImpl a = ui("a", withIcons(UiTestRuntime.UI_FEATURES), ALL_PERMS, new Registrations(), DECLARED,
                new File(tmp, "icons/a"));
        Icon hello = a.icon("hello");
        assertNotNull(hello);
        assertNull(held(hello), "inert: nothing for the HMI to load");
        assertEquals(1, warnings("hello"));
        a.icon("hello");
        assertEquals(1, warnings("hello"), "one WARN per name, not per call");

        Page p = a.page(PageSpec.of("main", "A"), null);
        Subscription row = a.carMenuRow("A", p, hello);
        assertNull(((UiImpl.RowHandle) row).icon, "the row shows without the icon");
        StatusItem item = a.statusItem("x", hello);
        item.set("y", hello);
        assertNull(((UiImpl.StatusHandle) item).icon);
        UiSnapshot s = published();
        assertEquals(2, s.carRows().size(), "the row is live");
        assertEquals("y", s.statusLine());

        // installed, but one declared name is missing from the index
        File dir = installIcons("b", "hello=0123456789abcdef,72,72\n");
        UiImpl b = ui("b", withIcons(UiTestRuntime.UI_FEATURES), ALL_PERMS, new Registrations(), DECLARED, dir);
        assertEquals(new File(dir, "0123456789abcdef.png").getPath(), held(b.icon("hello")).path());
        assertEquals("b", held(b.icon("hello")).owner());
        assertNull(held(b.icon("hello-20")));
        assertEquals(1, warnings("hello-20"));
    }

    @Test
    void indexedIconWithoutItsPngIsInert() throws Exception {
        File dir = new File(tmp, "icons/a");
        dir.mkdirs();
        writeFile(new File(dir, "index.properties"), "hello=0123456789abcdef,20,20\n"); // no 0123456789abcdef.png
        UiImpl a = ui("a", withIcons(UiTestRuntime.UI_FEATURES), ALL_PERMS, new Registrations(), DECLARED, dir);
        Icon hello = a.icon("hello");
        assertNull(held(hello), "the HMI must never be asked for a path that does not exist");
        a.icon("hello");
        assertEquals(1, warnings("hello"));
        StatusItem item = a.statusItem("x", hello);
        assertNull(((UiImpl.StatusHandle) item).icon);
    }

    @Test
    void undeclaredOrForeignIconThrows() throws Exception {
        File dir = installIcons("a", "hello=0123456789abcdef,20,20\nother=fedcba9876543210,20,20\n");
        UiImpl a = ui("a", withIcons(UiTestRuntime.UI_FEATURES), ALL_PERMS, new Registrations(), DECLARED, dir);
        UiImpl b = ui("b", withIcons(UiTestRuntime.UI_FEATURES), ALL_PERMS, new Registrations(), DECLARED, dir);
        assertThrows(IllegalArgumentException.class, () -> a.icon("other"), "installed but not declared");
        assertThrows(IllegalArgumentException.class, () -> a.icon("nope"));
        assertThrows(IllegalArgumentException.class, () -> a.icon(null));
        assertThrows(IllegalArgumentException.class, () -> a.stockIcon("nope"));
        assertThrows(IllegalArgumentException.class, () -> a.stockIcon(null));
        assertEquals(UiIcon.stock(StockIcons.STAR), held(a.stockIcon(StockIcons.STAR)));
        for (String n : new String[] {StockIcons.DOT, StockIcons.CIRCLE, StockIcons.STAR, StockIcons.TRIANGLE,
                StockIcons.SQUARE, StockIcons.CHECK, StockIcons.CROSS, StockIcons.PLUG}) {
            assertTrue(StockIcons.isKnown(n), n);
        }
        assertFalse(StockIcons.isKnown("nope"));
        assertFalse(StockIcons.isKnown(null));

        final Page p = a.page(PageSpec.of("main", "A"), null);
        final Icon foreign = b.icon("hello");
        final Icon made = new Icon() {
        };
        for (final Icon bad : new Icon[] {foreign, made, b.stockIcon(StockIcons.DOT)}) {
            assertThrows(IllegalArgumentException.class, () -> a.carMenuRow("A", p, bad));
            assertThrows(IllegalArgumentException.class, () -> a.statusItem("x", bad));
        }
        StatusItem item = a.statusItem("x", a.icon("hello"));
        assertThrows(IllegalArgumentException.class, () -> item.set("y", foreign));
        assertThrows(IllegalArgumentException.class, () -> item.set("y", made));
        a.carMenuRow("A1", p, a.icon("hello"));
        a.carMenuRow("A2", p, null);
        assertThrows(IllegalStateException.class, () -> a.carMenuRow("A3", p), "refused rows held no count slot");
        assertEquals(0, warnings("WARN"), "nothing was inert");
    }

    @Test
    void bigIconOnAStatusItemThrows() throws Exception {
        String index = "big=0123456789abcdef,72,72\nhello-20=fedcba9876543210,20,20\ntall=00112233445566ff,20,21\n";
        List<Set<String>> runs = Arrays.asList(withIcons(UiTestRuntime.UI_FEATURES), UiTestRuntime.UI_FEATURES);
        for (int r = 0; r < runs.size(); r++) {
            String id = "p" + r;
            boolean drawn = runs.get(r).contains(Features.UI_ICONS);
            UiImpl u = ui(id, runs.get(r), ALL_PERMS, new Registrations(), DECLARED, installIcons(id, index));
            final Icon big = u.icon("big");
            final Icon tall = u.icon("tall");
            Icon small = u.icon("hello-20");
            assertThrows(IllegalArgumentException.class, () -> u.statusItem("x", big), "run " + r);
            assertThrows(IllegalArgumentException.class, () -> u.statusItem("x", tall), "run " + r);
            final StatusItem item = u.statusItem("x", small); // the refused calls held no count slot
            assertThrows(IllegalArgumentException.class, () -> item.set("y", big), "run " + r);
            item.set("y", u.stockIcon(StockIcons.CHECK));
            assertEquals(drawn ? UiIcon.stock(StockIcons.CHECK) : null, ((UiImpl.StatusHandle) item).icon);
            item.set("z");
            assertEquals(drawn ? UiIcon.stock(StockIcons.CHECK) : null, ((UiImpl.StatusHandle) item).icon,
                    "set(text) keeps the icon");
            item.set("z", null);
            assertNull(((UiImpl.StatusHandle) item).icon);

            Page p = u.page(PageSpec.of("main", "P"), null);
            Subscription row = u.carMenuRow("P", p, big); // a row takes up to Ui.ROW_ICON_MAX_PX
            assertEquals(drawn ? held(big) : null, ((UiImpl.RowHandle) row).icon, "run " + r);
            assertEquals(72, held(big).width());
        }
    }

    @Test
    void handlesPublishTheirIconsWithTheirText() throws Exception {
        File dir = installIcons("a", "hello-20=fedcba9876543210,20,20\n");
        UiImpl a = ui("a", withIcons(UiTestRuntime.UI_FEATURES), ALL_PERMS, new Registrations(), DECLARED, dir);
        Icon png = a.icon("hello-20");
        Page p = a.page(PageSpec.of("main", "A"), null);
        a.carMenuRow("Row", p, png);
        StatusItem item = a.statusItem("one", a.stockIcon(StockIcons.STAR));
        UiSnapshot s = published();
        assertEquals(held(png), s.carRows().get(1).icon());
        assertEquals("one", s.statusParts().get(0).text());
        assertEquals(UiIcon.stock(StockIcons.STAR), s.statusParts().get(0).icon());

        item.set("two", png);
        s = published();
        assertEquals("two", s.statusParts().get(0).text());
        assertEquals(held(png), s.statusParts().get(0).icon(), "text and icon arrive together");
        item.set("three");
        assertEquals(held(png), published().statusParts().get(0).icon(), "set(text) keeps the icon");
        item.set("three", null);
        s = published();
        assertEquals("three", s.statusParts().get(0).text());
        assertNull(s.statusParts().get(0).icon());

        UiImpl b = ui("b", UiTestRuntime.UI_FEATURES, ALL_PERMS, new Registrations(), DECLARED, dir); // no UI_ICONS
        Page bp = b.page(PageSpec.of("main", "B"), null);
        b.carMenuRow("Row", bp, b.stockIcon(StockIcons.STAR));
        b.statusItem("b", b.stockIcon(StockIcons.STAR));
        s = published();
        assertNull(s.carRows().get(2).icon(), "not drawn without UI_ICONS");
        assertNull(s.statusParts().get(1).icon());
    }
}
