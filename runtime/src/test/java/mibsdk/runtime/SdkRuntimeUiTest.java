package mibsdk.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import mibsdk.api.Features;
import mibsdk.api.Permissions;
import mibsdk.platform.UiEntry;
import mibsdk.platform.UiInput;
import mibsdk.platform.UiPage;
import mibsdk.platform.UiSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The runtime's Plugins menu, user on/off, status-line indicators and boot-guard reset, end to end. */
class SdkRuntimeUiTest {

    private static final String[] UI_PERMS = {Permissions.UI_SLOT_CAR_MENU, Permissions.UI_SLOT_STATUS_BAR};
    private static final String PAGE_AND_ROW = ""
            + "Page p = ctx.ui().page(PageSpec.of(\"main\", \"Rack\"), null);\n"
            + "ctx.ui().carMenuRow(\"Rack\", p);\n";
    /** Throws on every IgnitionChanged: three of them quarantine the plugin. */
    private static final String THROWS_ON_IGNITION =
            "ctx.events().on(IgnitionChanged.class, e -> { throw new IllegalStateException(\"bad\"); });";
    /** First IgnitionChanged(true) mutes rear PDC (until ignition off), the next releases it. */
    private static final String MUTE_MEMBERS = "VehicleOverride ov;";
    private static final String MUTE_BODY = ""
            + "ctx.events().on(IgnitionChanged.class, e -> {\n"
            + "  if (!e.isOn()) return;\n"
            + "  if (ov == null) {\n"
            + "    ov = ctx.vehicle().parking().overrideSoundDirections(\n"
            + "        ctx.vehicle().parking().soundDirections().withRear(false), OverrideScope.UNTIL_IGNITION_OFF);\n"
            + "    ctx.log().info(\"muted\");\n"
            + "  } else { ov.release(); ov = null; ctx.log().info(\"released\"); }\n"
            + "});";

    @TempDir
    File tmp;

    private UiTestRuntime r;

    @AfterEach
    void tearDown() throws InterruptedException {
        if (r != null) {
            r.stop();
        }
    }

    private UiTestRuntime runtime(String... extraFeatures) {
        Set<String> f = new HashSet<String>(UiTestRuntime.UI_FEATURES);
        f.addAll(Arrays.asList(extraFeatures));
        r = new UiTestRuntime(tmp, f);
        return r;
    }

    private void toggle(String id, boolean on) {
        r.table.input(new UiInput(UiInput.TOGGLE_REQUESTED, "mibsdk/plugin." + id, "enabled", on));
    }

    private PluginStatus status(String id) {
        for (PluginStatus s : r.rt.statuses()) {
            if (s.id().equals(id)) {
                return s;
            }
        }
        return null;
    }

    private void awaitStatus(String id, PluginState state, String reason) throws InterruptedException {
        for (int i = 0; i < 500; i++) {
            PluginStatus s = status(id);
            if (s != null && s.state() == state && reason.equals(s.reason())) {
                return;
            }
            Thread.sleep(10);
        }
        fail(id + " never reached " + state + " " + reason + ": " + r.rt.statuses() + "\n" + r.rt.log().snapshot());
    }

    private static UiEntry entry(UiSnapshot s, String pageKey, String entryKey) {
        UiPage p = s.pages().get(pageKey);
        if (p == null) {
            return null;
        }
        for (UiEntry e : p.entries()) {
            if (entryKey.equals(e.key())) {
                return e;
            }
        }
        return null;
    }

    private static String label(UiSnapshot s, String pageKey, String entryKey) {
        UiEntry e = entry(s, pageKey, entryKey);
        return e == null ? null : e.label();
    }

    private static boolean hasInfo(UiSnapshot s, String pageKey, String text) {
        UiPage p = s.pages().get(pageKey);
        if (p == null) {
            return false;
        }
        for (UiEntry e : p.entries()) {
            if (e.kind() == UiEntry.INFO && text.equals(e.label())) {
                return true;
            }
        }
        return false;
    }

    private static String stateWord(UiSnapshot s, String id) {
        UiEntry e = entry(s, "mibsdk/plugins", "plugin." + id);
        return e == null ? null : e.value();
    }

    private Properties bootState() throws IOException {
        Properties p = new Properties();
        FileInputStream in = new FileInputStream(new File(r.persist(), "boot.state"));
        try {
            p.load(in);
        } finally {
            in.close();
        }
        return p;
    }

