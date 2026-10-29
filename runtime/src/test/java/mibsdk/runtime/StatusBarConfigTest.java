package mibsdk.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StatusBarConfigTest {

    @TempDir
    File tmp;

    private static void write(File f, String s) throws IOException {
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(s.getBytes("ISO-8859-1"));
        } finally {
            out.close();
        }
    }

    private static String read(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] b = new byte[(int) f.length()];
            int n = 0;
            while (n < b.length) {
                n += in.read(b, n, b.length - n);
            }
            return new String(b, "ISO-8859-1");
        } finally {
            in.close();
        }
    }

    @Test
    void defaults() {
        StatusBarConfig c = StatusBarConfig.defaults();
        assertTrue(c.enabled());
        assertEquals(StatusBarConfig.DEFAULT_DWELL_MS, c.dwellMs());
        assertEquals(StatusBarConfig.DEFAULT_ANIMATE_MS, c.animateMs());
        assertTrue(c.order().isEmpty());
        assertTrue(c.hidden().isEmpty());
        assertEquals(Integer.MAX_VALUE, c.rank("bike-rack"));
    }

    @Test
    void roundTripKeepsEverySetting() throws Exception {
        File f = new File(tmp, "statusbar.properties");
        StatusBarConfig in = new StatusBarConfig(false, 7000, 250,
                Arrays.asList("daemon-status", "bike-rack"), new HashSet<String>(Arrays.asList("crashy")));
        in.save(f);
        StatusBarConfig out = StatusBarConfig.load(f);
        assertFalse(out.enabled());
        assertEquals(7000, out.dwellMs());
        assertEquals(250, out.animateMs());
        assertEquals(Arrays.asList("daemon-status", "bike-rack"), out.order());
        assertEquals(new HashSet<String>(Arrays.asList("crashy")), out.hidden());
        assertEquals(0, out.rank("daemon-status"));
        assertEquals(1, out.rank("bike-rack"));
        assertEquals(Integer.MAX_VALUE, out.rank("gone"));
        assertEquals("enabled=false\ndwell.ms=7000\nanimate.ms=250\norder=daemon-status,bike-rack\n"
                + "crashy.hidden=true\n", read(f).replaceFirst("^#.*\\n", ""));
        assertFalse(DurableFiles.tmpFile(f).exists(), "written through DurableFiles.replace");
    }

    @Test
    void missingFileIsDefaults() {
        StatusBarConfig c = StatusBarConfig.load(new File(tmp, "nope.properties"));
        assertEquals(StatusBarConfig.DEFAULT_DWELL_MS, c.dwellMs());
        assertTrue(c.enabled());
    }

    @Test
    void invalidValuesAreClampedAndInvalidIdsDropped() throws Exception {
        File f = new File(tmp, "statusbar.properties");
        write(f, "enabled=true\ndwell.ms=1\nanimate.ms=99999\norder=bike-rack,bike-rack,bad id!,daemon-status\n"
                + "bad id!.hidden=true\ncrashy.hidden=false\nhello-menu.hidden=true\n");
        StatusBarConfig c = StatusBarConfig.load(f);
        assertEquals(StatusBarConfig.MIN_DWELL_MS, c.dwellMs());
        assertEquals(StatusBarConfig.MAX_ANIMATE_MS, c.animateMs());
        assertEquals(Arrays.asList("bike-rack", "daemon-status"), c.order());
        assertEquals(new HashSet<String>(Arrays.asList("hello-menu")), c.hidden());
    }

    /** A corrupt file reads as the defaults, and the next save replaces it. */
    @Test
    void corruptFileReadsAsDefaults() throws Exception {
        File f = new File(tmp, "statusbar.properties");
        write(f, "order=tr\\u12zz\n"); // malformed escape: Properties.load throws
        StatusBarConfig c = StatusBarConfig.load(f);
        assertTrue(c.order().isEmpty());
        c.withEnabled(false).save(f);
        assertTrue(read(f).contains("enabled=false\n"));
    }

    /** Died between delete and rename: the complete temp file is read. */
    @Test
    void recoversFromTmpWhenTargetMissing() throws Exception {
        File f = new File(tmp, "statusbar.properties");
        write(DurableFiles.tmpFile(f), "enabled=false\norder=cc,aa\n");
        StatusBarConfig c = StatusBarConfig.load(f);
        assertFalse(c.enabled());
        assertEquals(Arrays.asList("cc", "aa"), c.order());
    }

    @Test
    void hiddenAndOrderAreImmutableUpdates() {
        StatusBarConfig c = new StatusBarConfig(true, 5000, 300, Arrays.asList("aa", "bb"));
        assertEquals(Arrays.asList("aa", "bb"), c.setHidden("cc", true).order(), "hiding keeps the order");
        assertEquals(new HashSet<String>(Arrays.asList("cc")), c.setHidden("cc", true).hidden());
        assertTrue(c.setHidden("bad id!", true).hidden().isEmpty(), "invalid ids are never written");
        assertEquals(c, c.setHidden("cc", false), "nothing changes");
        assertEquals(c, c.withEnabled(true));
        assertEquals(7000, c.withDwell(7000).dwellMs());
        assertEquals(c.hidden(), c.withDwell(7000).hidden(), "a dwell change keeps hidden");
    }

    @Test
    void moveSwapsWithTheNearestEligibleNeighbourAndStopsAtTheEnds() {
        StatusBarConfig c = new StatusBarConfig(true, 5000, 300, Arrays.asList("aa", "bb", "cc"));
        assertEquals(Arrays.asList("aa", "cc", "bb"), c.move("cc", -1, Arrays.asList("aa", "bb", "cc")).order());
        assertEquals(Arrays.asList("bb", "aa", "cc"), c.move("aa", 1, Arrays.asList("aa", "bb", "cc")).order());
        assertEquals(c, c.move("aa", -1, Arrays.asList("aa", "bb", "cc")), "already first");
        assertEquals(c, c.move("zz", 1, Arrays.asList("aa", "bb", "cc")), "not eligible");
        assertEquals(c, c.move("bad id!", 1, Arrays.asList("aa")), "invalid id");

        // "xx" was saved earlier but is not eligible now (no item, not installed): it keeps its place, moves skip it.
        StatusBarConfig q = new StatusBarConfig(true, 5000, 300, Arrays.asList("aa", "xx", "bb"));
        assertEquals(Arrays.asList("bb", "xx", "aa"), q.move("bb", -1, Arrays.asList("aa", "bb")).order(),
                "bb swapped with aa over xx");
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
    void anUnwritableTargetDoesNotThrowFromSave() {
        // The helper reports the failed write as an IOException; the runtime catches it and keeps the in-memory value.
        File blocker = new File(tmp, "not-a-dir");
        try {
            write(blocker, "");
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        boolean threw = false;
        try {
            StatusBarConfig.defaults().save(new File(blocker, "statusbar.properties"));
        } catch (IOException expected) {
            threw = true;
        }
        assertTrue(threw, "save reports the failed write");
    }

    @Test
    void userOrderWinsThenPluginIdOrder() {
        StatusBarConfig c = new StatusBarConfig(true, 5000, 300, Collections.singletonList("daemon-status"));
        assertEquals(Arrays.asList("daemon-status", "alpha", "zeta"),
                StatusLine.order(c.order(), new HashSet<String>(Arrays.asList("zeta", "alpha", "daemon-status"))));
    }
}
