package sample.crashy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
import mibsdk.runtime.PluginStatus;
import mibsdk.runtime.RingLog;
import mibsdk.runtime.RuntimeConfig;
import mibsdk.runtime.SdkRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Mode {@code ui-throw} against the real built crashy jar, as the device loads it. */
class CrashyUiModeTest {

    private static final String PAGE = "crashy/boom";
    private static final String MENU = "mibsdk/plugin.crashy";

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

    @Test
    void uiThrowQuarantinesAndLeavesStoppedNotice() throws Exception {
        start("ui-throw", Features.UI_PAGES, Features.SLOT_STATUS_BAR);
        await(() -> !ui.render(PAGE).isEmpty(), "crashy page registered");
        await(() -> ui.statusLine().contains("C"), "crashy status item shown");
        String first = ui.statusLine();
        await(() -> !ui.statusLine().equals(first), "status item keeps changing");

        ui.select(MENU, linkKey());
        await(() -> PAGE.equals(ui.openPageKey()), "crashy page open");
        for (int i = 0; i < 3; i++) {
            ui.select(PAGE, "boom");
            Thread.sleep(50);
        }
        await(() -> state() == PluginState.QUARANTINED, "QUARANTINED");

        await(() -> {
            List<String> lines = ui.render(PAGE);
            return lines.size() == 1 && lines.get(0).contains("Crashy stopped: ");
        }, "stopped notice on the open page");
        await(() -> !ui.statusLine().contains("C"), "status item gone");
        String after = ui.statusLine();
        Thread.sleep(300);
        assertEquals(after, ui.statusLine(), "the status loop stopped with the plugin");
        ui.back();
        assertFalse(PAGE.equals(ui.openPageKey()), "BACK leaves the notice page");
    }

    @Test
    void otherModesRegisterNoUi() throws Exception {
        start("", Features.UI_PAGES, Features.SLOT_STATUS_BAR);
        await(() -> state() == PluginState.ENABLED, "ENABLED");
        Thread.sleep(200);
        assertTrue(ui.render(PAGE).isEmpty(), ui.render(PAGE).toString());
        assertEquals("", ui.statusLine());
    }

    private String linkKey() {
        UiPage menu = table.snapshot().pages().get(MENU);
        assertNotNull(menu, "crashy's Plugins entry");
        for (UiEntry e : menu.entries()) {
            if (e.kind() == UiEntry.LINK && PAGE.equals(e.targetPageKey())) {
                return e.key();
            }
        }
        fail("no link to " + PAGE + " in " + ui.render(MENU));
        return null;
    }

    private PluginState state() {
        List<PluginStatus> s = rt.statuses();
        return s.size() == 1 ? s.get(0).state() : null;
    }

    private void start(String mode, String... features) throws IOException {
        Set<String> f = new HashSet<String>(Arrays.asList(features));
        FakePlatform fake = new FakePlatform("HMI_TEST", f);
        SdkRoots roots = FakePlatform.roots(tmp);
        File base = roots.install();
        new File(base, "plugins").mkdirs();
        Files.copy(new File(System.getProperty("mibsdk.pluginJar")).toPath(),
                new File(base, "plugins/crashy.jar").toPath(), StandardCopyOption.REPLACE_EXISTING);
        write(new File(base, "sdk.properties"), "plugin.start.delay.ms=0\nheap.low.bytes=0\nui.publish.min.ms=0\n"
                + "log.file=" + new File(tmp, "mibsdk.log").getPath() + "\n");
        write(new File(base, "grants.properties"), "crashy=ui.slot.statusbar\n");
        write(new File(roots.persist(), "data/crashy.properties"), "mode=" + mode + "\n");
        Clock clock = new Clock() {
            public long uptimeMs() {
                return System.nanoTime() / 1000000L;
            }
        };
        rt = new SdkRuntime(RuntimeConfig.load(roots), fake, clock, new HookTable(64), table, new RingLog(500));
        rt.start();
        await(() -> state() == PluginState.ENABLED, "ENABLED");
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
        fail("never " + what + ": " + rt.statuses() + " status=" + ui.statusLine() + "\n" + rt.log().snapshot());
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
}