    @Test
    void offPluginStaysDisabledAcrossReload() throws Exception {
        runtime();
        r.plugin("rack", "Bike rack", UI_PERMS, PAGE_AND_ROW, null);
        r.start();
        r.awaitState("rack", PluginState.ENABLED);
        r.await(s -> "On".equals(stateWord(s, "rack")) && s.carRows().size() == 2, "lists rack as On");

        toggle("rack", false);
        awaitStatus("rack", PluginState.DISABLED, "off in Plugins menu");
        UiSnapshot off = r.await(s -> "Off".equals(stateWord(s, "rack")), "lists rack as Off");
        assertEquals(1, off.carRows().size(), "its Car row is gone");
        assertFalse(entry(off, "mibsdk/plugin.rack", "enabled").on());
        assertTrue(new File(r.persist(), "plugins.properties").isFile());

        r.rt.reload("rack");
        assertEquals(PluginState.DISABLED, status("rack").state());
        assertEquals("off in Plugins menu", status("rack").reason());

        r.stop();
        r.start(); // the choice is persistent
        assertEquals(PluginState.DISABLED, status("rack").state());
        assertEquals("off in Plugins menu", status("rack").reason());

        toggle("rack", true);
        r.awaitState("rack", PluginState.ENABLED);
        r.await(s -> "On".equals(stateWord(s, "rack")) && s.carRows().size() == 2, "lists rack as On again");
    }

    @Test
    void toggleOnReenablesQuarantinedPlugin() throws Exception {
        runtime();
        r.plugin("flaky", "Flaky", new String[0], THROWS_ON_IGNITION, null);
        r.start();
        r.awaitState("flaky", PluginState.ENABLED);
        for (int i = 0; i < 3; i++) {
            r.fake.setIgnition(i % 2 == 0);
        }
        r.awaitState("flaky", PluginState.QUARANTINED);
        UiSnapshot q = r.await(s -> "Quarantined".equals(stateWord(s, "flaky")), "lists flaky as Quarantined");
        assertNotNull(entry(q, "mibsdk/plugin.flaky", "enabled"), "a quarantined plugin keeps its toggle");

        toggle("flaky", true);
        r.awaitState("flaky", PluginState.ENABLED);
        r.await(s -> "On".equals(stateWord(s, "flaky")), "lists flaky as On");

        for (int i = 0; i < 3; i++) {
            r.fake.setIgnition(i % 2 == 0);
        }
        r.awaitState("flaky", PluginState.QUARANTINED);
        toggle("flaky", false); // off works from QUARANTINED too
        awaitStatus("flaky", PluginState.DISABLED, "off in Plugins menu");
    }

    @Test
    void safeModeStillPublishesPluginsMenuAndSafeIndicator() throws Exception {
        runtime();
        r.plugin("rack", "Bike rack", UI_PERMS, PAGE_AND_ROW, null);
        r.safeModeMarker();
        r.start();
        UiSnapshot s = r.await(x -> x.statusLine().startsWith("SAFE")
                && "Plugins (safe mode)".equals(x.carRows().get(0).label()), "shows safe mode");
        assertEquals(1, s.carRows().size());
        assertTrue(hasInfo(s, "mibsdk/plugins", "SDK safe mode: plugins are off (marker)"));
        assertTrue(hasInfo(s, "mibsdk/sdk", "Safe mode: on (marker)"));
        assertTrue(hasInfo(s, "mibsdk/sdk", "SAFE_MODE marker set: clear it with mibsdk-dev safe-mode off"));
        assertNull(entry(s, "mibsdk/sdk", "reset"), "no on-car reset for the marker");
    }

    @Test
    void normalBootLabelsAndSdkPage() throws Exception {
        runtime();
        r.start();
        UiSnapshot s = r.await(x -> x.pages().containsKey("mibsdk/sdk"), "publishes the SDK page");
        assertEquals("Plugins", s.carRows().get(0).label());
        assertEquals("", s.statusLine());
        assertTrue(hasInfo(s, "mibsdk/sdk", "API 3 · adapter " + r.fake.id()));
        assertTrue(hasInfo(s, "mibsdk/sdk", "Safe mode: off"));
        assertTrue(hasInfo(s, "mibsdk/sdk", "Boot guard: ok"));

        long v = r.table.snapshot().version();
        r.clock.advance(SdkRuntime.UI_POLL_MS);
        Thread.sleep(300);
        assertEquals(v, r.table.snapshot().version(), "a poll that changes nothing publishes nothing");
    }

