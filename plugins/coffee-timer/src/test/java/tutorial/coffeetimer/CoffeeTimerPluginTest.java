package tutorial.coffeetimer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import mibsdk.adapter.fake.FakePlatform;
import mibsdk.adapter.fake.FakeUi;
import mibsdk.api.Features;
import mibsdk.platform.HookTable;
import mibsdk.platform.SdkRoots;
import mibsdk.platform.UiTable;
import mibsdk.runtime.Clock;
import mibsdk.runtime.PluginState;
import mibsdk.runtime.RingLog;
import mibsdk.runtime.RuntimeConfig;
import mibsdk.runtime.SdkRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoffeeTimerPluginTest {

    private static final String ID = "coffee-timer";
    /** A plugin page's key is "<plugin id>/<page id>". */
    private static final String PAGE = ID + "/timer";
    /** Stand-ins for the sha16 names mibpkg gives installed icons. */
    private static final String CUP_SHA = "0123456789abcdef";
    private static final String CUP_20_SHA = "fedcba9876543210";
    private static final String ROW = "[png:" + CUP_SHA + "] Coffee";

    @TempDir
    File tmp;

    private SdkRuntime rt;
    private final UiTable table = new UiTable(64);
    private final FakeUi ui = new FakeUi(table);
    /** Added to the real uptime: raising it moves the plugin's Scheduler forward. */
    private final AtomicLong skew = new AtomicLong();

    @AfterEach
    void tearDown() {
        if (rt != null) {
            rt.stop();
        }
    }

    // ---- the harness: install the built jar into a temp "unit" and start the real runtime on the fake car ----

    private void start() throws IOException {
        FakePlatform fake = new FakePlatform("HMI_TEST", new HashSet<String>(Arrays.asList(Features.UI_PAGES,
                Features.UI_INPUTS, Features.UI_ICONS, Features.SLOT_CAR_MENU, Features.SLOT_STATUS_BAR)));
        SdkRoots roots = FakePlatform.roots(tmp);
        File base = roots.install();
        new File(base, "plugins").mkdirs();
        Files.copy(new File(System.getProperty("mibsdk.pluginJar")).toPath(),
                new File(base, "plugins/" + ID + ".jar").toPath(), StandardCopyOption.REPLACE_EXISTING);
        // No start delay, no heap guard, no UI throttle: a test wants the plugin up at once.
        write(new File(base, "sdk.properties"), "plugin.start.delay.ms=0\nheap.low.bytes=0\nui.publish.min.ms=0\n"
                + "log.file=" + new File(tmp, "mibsdk.log").getPath() + "\n");
        // What the user approved at install: every permission plugin.json declares.
        write(new File(base, "grants.properties"), ID + "=ui.slot.carmenu,ui.slot.statusbar\n");
        // What mibpkg installs for the package's icons.
        File icons = new File(base, "icons/" + ID);
        write(new File(icons, "index.properties"), "cup=" + CUP_SHA + ",72,72\ncup-20=" + CUP_20_SHA + ",20,20\n");
        Files.copy(new File("src/main/icons/cup.png").toPath(), new File(icons, CUP_SHA + ".png").toPath());
        Files.copy(new File("src/main/icons/cup-20.png").toPath(), new File(icons, CUP_20_SHA + ".png").toPath());

        Clock clock = () -> System.nanoTime() / 1000000L + skew.get();
        rt = new SdkRuntime(RuntimeConfig.load(roots), fake, clock, new HookTable(64), table, new RingLog(500));
        rt.start();
        await(() -> rt.statuses().size() == 1 && rt.statuses().get(0).state() == PluginState.ENABLED, "ENABLED");
        await(() -> !ui.render(PAGE).isEmpty(), "the page");
    }

    private void await(BooleanSupplier condition, String what) {
        for (int i = 0; i < 500; i++) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }
        fail("never " + what + ": " + ui.render(PAGE) + " " + ui.statusParts() + "\n" + rt.log().snapshot());
    }

    private static void write(File f, String text) throws IOException {
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    /** Stops the runtime (which flushes storage) and reads the plugin's stored values. */
    private Properties stopAndReadStorage() throws IOException {
        rt.stop();
        rt = null;
        Properties p = new Properties();
        try (FileInputStream in = new FileInputStream(new File(FakePlatform.roots(tmp).persist(), "data/" + ID + ".properties"))) {
            p.load(in);
        }
        return p;
    }

    private void minutePasses() {
        skew.addAndGet(CoffeeTimerPlugin.MINUTE_MS);
    }

    // ---- the tests ----

    @Test
    void carRowOpensTheTimerPage() throws Exception {
        start();
        await(() -> ui.carRows().contains(ROW), "the Coffee row: " + ui.carRows());
        ui.selectRow(ui.carRows().indexOf(ROW));
        await(() -> PAGE.equals(ui.openPageKey()), "the page open");
        assertEquals(Arrays.asList(
                "Start timer: 4 min",
                "[on] Pop up when ready",
                "Brew time: 4 [1..10]",
                "Set a brew time, then Start."), ui.render(PAGE));
    }

    @Test
    void sliderAndToggleAreStored() throws Exception {
        start();
        ui.turn(PAGE, "minutes", 7);
        await(() -> ui.render(PAGE).contains("Start timer: 7 min"), "7 min");
        ui.toggle(PAGE, "toast");
        await(() -> ui.render(PAGE).contains("[off] Pop up when ready"), "toggle off");
        Properties stored = stopAndReadStorage();
        assertEquals("7", stored.getProperty("minutes"));
        assertEquals("off", stored.getProperty("toast"));
    }

    @Test
    void storedValuesAreRestored() throws Exception {
        write(new File(FakePlatform.roots(tmp).persist(), "data/" + ID + ".properties"), "minutes=2\ntoast=off\n");
        start();
        List<String> lines = ui.render(PAGE);
        assertEquals("Start timer: 2 min", lines.get(0));
        assertEquals("[off] Pop up when ready", lines.get(1));
    }

    @Test
    void timerCountsDownOnTheStatusLineAndToasts() throws Exception {
        start();
        ui.turn(PAGE, "minutes", 2);
        await(() -> ui.render(PAGE).contains("Start timer: 2 min"), "2 min");
        assertEquals("", ui.statusLine());
        ui.select(PAGE, "start");
        await(() -> "Coffee 2m".equals(ui.statusLine()), "Coffee 2m on the status line");
        minutePasses();
        await(() -> "Coffee 1m".equals(ui.statusLine()), "Coffee 1m");
        assertNull(ui.toast());
        minutePasses();
        await(() -> "Coffee ready".equals(ui.statusLine()), "Coffee ready");
        await(() -> ("[png:" + CUP_20_SHA + "] Coffee is ready").equals(ui.toast()), "the toast: " + ui.toast());
        ui.select(PAGE, "dismiss");
        await(() -> "".equals(ui.statusLine()), "the status item hidden");
    }

    @Test
    void noToastWhenTurnedOff() throws Exception {
        start();
        ui.toggle(PAGE, "toast");
        ui.turn(PAGE, "minutes", 1);
        await(() -> ui.render(PAGE).contains("Start timer: 1 min"), "1 min");
        ui.select(PAGE, "start");
        await(() -> "Coffee 1m".equals(ui.statusLine()), "Coffee 1m");
        minutePasses();
        await(() -> "Coffee ready".equals(ui.statusLine()), "Coffee ready");
        assertNull(ui.toast());
    }

    @Test
    void stopCancelsTheTimer() throws Exception {
        start();
        ui.select(PAGE, "start");
        await(() -> "Coffee 4m".equals(ui.statusLine()), "Coffee 4m");
        ui.select(PAGE, "stop");
        await(() -> ui.render(PAGE).contains("Start timer: 4 min"), "back to Start");
        assertEquals("", ui.statusLine());
    }
}
