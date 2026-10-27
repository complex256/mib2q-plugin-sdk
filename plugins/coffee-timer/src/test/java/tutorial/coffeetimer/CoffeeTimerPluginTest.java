package tutorial.coffeetimer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import mibsdk.adapter.fake.FakePlatform;
import mibsdk.adapter.fake.FakeUi;
import mibsdk.test.PluginTestHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoffeeTimerPluginTest {

    private static final String ID = "coffee-timer";

    @TempDir
    File tmp;

    private PluginTestHarness h;
    private FakeUi ui;
    /** A plugin page's key is "<plugin id>/<page id>". */
    private String page;
    private String row;
    private String cup20Sha;

    @AfterEach
    void tearDown() {
        if (h != null) {
            h.close();
        }
    }

    /** Installs the built jar into a temp "unit", starts the real runtime on the fake car and waits for the page. */
    private void start() {
        h = PluginTestHarness.start(tmp);
        ui = h.ui();
        page = h.pageKey("timer");
        row = "[png:" + h.iconSha("cup") + "] Coffee";
        cup20Sha = h.iconSha("cup-20");
        h.await(() -> !ui.render(page).isEmpty(), "the page");
    }

    private void minutePasses() {
        h.advance(CoffeeTimerPlugin.MINUTE_MS);
    }

    // ---- the tests ----

    @Test
    void carRowOpensTheTimerPage() {
        start();
        h.await(() -> ui.carRows().contains(row), "the Coffee row: " + ui.carRows());
        ui.selectRow(ui.carRows().indexOf(row));
        h.await(() -> page.equals(ui.openPageKey()), "the page open");
        assertEquals(Arrays.asList(
                "Start timer: 4 min",
                "[on] Pop up when ready",
                "Brew time: 4 [1..10]",
                "Set a brew time, then Start."), ui.render(page));
    }

    @Test
    void sliderAndToggleAreStored() {
        start();
        ui.turn(page, "minutes", 7);
        h.await(() -> ui.render(page).contains("Start timer: 7 min"), "7 min");
        ui.toggle(page, "toast");
        h.await(() -> ui.render(page).contains("[off] Pop up when ready"), "toggle off");
        Properties stored = h.storedValues();
        assertEquals("7", stored.getProperty("minutes"));
        assertEquals("off", stored.getProperty("toast"));
    }

    @Test
    void storedValuesAreRestored() throws IOException {
        File data = new File(FakePlatform.roots(tmp).persist(), "data/" + ID + ".properties");
        data.getParentFile().mkdirs();
        Files.write(data.toPath(), "minutes=2\ntoast=off\n".getBytes(StandardCharsets.UTF_8));
        start();
        List<String> lines = ui.render(page);
        assertEquals("Start timer: 2 min", lines.get(0));
        assertEquals("[off] Pop up when ready", lines.get(1));
    }

    @Test
    void timerCountsDownOnTheStatusLineAndToasts() {
        start();
        ui.turn(page, "minutes", 2);
        h.await(() -> ui.render(page).contains("Start timer: 2 min"), "2 min");
        assertEquals("", ui.statusLine());
        ui.select(page, "start");
        h.await(() -> "Coffee 2m".equals(ui.statusLine()), "Coffee 2m on the status line");
        minutePasses();
        h.await(() -> "Coffee 1m".equals(ui.statusLine()), "Coffee 1m");
        assertNull(ui.toast());
        minutePasses();
        h.await(() -> "Coffee ready".equals(ui.statusLine()), "Coffee ready");
        h.await(() -> ("[png:" + cup20Sha + "] Coffee is ready").equals(ui.toast()), "the toast: " + ui.toast());
        ui.select(page, "dismiss");
        h.await(() -> "".equals(ui.statusLine()), "the status item hidden");
    }

    @Test
    void noToastWhenTurnedOff() {
        start();
        ui.toggle(page, "toast");
        ui.turn(page, "minutes", 1);
        h.await(() -> ui.render(page).contains("Start timer: 1 min"), "1 min");
        ui.select(page, "start");
        h.await(() -> "Coffee 1m".equals(ui.statusLine()), "Coffee 1m");
        minutePasses();
        h.await(() -> "Coffee ready".equals(ui.statusLine()), "Coffee ready");
        assertNull(ui.toast());
    }

    @Test
    void stopCancelsTheTimer() {
        start();
        ui.select(page, "start");
        h.await(() -> "Coffee 4m".equals(ui.statusLine()), "Coffee 4m");
        ui.select(page, "stop");
        h.await(() -> ui.render(page).contains("Start timer: 4 min"), "back to Start");
        assertEquals("", ui.statusLine());
    }
}