    @Test
    void resetBootGuardActionResetsFile() throws Exception {
        runtime();
        r.bootState("unhealthyBoots=3\nlastShutdownClean=false\n");
        r.start();
        assertTrue(r.rt.safeMode());
        UiSnapshot s = r.await(x -> entry(x, "mibsdk/sdk", "reset") != null, "offers the reset");
        assertTrue(hasInfo(s, "mibsdk/sdk", "Safe mode: on (unhealthy-boots)"));
        assertEquals("4", bootState().getProperty("unhealthyBoots"));

        r.table.input(new UiInput(UiInput.ENTRY_SELECTED, "mibsdk/sdk", "reset", false));
        UiSnapshot after = r.await(x -> hasInfo(x, "mibsdk/sdk", "Takes effect at the next start"),
                "says the reset takes effect at the next start");
        assertNull(entry(after, "mibsdk/sdk", "reset"));
        Properties p = bootState();
        assertEquals("0", p.getProperty("unhealthyBoots"));
        assertEquals("0", p.getProperty("wrapBoots"));
    }

    @Test
    void resetIsRefusedUnderTheWrapLoopLatch() throws Exception {
        runtime();
        r.bootState("unhealthyBoots=3\nlastShutdownClean=false\nwrapBoots=3\n");
        r.start();
        r.await(x -> x.pages().containsKey("mibsdk/sdk"), "publishes the SDK page");
        r.table.input(new UiInput(UiInput.ENTRY_SELECTED, "mibsdk/sdk", "reset", false));
        r.awaitCount("mibsdk", "boot guard reset ignored: only offered in count-based safe mode", 1);
        Properties p = bootState();
        assertEquals("4", p.getProperty("unhealthyBoots"));
        assertEquals("3", p.getProperty("wrapBoots"));
        for (UiPage page : r.table.snapshot().pages().values()) {
            for (UiEntry e : page.entries()) {
                assertTrue(e.label() == null || e.label().toLowerCase().indexOf("latch") < 0, e.label());
            }
        }
    }

    @Test
    void overrideIndicatorFollowsLedger() throws Exception {
        runtime(Features.IGNITION, Features.PARKING, Features.HOOK_PARKING);
        r.plugin("pdc", "PDC", new String[] {"vehicle.read", "vehicle.parking.write"}, MUTE_BODY, MUTE_MEMBERS);
        r.start();
        r.awaitState("pdc", PluginState.ENABLED);
        r.fake.setIgnition(true);
        r.awaitCount("pdc", "muted", 1);
        r.clock.advance(SdkRuntime.UI_POLL_MS);
        UiSnapshot on = r.await(s -> s.statusLine().indexOf("PDC R OFF") >= 0, "shows PDC R OFF within one poll");
        assertTrue(on.statusLine().startsWith("PDC R OFF"), "indicators come first: " + on.statusLine());

        r.fake.setIgnition(true);
        r.awaitCount("pdc", "released", 1);
        for (int i = 0; i < 100 && !r.rt.activeOverrides().isEmpty(); i++) {
            Thread.sleep(20); // the restore is applied and confirmed on the ledger thread
        }
        assertTrue(r.rt.activeOverrides().isEmpty(), "restore confirmed: " + r.rt.activeOverrides());
        r.clock.advance(SdkRuntime.UI_POLL_MS);
        r.await(s -> s.statusLine().indexOf("PDC R OFF") < 0, "drops PDC R OFF after the restore");
    }

    /** Controller requirement 1: the SDK input handler only queues work; the dispatcher keeps routing input. */
    @Test
    void sdkInputNeverBlocksTheDispatcher() throws Exception {
        runtime();
        r.plugin("apple", "Apple", UI_PERMS, PAGE_AND_ROW, null);
        r.plugin("zslow", "Slow", new String[0], "ctx.log().info(\"blocking\"); Thread.sleep(3000L);", null);
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
        r.awaitCount("zslow", "blocking", 1);
        toggle("apple", false); // queued behind zslow's enable on the control thread
        UiSnapshot before = r.table.snapshot();
        r.table.input(new UiInput(UiInput.ROW_SELECTED, "mibsdk/plugins", null, false));
        UiSnapshot opened = r.await(s -> s.openSeq() > before.openSeq(), "routes input after the toggle");
        assertEquals("mibsdk/plugins", opened.openPageKey());
        assertEquals(PluginState.ENABLED, status("apple").state(), "the toggle waits for the control thread");
        assertTrue(status("zslow") == null || status("zslow").state() != PluginState.ENABLED,
                "zslow's onEnable has not returned yet");

        starter.join(15000);
        awaitStatus("apple", PluginState.DISABLED, "off in Plugins menu");
    }

