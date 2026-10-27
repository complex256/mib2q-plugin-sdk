package sample.hellomenu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import mibsdk.adapter.fake.FakePlatform;
import mibsdk.adapter.fake.FakeUi;
import mibsdk.api.Features;
import mibsdk.test.PluginTestHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HelloMenuPluginTest {

    @TempDir
    File tmp;

    private PluginTestHarness h;
    private FakeUi ui;
    private String page;
    private String row;
    private String rowStar;
    private String statusPng;
    private String statusTinted;

    @AfterEach
    void tearDown() {
        if (h != null) {
            h.close();
        }
    }

    private void start(String... features) {
        use(PluginTestHarness.builder(tmp).features(features).start());
    }

    /** The runtime blocks the plugin when its declared permissions are not granted: it never runs. */
    private void startUngranted(String... features) {
        use(PluginTestHarness.builder(tmp).features(features).grants().startExpectingFailure());
        assertEquals("BLOCKED_PERMISSIONS", h.state());
    }

    /** What the plugin stored on an earlier run: written before the harness starts. */
    private void seedStorage(String text) throws IOException {
        File data = new File(FakePlatform.roots(tmp).persist(), "data/hello-menu.properties");
        data.getParentFile().mkdirs();
        Files.write(data.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private void use(PluginTestHarness started) {
        h = started;
        ui = h.ui();
        page = h.pageKey("fruits");
        row = "[png:" + h.iconSha("hello") + "] Hello";
        rowStar = "[star] Hello";
        // The status icon starts in its own colours.
        statusPng = "[png:" + h.iconSha("hello-20") + "+own] Hello";
        statusTinted = "[png:" + h.iconSha("hello-20") + "] Hello";
    }

    @Test
    void carRowOpensListPage() throws IOException {
        start(Features.SLOT_CAR_MENU, Features.UI_PAGES, Features.UI_ICONS);
        h.await(() -> ui.carRows().contains(row), "Hello car row");
        ui.selectRow(ui.carRows().indexOf(row));
        h.await(() -> page.equals(ui.openPageKey()), "page open");
        List<String> lines = ui.render(page);
        assertEquals(7, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains("Apple"), lines.toString());
        assertTrue(lines.get(1).contains("Pear"), lines.toString());
        assertTrue(lines.get(2).contains("Plum"), lines.toString());
        assertTrue(lines.get(6).contains("Selected: <none>"), lines.toString());
    }

    @Test
    void selectingItemUpdatesInfoAndStorage() throws IOException {
        start(Features.SLOT_CAR_MENU, Features.UI_PAGES);
        h.await(() -> !ui.render(page).isEmpty(), "page registered");
        ui.select(page, "pear");
        h.await(() -> ui.render(page).get(6).contains("Selected: Pear"), "Selected: Pear");
        assertEquals("pear", h.storedValues().getProperty("last")); // stops the runtime, which flushes storage
    }

    @Test
    void pageReachableFromPluginsMenuWithoutCarSlotFeature() throws IOException {
        start(Features.UI_PAGES);
        h.await(() -> !ui.render(page).isEmpty(), "page registered");
        h.openPage("fruits"); // the Plugins-menu page links to it (the SDK pages refresh after the plugin's page)
        assertEquals(page, ui.openPageKey());
        assertFalse(ui.carRows().contains(row), ui.carRows().toString());
    }

    /** UI off (wrap-loop latch, ui.enabled=false, count or init failure): inert UI handles, still ENABLED. */
    @Test
    void uiOffStillEnables() throws IOException {
        start(); // start() awaits ENABLED
        assertTrue(ui.render(page).isEmpty(), "no page registered: " + ui.render(page));
        assertFalse(ui.carRows().contains(row), ui.carRows().toString());
    }

    /** The runtime blocks a plugin whose declared permission is not granted: it never runs, so it adds no Car row. */
    @Test
    void ungrantedCarMenuPermissionBlocksPluginWithoutCarRow() throws IOException {
        startUngranted(Features.SLOT_CAR_MENU, Features.UI_PAGES);
        assertFalse(ui.carRows().contains(row), ui.carRows().toString());
        assertTrue(ui.render(page).isEmpty());
    }

    @Test
    void lastSelectionIsRestoredFromStorage() throws IOException {
        seedStorage("last=plum\n");
        start(Features.SLOT_CAR_MENU, Features.UI_PAGES);
        h.await(() -> ui.render(page).size() > 6 && ui.render(page).get(6).contains("Selected: Plum"),
                "Selected: Plum, restored: " + ui.render(page));
    }

    @Test
    void rowAndStatusCarryIcons() throws IOException {
        start(Features.SLOT_CAR_MENU, Features.SLOT_STATUS_BAR, Features.UI_PAGES, Features.UI_ICONS);
        h.await(() -> ui.carRows().contains(row), "Hello row with the png icon: " + ui.carRows());
        h.await(() -> ui.statusParts().contains(statusPng), "status part with the png icon: " + ui.statusParts());
        ui.select(page, "next-icon");
        h.await(() -> ui.carRows().contains(rowStar), "star row: " + ui.carRows());
    }

    @Test
    void nextStatusIconCycles() throws IOException {
        start(Features.SLOT_CAR_MENU, Features.SLOT_STATUS_BAR, Features.UI_PAGES, Features.UI_ICONS);
        h.await(() -> ui.statusParts().contains(statusPng), "png status: " + ui.statusParts());
        ui.select(page, "next-status-icon");
        h.await(() -> ui.statusParts().contains("[star] Hello"), "star status: " + ui.statusParts());
        ui.select(page, "next-status-icon");
        h.await(() -> ui.statusParts().contains("[dot] Hello"), "dot status: " + ui.statusParts());
        ui.select(page, "next-status-icon");
        h.await(() -> ui.statusParts().contains(statusPng), "png status again: " + ui.statusParts());
        assertEquals("Hello", ui.statusLine());
    }

    @Test
    void statusIconIsOwnColoursAndToggles() throws IOException {
        start(Features.SLOT_CAR_MENU, Features.SLOT_STATUS_BAR, Features.UI_PAGES, Features.UI_ICONS);
        h.await(() -> ui.statusParts().contains(statusPng), "own-colour status: " + ui.statusParts());
        assertTrue(ui.carRows().contains(row), "the row ignores the colour: " + ui.carRows());
        ui.select(page, "status-colour");
        h.await(() -> ui.statusParts().contains(statusTinted), "tinted status: " + ui.statusParts());
        ui.select(page, "status-colour");
        h.await(() -> ui.statusParts().contains(statusPng), "own-colour status again: " + ui.statusParts());

        ui.select(page, "status-colour"); // tinted: stored on the way out
        h.await(() -> ui.statusParts().contains(statusTinted), "tinted status: " + ui.statusParts());
        assertEquals("tinted", h.storedValues().getProperty("colour"));
    }

    /** A fresh start with {@code colour=tinted} stored: the first status the plugin publishes is tinted. */
    @Test
    void storedStatusColourIsRestoredOnEnable() throws IOException {
        seedStorage("colour=tinted\n");
        start(Features.SLOT_CAR_MENU, Features.SLOT_STATUS_BAR, Features.UI_PAGES, Features.UI_ICONS);
        h.await(() -> !ui.statusParts().isEmpty() && ui.statusParts().get(ui.statusParts().size() - 1).endsWith("Hello"),
                "a status item: " + ui.statusParts());
        assertTrue(ui.statusParts().contains(statusTinted), "restored tinted: " + ui.statusParts());
        assertFalse(ui.statusParts().contains(statusPng), ui.statusParts().toString());
    }

    @Test
    void manifestDeclaresTheStatusbarPermission() throws IOException {
        assertTrue(manifest().contains("\"ui.slot.statusbar\""), manifest());
    }

    @Test
    void manifestIsApi4Version120() throws IOException {
        String json = manifest();
        assertTrue(json.contains("\"api\":4"), json);
        assertTrue(json.contains("\"version\":\"1.2.0\""), json);
    }

    private static String manifest() throws IOException {
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(System.getProperty("mibsdk.pluginJar"))) {
            java.io.InputStream in = z.getInputStream(z.getEntry("plugin.json"));
            return new String(in.readAllBytes(), "UTF-8");
        }
    }
}
