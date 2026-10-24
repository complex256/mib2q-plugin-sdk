package sample.hellomenu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

class HelloMenuPluginTest {

    @TempDir
    File tmp;

    private SdkRuntime rt;
    private final UiTable table = new UiTable(64);
    private final FakeUi ui = new FakeUi(table);

    @AfterEach
    void tearDown() {
        if (rt != null) {
            rt.stop();
        }
    }

    private FakePlatform start(String... features) throws IOException {
        return startGranting("ui.slot.carmenu", features);
    }

    private FakePlatform startGranting(String grants, String... features) throws IOException {
        Set<String> f = new HashSet<String>(Arrays.asList(features));
        FakePlatform fake = new FakePlatform("HMI_TEST", f);
        SdkRoots roots = FakePlatform.roots(tmp);
        File base = roots.install();
        new File(base, "plugins").mkdirs();
        Files.copy(new File(System.getProperty("mibsdk.pluginJar")).toPath(),
                new File(base, "plugins/hello-menu.jar").toPath(), StandardCopyOption.REPLACE_EXISTING);
        write(new File(base, "sdk.properties"), "plugin.start.delay.ms=0\nheap.low.bytes=0\nui.publish.min.ms=0\n"
                + "log.file=" + new File(tmp, "mibsdk.log").getPath() + "\n");
        write(new File(base, "grants.properties"), "hello-menu=" + grants + "\n");
        Clock clock = new Clock() {
            public long uptimeMs() {
                return System.nanoTime() / 1000000L;
            }
        };
        rt = new SdkRuntime(RuntimeConfig.load(roots), fake, clock, new HookTable(64), table, new RingLog(500));
        rt.start();
        PluginState want = grants.isEmpty() ? PluginState.BLOCKED_PERMISSIONS : PluginState.ENABLED;
        await(() -> rt.statuses().size() == 1 && rt.statuses().get(0).state() == want, want.name());
        return fake;
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
        fail("never " + what + ": " + rt.statuses() + "\n" + rt.log().snapshot());
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

    private static final String PAGE = "hello-menu/fruits";

    @Test
    void carRowOpensListPage() throws Exception {
        start(Features.SLOT_CAR_MENU, Features.UI_PAGES);
        await(() -> ui.carRows().contains("Hello"), "Hello car row");
        ui.selectRow(ui.carRows().indexOf("Hello"));
        await(() -> PAGE.equals(ui.openPageKey()), "page open");
        List<String> lines = ui.render(PAGE);
        assertEquals(4, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains("Apple"), lines.toString());
        assertTrue(lines.get(1).contains("Pear"), lines.toString());
        assertTrue(lines.get(2).contains("Plum"), lines.toString());
        assertTrue(lines.get(3).contains("Selected: <none>"), lines.toString());
    }

    @Test
    void selectingItemUpdatesInfoAndStorage() throws Exception {
        start(Features.SLOT_CAR_MENU, Features.UI_PAGES);
        await(() -> !ui.render(PAGE).isEmpty(), "page registered");
        ui.select(PAGE, "pear");
        await(() -> ui.render(PAGE).get(3).contains("Selected: Pear"), "Selected: Pear");
        rt.stop(); // flushes storage
        rt = null;
        Properties p = new Properties();
        FileInputStream in = new FileInputStream(new File(FakePlatform.roots(tmp).persist(), "data/hello-menu.properties"));
        try {
            p.load(in);
        } finally {
            in.close();
        }
        assertEquals("pear", p.getProperty("last"));
    }

    @Test
    void pageReachableFromPluginsMenuWithoutCarSlotFeature() throws Exception {
        start(Features.UI_PAGES);
        await(() -> !ui.render(PAGE).isEmpty(), "page registered");
        assertFalse(ui.carRows().contains("Hello"), ui.carRows().toString());
        List<String> menu = ui.render("mibsdk/plugin.hello-menu");
        boolean linked = false;
        for (mibsdk.platform.UiEntry e : table.snapshot().pages().get("mibsdk/plugin.hello-menu").entries()) {
            if (e.kind() == mibsdk.platform.UiEntry.LINK && PAGE.equals(e.targetPageKey())) {
                linked = true;
            }
        }
        assertTrue(linked, menu.toString());
    }

    /** UI off (wrap-loop latch, ui.enabled=false, count or init failure): inert UI handles, still ENABLED. */
    @Test
    void uiOffStillEnables() throws Exception {
        start(); // start() awaits ENABLED
        assertFalse(table.snapshot().pages().containsKey(PAGE), table.snapshot().pages().keySet().toString());
        assertFalse(ui.carRows().contains("Hello"), ui.carRows().toString());
    }

    /** The runtime blocks a plugin whose declared permission is not granted: it never runs, so it adds no Car row. */
    @Test
    void ungrantedCarMenuPermissionBlocksPluginWithoutCarRow() throws Exception {
        startGranting("", Features.SLOT_CAR_MENU, Features.UI_PAGES);
        assertFalse(ui.carRows().contains("Hello"), ui.carRows().toString());
        assertTrue(ui.render(PAGE).isEmpty());
    }

    @Test
    void lastSelectionIsRestoredFromStorage() throws Exception {
        write(new File(FakePlatform.roots(tmp).persist(), "data/hello-menu.properties"), "last=plum\n");
        start(Features.SLOT_CAR_MENU, Features.UI_PAGES);
        await(() -> !ui.render(PAGE).isEmpty(), "page registered");
        assertTrue(ui.render(PAGE).get(3).contains("Selected: Plum"), ui.render(PAGE).toString());
    }

    private boolean linked() {
        for (mibsdk.platform.UiEntry e : table.snapshot().pages().get("mibsdk/plugin.hello-menu").entries()) {
            if (e.kind() == mibsdk.platform.UiEntry.LINK && PAGE.equals(e.targetPageKey())) {
                return true;
            }
        }
        return false;
    }
}