    /** Fix round 1 ruling: safe mode lists installed plugins (manifest only) with their toggles; none runs. */
    @Test
    void safeModeListsInstalledPluginsWithToggles() throws Exception {
        runtime();
        r.plugin("rack", "Bike rack", UI_PERMS, "ctx.log().info(\"enabled\");\n" + PAGE_AND_ROW, null);
        r.safeModeMarker();
        r.start();
        UiSnapshot s = r.await(x -> entry(x, "mibsdk/plugin.rack", "enabled") != null, "lists rack with a toggle");
        assertTrue(entry(s, "mibsdk/plugin.rack", "enabled").on(), "the user's choice: on");
        assertTrue(hasInfo(s, "mibsdk/plugin.rack", "Safe mode: changes apply after it ends"));
        assertEquals("Bike rack", entry(s, "mibsdk/plugins", "plugin.rack").label());
        assertEquals("Off", stateWord(s, "rack"));
        assertNotNull(status("rack"));
        assertTrue(status("rack").state() != PluginState.ENABLED, "statuses() reports it not running");

        toggle("rack", false);
        r.await(x -> !entry(x, "mibsdk/plugin.rack", "enabled").on(), "shows the recorded choice: off");
        assertTrue(new File(r.persist(), "plugins.properties").isFile());
        assertEquals(0, r.count("rack", "enabled"), "no plugin class ran in safe mode");
    }

    /** Fix round 1 ruling: the toggle shows the user's recorded choice, not whether the plugin runs. */
    @Test
    void toggleShowsTheUsersChoiceNotTheState() throws Exception {
        runtime();
        r.plugin("flaky", "Flaky", new String[0], THROWS_ON_IGNITION, null);
        r.start();
        r.awaitState("flaky", PluginState.ENABLED);
        for (int i = 0; i < 3; i++) {
            r.fake.setIgnition(i % 2 == 0);
        }
        r.awaitState("flaky", PluginState.QUARANTINED);
        UiSnapshot q = r.await(s -> "Quarantined".equals(stateWord(s, "flaky")), "lists flaky as Quarantined");
        assertTrue(entry(q, "mibsdk/plugin.flaky", "enabled").on(), "the user never switched it off");
    }

    /** Fix round 1, Important 2: switching a dependency back on re-enables what was blocked on it. */
    @Test
    void dependencyBackOnReenablesDependents() throws Exception {
        runtime();
        r.plugin("base", "Base", new String[0], "", null);
        r.plugin("child", "Child", new String[0], "", null, new String[] {"base"});
        r.plugin("grand", "Grand", new String[0], "", null, new String[] {"child"});
        r.userToggles("base.off=true\n");
        r.start();
        awaitStatus("base", PluginState.DISABLED, "off in Plugins menu");
        awaitStatus("child", PluginState.BLOCKED_DEPENDENCY, "dependency base is DISABLED");
        UiSnapshot s = r.await(x -> entry(x, "mibsdk/plugin.child", "enabled") != null,
                "a dependent blocked on a switched-off plugin keeps its toggle");
        assertTrue(entry(s, "mibsdk/plugin.child", "enabled").on());

        toggle("base", true);
        r.awaitState("base", PluginState.ENABLED);
        r.awaitState("child", PluginState.ENABLED);
        r.awaitState("grand", PluginState.ENABLED);
    }

    /**
     * Fix round 1, Important 2: no SDK UI outlives the runtime. stop() publishes the down snapshot first, before any
     * plugin is disabled, and nothing is published after it.
     */
    @Test
    void stopTakesTheUiDownBeforeDisablingPlugins() throws Exception {
        runtime();
        r.plugin("rack", "Bike rack", UI_PERMS, PAGE_AND_ROW,
                "protected void onDisable() { ctx.log().info(\"disabling\"); }");
        r.start();
        r.awaitState("rack", PluginState.ENABLED);
        r.await(s -> s.carRows().size() == 2, "shows the rack row");
        RingLog log = r.rt.log();

        r.stop();
        UiSnapshot last = r.table.snapshot();
        assertTrue(last.isDown(), "the last snapshot is the down one");
        assertTrue(last.carRows().isEmpty() && last.pages().isEmpty() && last.statusLine().isEmpty());
        int down = -1;
        int disabling = -1;
        List<String> lines = log.snapshot();
        for (int i = 0; i < lines.size(); i++) {
            if (down < 0 && lines.get(i).endsWith(" ui down")) {
                down = i;
            }
            if (disabling < 0 && lines.get(i).endsWith(" disabling")) {
                disabling = i;
            }
        }
        assertTrue(down >= 0 && disabling > down, "ui down before the plugin's onDisable: " + lines);
        r = null; // already stopped
    }

