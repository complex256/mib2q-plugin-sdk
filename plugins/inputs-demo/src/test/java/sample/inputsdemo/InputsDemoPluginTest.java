package sample.inputsdemo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import mibsdk.adapter.fake.FakePlatform;
import mibsdk.adapter.fake.FakeUi;
import mibsdk.api.Features;
import mibsdk.platform.HookTable;
import mibsdk.platform.SdkRoots;
import mibsdk.platform.UiEntry;
import mibsdk.platform.UiPage;
import mibsdk.platform.UiTable;
import mibsdk.runtime.Clock;
import mibsdk.runtime.PluginState;
import mibsdk.runtime.RingLog;
import mibsdk.runtime.RuntimeConfig;
import mibsdk.runtime.SdkRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InputsDemoPluginTest {

    @TempDir
    File tmp;

    private SdkRuntime rt;
    private final UiTable table = new UiTable(64);
    private final FakeUi ui = new FakeUi(table);
    /** Added to the real uptime: a test moves the plugin's Scheduler forward by raising it. */
    private final AtomicLong skew = new AtomicLong();

    @AfterEach
    void tearDown() {
        if (rt != null) {
            rt.stop();
        }
    }

    /** Every feature the demo uses (F27). */
    private void start() throws IOException {
        start(Features.UI_PAGES, Features.UI_INPUTS, Features.UI_ICONS, Features.SLOT_STATUS_BAR);
    }

    private void start(String... features) throws IOException {
        Set<String> f = new HashSet<String>(Arrays.asList(features));
        FakePlatform fake = new FakePlatform("HMI_TEST", f);
        SdkRoots roots = FakePlatform.roots(tmp);
        File base = roots.install();
        new File(base, "plugins").mkdirs();
        Files.copy(new File(System.getProperty("mibsdk.pluginJar")).toPath(),
                new File(base, "plugins/inputs-demo.jar").toPath(), StandardCopyOption.REPLACE_EXISTING);
        write(new File(base, "sdk.properties"), "plugin.start.delay.ms=0\nheap.low.bytes=0\nui.publish.min.ms=0\n"
                + "log.file=" + new File(tmp, "mibsdk.log").getPath() + "\n");
        write(new File(base, "grants.properties"), "inputs-demo=ui.slot.statusbar\n");
        installIcons(base);
        Clock clock = new Clock() {
            public long uptimeMs() {
                return System.nanoTime() / 1000000L + skew.get();
            }
        };
        rt = new SdkRuntime(RuntimeConfig.load(roots), fake, clock, new HookTable(64), table, new RingLog(500));
        rt.start();
        await(() -> rt.statuses().size() == 1 && rt.statuses().get(0).state() == PluginState.ENABLED, "ENABLED");
        await(() -> !ui.render(PAGE).isEmpty(), "page registered");
    }

    private static final String SHA = "0123456789abcdef";

    /** What mibpkg installs for the package: index.properties and the PNG under its sha16 name. */
    private static void installIcons(File base) throws IOException {
        File dir = new File(base, "icons/inputs-demo");
        write(new File(dir, "index.properties"), "plug-24=" + SHA + ",24,24\n");
        Files.copy(new File("src/main/icons/plug-24.png").toPath(), new File(dir, SHA + ".png").toPath(),
                StandardCopyOption.REPLACE_EXISTING);
    }

    private void await(java.util.function.BooleanSupplier c, String what) {
        for (int i = 0; i < 500; i++) {
            if (c.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }
        fail("never " + what + ": " + ui.render(PAGE) + "\n" + rt.log().snapshot());
    }

    private static void write(File f, String text) throws IOException {
        f.getParentFile().mkdirs();
        FileOutputStream o = new FileOutputStream(f);
        try {
            o.write(text.getBytes("UTF-8"));
        } finally {
            o.close();
        }
    }

    private static final String PAGE = "inputs-demo/inputs";
    private static final String LANG = PAGE + "#lang";
    private static final String CONFIRM_TEXT = "Are you sure? This long question checks how the confirm screen wraps"
            + " one line of text over all its three lines";

    /** Opens the page from the Plugins menu, as the user does: confirm and speller need it on screen. */
    private void openPage() {
        await(() -> link() != null, "Plugins-menu link to " + PAGE);
        ui.select("mibsdk/plugin.inputs-demo", link());
        await(() -> PAGE.equals(ui.openPageKey()), "page open");
    }

    private String link() {
        UiPage menu = table.snapshot().pages().get("mibsdk/plugin.inputs-demo");
        if (menu != null) {
            for (UiEntry e : menu.entries()) {
                if (e.kind() == UiEntry.LINK && PAGE.equals(e.targetPageKey())) {
                    return e.key();
                }
            }
        }
        return null;
    }

    /** The rendered line of the entry labelled {@code label}; null for none. */
    private String line(String label) {
        for (String l : ui.render(PAGE)) {
            String t = l.endsWith(" [disabled]") ? l.substring(0, l.length() - " [disabled]".length()) : l;
            if (t.equals(label) || t.endsWith("] " + label) || t.startsWith(label + ":")) {
                return l;
            }
        }
        return null;
    }

    private void awaitLine(String exact) {
        await(() -> ui.render(PAGE).contains(exact), "line '" + exact + "'");
    }

    private String last() {
        List<String> lines = ui.render(PAGE);
        return lines.get(lines.size() - 1);
    }

    @Test
    void pageRendersEveryInput() throws Exception {
        start();
        assertEquals(Arrays.asList(
                "[off] Wi-Fi",
                "[off] Red",
                "[off] Green",
                "[off] Blue",
                "[off] Locked [disabled]",
                "Language: English >",
                "Version: 1.0.0",
                "[png:" + SHA + "] Plug",
                "[png:" + SHA + "] Plug value: on",
                "Progress: 60%",
                "Progress +20",
                "Swap kind",
                "[off] Swap me",
                "Confirm",
                "Confirm 2 lines",
                "Toast",
                "Toast in 5 s",
                "Name",
                "Level: Off v",
                "Bass: 0 [-6..6]",
                "Volume: 0 [0..10]",
                "Last: <none>"), ui.render(PAGE));
        assertEquals(Arrays.asList("(*) English", "( ) Deutsch", "( ) Français"),
                ui.render(LANG));
    }

    @Test
    void withoutInputsRendersTextLines() throws Exception {
        start(Features.UI_PAGES, Features.UI_ICONS, Features.SLOT_STATUS_BAR);
        List<String> lines = ui.render(PAGE);
        assertEquals(22, lines.size(), lines.toString());
        for (String text : new String[] {"Level: Off", "Bass: 0", "Volume: 0", "Progress: 60%", "Language: English"}) {
            assertTrue(lines.contains(text), text + " in " + lines);
        }
        assertTrue(lines.contains("[off] Locked [disabled]"), "the toggle stays a disabled list row: " + lines);
    }

    @Test
    void toggleAndMultiChoice() throws Exception {
        start();
        ui.toggle(PAGE, "wifi");
        awaitLine("[on] Wi-Fi");
        ui.toggle(PAGE, "red");
        ui.toggle(PAGE, "blue");
        awaitLine("[on] Blue");
        assertEquals("[on] Red", line("Red"));
        assertEquals("[off] Green", line("Green"), "multi-choice: each colour on its own");
        ui.toggle(PAGE, "red");
        awaitLine("[off] Red");
        assertEquals("[on] Blue", line("Blue"));
        assertTrue(last().startsWith("Last: UiAction{page=inputs, item=red, requestedOn=false"), last());
        assertTrue(rt.log().snapshot().toString().contains("UiAction{page=inputs, item=wifi, requestedOn=true"),
                "every action is logged");
    }

    @Test
    void lockedSendsNothing() throws Exception {
        start();
        ui.toggle(PAGE, "locked");
        ui.toggle(PAGE, "wifi"); // a later action proves the first one never arrived
        awaitLine("[on] Wi-Fi");
        assertEquals("[off] Locked [disabled]", line("Locked"));
        assertFalse(rt.log().snapshot().toString().contains("item=locked"), rt.log().snapshot().toString());
    }

    @Test
    void choicePicksDeutsch() throws Exception {
        start();
        ui.select(PAGE, "lang");
        ui.select(LANG, "1");
        awaitLine("Language: Deutsch >");
        assertEquals(Arrays.asList("( ) English", "(*) Deutsch", "( ) Français"), ui.render(LANG));
    }

    @Test
    void dropdownAndSlidersPersist() throws Exception {
        start();
        ui.pick(PAGE, "level", 2);
        awaitLine("Level: Mid v");
        ui.turn(PAGE, "bass", 9);
        awaitLine("Bass: 6 [-6..6]");
        ui.turn(PAGE, "vol", 5);
        awaitLine("Volume: 4 [0..10]");
        Properties p = stored();
        assertEquals("2", p.getProperty("level"));
        assertEquals("6", p.getProperty("bass"));
        assertEquals("4", p.getProperty("vol"));
    }

    @Test
    void storedValuesAreRestored() throws Exception {
        write(new File(FakePlatform.roots(tmp).persist(), "data/inputs-demo.properties"),
                "name=Al\nlevel=3\nbass=-2\nvol=8\n");
        start();
        assertEquals("Name: Al", line("Name"));
        assertEquals("Level: High v", line("Level"));
        assertEquals("Bass: -2 [-6..6]", line("Bass"));
        assertEquals("Volume: 8 [0..10]", line("Volume"));
    }

    @Test
    void progressNext() throws Exception {
        start();
        ui.select(PAGE, "prog-next");
        awaitLine("Progress: 80%");
        ui.select(PAGE, "prog-next");
        awaitLine("Progress: 100%");
        ui.select(PAGE, "prog-next");
        awaitLine("Progress: 0%");
        ui.select(PAGE, "prog-next");
        awaitLine("Progress: 20%");
    }

    @Test
    void swapKindAlternates() throws Exception {
        start();
        ui.toggle(PAGE, "swapme");
        awaitLine("[on] Swap me");
        ui.select(PAGE, "swap");
        awaitLine("Swap me: item");
        assertEquals(22, ui.render(PAGE).size());
        ui.select(PAGE, "swap");
        awaitLine("[on] Swap me");
    }

    @Test
    void confirmYesNo() throws Exception {
        start();
        openPage();
        assertEquals(110, CONFIRM_TEXT.length());
        assertEquals(-1, CONFIRM_TEXT.indexOf('\n'));
        ui.select(PAGE, "confirm");
        await(() -> ("confirm:Confirm|" + CONFIRM_TEXT + "|Yes|No").equals(ui.dialog()), "the long confirm");
        ui.answer(1);
        await(() -> last().contains("value=1"), "the Yes answer: " + last());
        ui.select(PAGE, "confirm2");
        await(() -> "confirm:Confirm|First line\nSecond line|Yes|No".equals(ui.dialog()), "the 2-line confirm");
        ui.leave(); // BACK is No
        await(() -> last().contains("value=0"), "the No answer: " + last());
        assertNull(ui.dialog());
    }

    @Test
    void toastAndDelayedToast() throws Exception {
        start();
        ui.select(PAGE, "toast5");
        await(() -> last().contains("item=toast5"), "the toast5 action");
        Thread.sleep(200);
        assertNull(ui.toast(), "not before 5 s");
        skew.addAndGet(5000);
        await(() -> "[plug] Saved".equals(ui.toast()), "the delayed toast: " + ui.toast());
        long seq = table.snapshot().toast().seq();
        ui.select(PAGE, "toast");
        await(() -> table.snapshot().toast().seq() > seq, "a second toast");
        assertEquals("[plug] Saved", ui.toast());
    }

    @Test
    void spellerSaveFlow() throws Exception {
        start();
        openPage();
        ui.select(PAGE, "name");
        await(() -> "speller:Name|".equals(ui.dialog()), "the speller: " + ui.dialog());
        ui.type("BC");
        ui.leave(); // BACK is done
        awaitLine("Name: BC");
        List<String> lines = ui.render(PAGE);
        assertEquals("Save name", lines.get(lines.indexOf("Name: BC") + 1), lines.toString());
        assertEquals(23, lines.size());
        ui.select(PAGE, "save");
        await(() -> !ui.render(PAGE).contains("Save name"), "the Save row removed");
        assertEquals("Name: BC", line("Name"));
        await(() -> "Name saved".equals(ui.toast()), "the saved toast: " + ui.toast());
        assertEquals("BC", stored().getProperty("name"));
    }

    @Test
    void spellerUnchangedAddsNoSaveRow() throws Exception {
        write(new File(FakePlatform.roots(tmp).persist(), "data/inputs-demo.properties"), "name=Al\n");
        start();
        openPage();
        ui.select(PAGE, "name");
        await(() -> "speller:Name|Al".equals(ui.dialog()), "the speller from the stored name: " + ui.dialog());
        ui.leave(); // nothing typed: the stored name comes back
        await(() -> last().contains("text=Al"), "the unchanged answer: " + last());
        assertFalse(ui.render(PAGE).contains("Save name"), ui.render(PAGE).toString());
        assertNull(ui.toast());
    }

    @Test
    void manifestIsApi5Version100() throws Exception {
        String json = manifest();
        assertTrue(json.contains("\"api\":5"), json);
        assertTrue(json.contains("\"version\":\"1.0.0\""), json);
        assertTrue(json.contains("\"permissions\":[\"ui.slot.statusbar\"]"), json);
        assertTrue(json.contains("\"icons\":[\"plug-24\"]"), json);
    }

    /** Stops the runtime (flushing storage) and reads the plugin's stored properties. */
    private Properties stored() throws IOException {
        rt.stop();
        rt = null;
        Properties p = new Properties();
        File f = new File(FakePlatform.roots(tmp).persist(), "data/inputs-demo.properties");
        if (f.exists()) {
            FileInputStream in = new FileInputStream(f);
            try {
                p.load(in);
            } finally {
                in.close();
            }
        }
        return p;
    }

    private static String manifest() throws IOException {
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(System.getProperty("mibsdk.pluginJar"))) {
            java.io.InputStream in = z.getInputStream(z.getEntry("plugin.json"));
            return new String(in.readAllBytes(), "UTF-8");
        }
    }
}
