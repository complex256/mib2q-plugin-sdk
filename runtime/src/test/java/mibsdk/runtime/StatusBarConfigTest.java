package mibsdk.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StatusBarConfigTest {

    @TempDir
    File tmp;

    @Test
    void defaults() {
        StatusBarConfig c = StatusBarConfig.defaults();
        assertTrue(c.enabled());
        assertEquals(StatusBarConfig.DEFAULT_DWELL_MS, c.dwellMs());
        assertEquals(StatusBarConfig.DEFAULT_ANIMATE_MS, c.animateMs());
        assertTrue(c.order().isEmpty());
        assertEquals(Integer.MAX_VALUE, c.rank("bike-rack/rack"));
    }

    @Test
    void roundTripKeepsOrderAndValues() throws Exception {
        File f = new File(tmp, "statusbar.properties");
        StatusBarConfig in = new StatusBarConfig(false, 7000, 250,
                Arrays.asList("daemon-status/mq", "bike-rack/rack"));
        in.save(f);
        StatusBarConfig out = StatusBarConfig.load(f);
        assertFalse(out.enabled());
        assertEquals(7000, out.dwellMs());
        assertEquals(250, out.animateMs());
        assertEquals(Arrays.asList("daemon-status/mq", "bike-rack/rack"), out.order());
        assertEquals(0, out.rank("daemon-status/mq"));
        assertEquals(1, out.rank("bike-rack/rack"));
    }

    @Test
    void dwellAndAnimationRowsCycleThroughPresets() {
        assertEquals(10000, StatusBarConfig.nextDwell(5000));
        assertEquals(2000, StatusBarConfig.nextDwell(30000), "the last preset wraps");
        assertEquals(2000, StatusBarConfig.nextDwell(0), "an unlisted value goes to the first preset above it");
        assertEquals(150, StatusBarConfig.nextAnimate(0));
        assertEquals(0, StatusBarConfig.nextAnimate(600), "the last preset wraps");
        assertEquals("5 s", StatusBarConfig.dwellLabel(5000));
        assertEquals("500 ms", StatusBarConfig.dwellLabel(500));
        assertEquals("off", StatusBarConfig.animateLabel(0));
        assertEquals("300 ms", StatusBarConfig.animateLabel(300));
    }

    @Test
    void missingFileIsDefaults() {        StatusBarConfig c = StatusBarConfig.load(new File(tmp, "nope.properties"));
        assertEquals(StatusBarConfig.DEFAULT_DWELL_MS, c.dwellMs());
    }

    @Test
    void invalidValuesAreClampedNotFatal() throws Exception {
        File f = new File(tmp, "statusbar.properties");
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(("enabled=true\ndwell.ms=1\nanimate.ms=99999\norder=a/b, a/b ,,c/d\n").getBytes("UTF-8"));
        }
        StatusBarConfig c = StatusBarConfig.load(f);
        assertEquals(StatusBarConfig.MIN_DWELL_MS, c.dwellMs());
        assertEquals(StatusBarConfig.MAX_ANIMATE_MS, c.animateMs());
        assertEquals(Arrays.asList("a/b", "c/d"), c.order());
    }

    @Test
    void userOrderWinsThenPriorityThenRegistration() {
        List<StatusBarItems.Item> items = new ArrayList<StatusBarItems.Item>();
        items.add(new StatusBarItems.Item("a/low", 1, 1));
        items.add(new StatusBarItems.Item("b/high", 100, 2));
        items.add(new StatusBarItems.Item("c/placed", 0, 3));
        items.add(new StatusBarItems.Item("d/high", 100, 4));
        StatusBarConfig c = new StatusBarConfig(true, 5000, 300, Collections.singletonList("c/placed"));
        List<StatusBarItems.Item> out = StatusBarItems.order(items, c);
        assertEquals(Arrays.asList("c/placed", "b/high", "d/high", "a/low"), keys(out));
    }

    @Test
    void rejectsMalformedItemKeys() {
        try {
            new StatusBarItems.Item("no-slash", 0, 0);
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    private static List<String> keys(List<StatusBarItems.Item> items) {
        List<String> out = new ArrayList<String>();
        for (StatusBarItems.Item i : items) {
            out.add(i.key());
        }
        return out;
    }
}