    /** Status line: plugin-id order, then the user's moves and hides from the plugin pages, kept across a restart. */
    @Test
    void statusItemsCanBeHiddenAndReorderedFromThePluginsMenu() throws Exception {
        runtime();
        String[] status = {Permissions.UI_SLOT_STATUS_BAR};
        r.plugin("zeta", "Zeta", status, "ctx.ui().statusItem(\"ZZ long text\");", null);
        r.plugin("alpha", "Alpha", status, "ctx.ui().statusItem(\"AA long text\");", null);
        r.plugin("plain", "Plain", new String[0], "", null);
        r.start();
        r.awaitState("zeta", PluginState.ENABLED);
        r.awaitState("alpha", PluginState.ENABLED);
        UiSnapshot first = r.await(s -> "AA long text ZZ long...".equals(s.statusLine())
                && "Status item (1 of 2)".equals(label(s, "mibsdk/plugin.alpha", "status"))
                && s.pages().containsKey("mibsdk/plugin.plain"), "plugin-id order, fair share, status rows");
        assertTrue(entry(first, "mibsdk/plugin.alpha", "status").on());
        assertNotNull(entry(first, "mibsdk/plugin.zeta", "status.earlier"));
        assertNull(entry(first, "mibsdk/plugin.plain", "status"), "no item and no permission: no rows");

        r.table.input(new UiInput(UiInput.ENTRY_SELECTED, "mibsdk/plugin.zeta", "status.earlier", false));
        r.await(s -> "ZZ long text AA long...".equals(s.statusLine()), "zeta moved earlier");
        r.await(s -> "Status item (1 of 2)".equals(label(s, "mibsdk/plugin.zeta", "status")),
                "zeta's page shows its new place");

        r.table.input(new UiInput(UiInput.TOGGLE_REQUESTED, "mibsdk/plugin.zeta", "status", false));
        r.await(s -> "AA long text".equals(s.statusLine()) && entry(s, "mibsdk/plugin.zeta", "status") != null
                && !entry(s, "mibsdk/plugin.zeta", "status").on(),
                "zeta hidden, alpha gets the line");
        assertEquals(PluginState.ENABLED, status("zeta").state(), "hiding the item leaves the plugin alone");
        assertTrue(new File(r.persist(), "statusline.properties").isFile());

        r.stop();
        r.start(); // the choices are persistent
        r.awaitState("zeta", PluginState.ENABLED);
        r.awaitState("alpha", PluginState.ENABLED);
        r.await(s -> "AA long text".equals(s.statusLine()), "still hidden after a restart");
        r.table.input(new UiInput(UiInput.TOGGLE_REQUESTED, "mibsdk/plugin.zeta", "status", true));
        r.await(s -> "ZZ long text AA long...".equals(s.statusLine()), "shown again, in the saved order");
    }

    /** Review fix: like plugins.properties, statusline.properties is re-read at reload (mibpkg edits it). */
    @Test
    void statusPrefsAreReReadAtReload() throws Exception {
        runtime();
        String[] status = {Permissions.UI_SLOT_STATUS_BAR};
        r.plugin("zeta", "Zeta", status, "ctx.ui().statusItem(\"ZZ\");", null);
        r.plugin("alpha", "Alpha", status, "ctx.ui().statusItem(\"AA\");", null);
        r.start();
        r.awaitState("zeta", PluginState.ENABLED);
        r.awaitState("alpha", PluginState.ENABLED);
        r.table.input(new UiInput(UiInput.TOGGLE_REQUESTED, "mibsdk/plugin.zeta", "status", false));
        r.await(s -> "AA".equals(s.statusLine()), "zeta hidden");

        java.io.FileOutputStream out = new java.io.FileOutputStream(new File(r.persist(), "statusline.properties"));
        try {
            out.write("order=zeta\n".getBytes("ISO-8859-1")); // another writer showed zeta and moved it first
        } finally {
            out.close();
        }
        r.rt.reload("alpha");
        r.awaitState("alpha", PluginState.ENABLED);
        r.await(s -> "ZZ AA".equals(s.statusLine()), "the file is re-read at reload");
    }
}
