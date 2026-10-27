package mibsdk.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.List;
import java.util.Properties;
import mibsdk.adapter.fake.FakePlatform;
import mibsdk.api.Features;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs the harness against the real hello-menu jar (the build passes -Dmibsdk.pluginJar and -Dmibsdk.pluginIcons). */
class PluginTestHarnessTest {

    private static final String PAGE = "hello-menu/fruits";

    @TempDir
    File tmp;

    private PluginTestHarness h;

    @AfterEach
    void tearDown() {
        if (h != null) {
            h.close();
        }
    }

    @Test
    void startsTheBuiltJarEnabled() {
        h = PluginTestHarness.start(tmp);
        assertEquals("ENABLED", h.state());
        assertEquals("hello-menu", h.pluginId());
        assertEquals(PAGE, h.pageKey("fruits"));
        assertFalse(h.ui().carRows().isEmpty(), h.ui().carRows().toString());
    }

    @Test
    void installsIconsUnderTheirSha16() throws Exception {
        h = PluginTestHarness.start(tmp);
        File dir = new File(FakePlatform.roots(tmp).install(), "icons/hello-menu");
        Properties index = new Properties();
        try (InputStream in = new FileInputStream(new File(dir, "index.properties"))) {
            index.load(in);
        }
        String sha = sha16(new File(System.getProperty("mibsdk.pluginIcons"), "hello.png"));
        assertEquals(sha + ",72,72", index.getProperty("hello"));
        assertTrue(new File(dir, sha + ".png").isFile());
        assertEquals(sha, h.iconSha("hello"));
    }

    @Test
    void withoutAGrantThePluginIsBlocked() {
        h = PluginTestHarness.builder(tmp).grants().startExpectingFailure();
        assertTrue(h.state().startsWith("BLOCKED"), h.state());
        assertTrue(h.ui().render(PAGE).isEmpty());
    }

    @Test
    void featuresDefaultToAllAndCanBeNarrowed() {
        h = PluginTestHarness.builder(tmp).features().start();
        assertEquals("ENABLED", h.state());
        assertTrue(h.ui().render(PAGE).isEmpty(), "no ui.pages feature: the plugin shows nothing");
    }

    @Test
    void awaitFailsWithTheRenderAndTheLog() {
        h = PluginTestHarness.start(tmp);
        AssertionError e = assertThrows(AssertionError.class, () -> h.await(() -> false, "the impossible", 50));
        assertTrue(e.getMessage().contains("never the impossible"), e.getMessage());
        assertTrue(e.getMessage().contains("Selected: <none>"), e.getMessage());
    }

    @Test
    void openPageFollowsThePluginsMenuLink() {
        h = PluginTestHarness.builder(tmp).features(Features.UI_PAGES).start(); // no Car row: only the Plugins menu leads there
        h.openPage("fruits");
        assertEquals(PAGE, h.ui().openPageKey());
    }

    @Test
    void restartKeepsStorage() {
        h = PluginTestHarness.start(tmp);
        h.await(() -> !h.ui().render(PAGE).isEmpty(), "the page");
        h.ui().select(PAGE, "pear");
        h.await(() -> h.ui().render(PAGE).contains("Selected: Pear"), "Selected: Pear");
        h.restart();
        assertEquals("ENABLED", h.state());
        h.await(() -> h.ui().render(PAGE).contains("Selected: Pear"), "Selected: Pear after the restart");
    }

    @Test
    void storedValuesStopsTheRuntimeAndReadsStorage() {
        h = PluginTestHarness.start(tmp);
        h.await(() -> !h.ui().render(PAGE).isEmpty(), "the page");
        h.ui().select(PAGE, "plum");
        h.await(() -> h.ui().render(PAGE).contains("Selected: Plum"), "Selected: Plum");
        assertEquals("plum", h.storedValues().getProperty("last"));
    }

    /** hello-menu has no timer of its own: the log's timestamps come from the clock the Scheduler reads. */
    @Test
    void advanceMovesTheClock() {
        h = PluginTestHarness.start(tmp);
        long before = lastLogUptime();
        h.advance(60_000);
        h.restart(); // a fresh log line stamped with the advanced clock
        assertTrue(lastLogUptime() - before >= 60_000, before + " -> " + lastLogUptime());
    }

    @Test
    void aJarThatDoesNotExistIsReported() {
        File missing = new File(tmp, "nope.jar");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> PluginTestHarness.builder(tmp).jar(missing).start());
        assertTrue(e.getMessage().contains("nope.jar"), e.getMessage());
    }

    @Test
    void withoutAJarPropertyOrBuilderJarTheMessageNamesTheProperty() {
        String old = System.getProperty("mibsdk.pluginJar");
        System.clearProperty("mibsdk.pluginJar");
        try {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> PluginTestHarness.start(tmp));
            assertTrue(e.getMessage().contains("mibsdk.pluginJar"), e.getMessage());
        } finally {
            if (old != null) {
                System.setProperty("mibsdk.pluginJar", old);
            }
        }
    }

    @Test
    void storedValuesSeedTheStorageBeforeTheFirstStart() {
        Properties seed = new Properties();
        seed.setProperty("last", "plum");
        h = PluginTestHarness.builder(tmp).storedValues(seed).start();
        h.await(() -> h.ui().render(PAGE).contains("Selected: Plum"), "Selected: Plum");
    }

    @Test
    void startThrowsWhenThePluginDoesNotEnable() {
        int live = PluginTestHarness.liveRuntimes();
        AssertionError e = assertThrows(AssertionError.class, () -> PluginTestHarness.builder(tmp).grants().start());
        assertTrue(e.getMessage().contains("BLOCKED_PERMISSIONS"), e.getMessage());
        assertEquals(live, PluginTestHarness.liveRuntimes(), "the failed start stopped its runtime");
    }

    @Test
    void startExpectingFailureThrowsWhenThePluginEnables() {
        int live = PluginTestHarness.liveRuntimes();
        AssertionError e = assertThrows(AssertionError.class,
                () -> PluginTestHarness.builder(tmp).startExpectingFailure());
        assertTrue(e.getMessage().contains("expected to fail"), e.getMessage());
        assertEquals(live, PluginTestHarness.liveRuntimes(), "the failed start stopped its runtime");
    }

    @Test
    void aStartedHarnessCountsAsLiveUntilClosed() {
        int live = PluginTestHarness.liveRuntimes();
        h = PluginTestHarness.start(tmp);
        assertEquals(live + 1, PluginTestHarness.liveRuntimes());
        h.close();
        h.close(); // idempotent
        assertEquals(live, PluginTestHarness.liveRuntimes());
    }

    private long lastLogUptime() {
        List<String> lines = java.util.Arrays.asList(h.log().split("\n"));
        String last = lines.get(lines.size() - 1);
        return Long.parseLong(last.substring(0, last.indexOf(' ')));
    }

    private static String sha16(File f) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f.toPath()));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            sb.append(String.format("%02x", d[i] & 0xff));
        }
        return sb.toString();
    }
}
