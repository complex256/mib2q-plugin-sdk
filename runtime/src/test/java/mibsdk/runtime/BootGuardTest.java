package mibsdk.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BootGuardTest {

    @TempDir
    File tmp;

    private File state;
    private File marker;

    @BeforeEach
    void setUp() {
        state = new File(tmp, "boot.state");
        marker = new File(tmp, "SAFE_MODE");
    }

    /** A new guard per boot, as each HMI start constructs a fresh runtime. */
    private BootGuard boot() {
        return new BootGuard(state, marker, 3);
    }

    private Properties props() throws IOException {
        Properties p = new Properties();
        FileInputStream in = new FileInputStream(state);
        try {
            p.load(in);
        } finally {
            in.close();
        }
        return p;
    }

    @Test
    void pluginsLoadingClearsTheWrapCountInTheSameWriteWhenAsked() throws Exception {
        BootGuard.countWrapBoot(state, null);
        BootGuard g = boot();
        assertFalse(g.beginBoot());
        g.markPluginsLoading(false);
        assertEquals("unhealthyBoots=0\nlastShutdownClean=false\nwrapBoots=1\n", text());

        write("unhealthyBoots=0\nlastShutdownClean=true\nwrapBoots=1\n");
        BootGuard h = boot();
        assertFalse(h.beginBoot());
        h.markPluginsLoading(true);
        assertEquals("unhealthyBoots=0\nlastShutdownClean=false\nwrapBoots=0\n", text());
    }

    private String text() throws IOException {
        return new String(java.nio.file.Files.readAllBytes(state.toPath()), "ISO-8859-1");
    }

    private void write(String s) throws IOException {
        java.nio.file.Files.write(state.toPath(), s.getBytes("ISO-8859-1"));
    }

    /** A boot that loads plugins and then dies without ignition-off or health: returns beginBoot's verdict. */
    private boolean crashingBoot() {
        BootGuard g = boot();
        boolean safe = g.beginBoot();
        if (!safe) {
            g.markPluginsLoading(false);
        }
        return safe;
    }

    @Test
    void threeUncleanBootsEnterSafeMode() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertFalse(crashingBoot(), "boot " + (i + 1)); // each crashes after loading, before healthy
        }
        assertTrue(boot().beginBoot(), "the boot after three unhealthy ones is in safe mode");
        assertEquals("3", props().getProperty("unhealthyBoots"));
        assertTrue(boot().beginBoot(), "safe mode stays until a reset (it loads no plugins)");
        assertEquals("3", props().getProperty("unhealthyBoots"), "safe-mode boots do not count");
    }

    @Test
    void healthyResetsCounter() throws Exception {
        assertFalse(crashingBoot());
        assertFalse(crashingBoot());
        BootGuard g = boot();
        assertFalse(g.beginBoot());
        g.markPluginsLoading(false);
        assertEquals("2", props().getProperty("unhealthyBoots"));
        g.markHealthy(); // ran healthyAfterMs with plugins enabled, then crashed
        assertEquals("0", props().getProperty("unhealthyBoots"));

        assertFalse(crashingBoot()); // counts the crash of the healthy boot (it never shut down clean): 1
        assertFalse(crashingBoot()); // 2
        assertTrue(boot().beginBoot(), "three in a row since the healthy boot"); // 3
    }

    /** Review Focus 3: three drives under 60 s each (ignition off before healthy) must not trip safe mode. */
    @Test
    void ignitionOffCountsAsCleanShutdown() throws Exception {
        for (int i = 0; i < 3; i++) {
            BootGuard g = boot();
            assertFalse(g.beginBoot(), "boot " + (i + 1));
            g.markPluginsLoading(false);
            assertEquals("false", props().getProperty("lastShutdownClean"));
            g.markCleanShutdown(); // IgnitionChanged(false) before healthyAfterMs
            assertEquals("true", props().getProperty("lastShutdownClean"));
        }
        assertFalse(boot().beginBoot(), "the fourth boot is normal");
        assertEquals("0", props().getProperty("unhealthyBoots"));
    }

    @Test
    void ignitionOffBeforePluginsLoadStillCountsAsClean() throws Exception {
        for (int i = 0; i < 3; i++) {
            BootGuard g = boot();
            assertFalse(g.beginBoot());
            g.markCleanShutdown(); // during the plugin-start delay
            g.markPluginsLoading(false); // the delay ends before power-off
            assertEquals("true", props().getProperty("lastShutdownClean"));
        }
        assertFalse(boot().beginBoot());
        assertEquals("0", props().getProperty("unhealthyBoots"));
    }

    @Test
    void bootsThatNeverLoadPluginsDoNotCount() throws Exception {
        for (int i = 0; i < 5; i++) { // restarts during the start delay, adapter failures, safe-mode boots
            assertFalse(boot().beginBoot(), "boot " + (i + 1));
            assertEquals("true", props().getProperty("lastShutdownClean"));
        }
        assertEquals("0", props().getProperty("unhealthyBoots"));
    }

    @Test
    void cleanShutdownDoesNotResetEarlierUnhealthyBoots() throws Exception {
        assertFalse(crashingBoot());
        BootGuard g = boot();
        assertFalse(g.beginBoot()); // previous crashed: 1
        g.markPluginsLoading(false);
        g.markCleanShutdown();
        assertFalse(crashingBoot()); // clean: still 1
        assertEquals("1", props().getProperty("unhealthyBoots"));
        assertFalse(crashingBoot()); // 2
        assertTrue(boot().beginBoot(), "the previous boot crashed: 3");
    }

    @Test
    void resetLeavesSafeMode() throws Exception {
        for (int i = 0; i < 3; i++) {
            crashingBoot();
        }
        assertTrue(boot().beginBoot());
        boot().reset();
        assertEquals("unhealthyBoots=0\nlastShutdownClean=true\nwrapBoots=0\n", text(state));
        assertFalse(new File(tmp, "boot.state.tmp").exists());
        assertFalse(BootGuard.wouldEnterSafeMode(state, marker, 3));
        assertFalse(boot().beginBoot());
    }

    @Test
    void wouldEnterSafeModePredictsBeginBootWithoutWriting() throws Exception {
        assertFalse(BootGuard.wouldEnterSafeMode(state, marker, 3), "fresh unit");
        assertFalse(state.exists(), "read-only");
        crashingBoot();
        crashingBoot();
        crashingBoot(); // count 2 on disk, the last boot unclean: the next beginBoot makes it 3
        assertEquals("2", props().getProperty("unhealthyBoots"));
        String before = text(state);
        assertTrue(BootGuard.wouldEnterSafeMode(state, marker, 3));
        assertEquals(before, text(state));
        assertTrue(boot().beginBoot());

        boot().reset();
        assertTrue(marker.createNewFile());
        assertTrue(BootGuard.wouldEnterSafeMode(state, marker, 3), "marker");
    }

    @Test
    void safeModeMarkerForces() throws Exception {
        assertTrue(marker.createNewFile());
        assertTrue(boot().beginBoot(), "marker forces safe mode on a fresh unit");
        assertTrue(marker.delete());
        assertFalse(boot().beginBoot(), "without the marker the counter decides");
    }

    @Test
    void corruptOrPartialStateFileIsTreatedAsFresh() throws Exception {
        FileOutputStream out = new FileOutputStream(state);
        out.write("unhealthyBoots=lots\n\u0000garbage".getBytes("ISO-8859-1"));
        out.close();
        assertFalse(boot().beginBoot());
        assertEquals("0", props().getProperty("unhealthyBoots"));
        assertEquals("true", props().getProperty("lastShutdownClean"));
    }

    @Test
    void stateFileFormatIsExact() throws Exception {
        BootGuard g = boot();
        g.beginBoot();
        g.markPluginsLoading(false);
        assertEquals("unhealthyBoots=0\nlastShutdownClean=false\nwrapBoots=0\n", text(state));
        g.markCleanShutdown();
        assertEquals("unhealthyBoots=0\nlastShutdownClean=true\nwrapBoots=0\n", text(state));
        assertFalse(new File(tmp, "boot.state.tmp").exists());
    }

    // ---- wrapBoots: boots the configurator wrapped, counted before the runtime can see anything ----

    /** What the configurator does at init: true means it wraps this boot (and counted it). */
    private boolean configuratorBoot() {
        if (BootGuard.wrapLoopDetected(state, 3)) {
            return false;
        }
        BootGuard.countWrapBoot(state, null);
        return true;
    }

    @Test
    void crashesBeforePluginsLoadEndWrappingAfterThreshold() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertTrue(configuratorBoot(), "boot " + (i + 1) + " wraps");
            if (i == 1) {
                assertFalse(boot().beginBoot(), "a runtime that starts and dies keeps the count"); // no plugins yet
            }
            // the HMI dies here: a proxy, trace or bridge crash, before the runtime marks anything
        }
        assertEquals("3", props().getProperty("wrapBoots"));
        assertEquals("0", props().getProperty("unhealthyBoots"), "the plugin counter never saw these boots");
        assertTrue(BootGuard.wrapLoopDetected(state, 3));
        assertFalse(configuratorBoot(), "the next boot passes everything through");
        assertEquals("3", props().getProperty("wrapBoots"), "a pass-through boot is not counted");
    }

    @Test
    void healthyRunClearsWrapBoots() throws Exception {
        configuratorBoot();
        configuratorBoot();
        BootGuard g = boot();
        assertFalse(g.beginBoot());
        assertEquals("2", props().getProperty("wrapBoots"), "beginBoot keeps the configurator's count");
        g.markHmiHealthy();
        assertEquals("0", props().getProperty("wrapBoots"));
        assertEquals(0, g.wrapBoots());
    }

    @Test
    void cleanShutdownClearsWrapBoots() throws Exception {
        configuratorBoot();
        configuratorBoot();
        BootGuard g = boot();
        assertFalse(g.beginBoot());
        g.markCleanShutdown();
        assertEquals("0", props().getProperty("wrapBoots"));
    }

    @Test
    void wrapLoopLatchesUntilReset() throws Exception {
        for (int i = 0; i < 3; i++) {
            configuratorBoot();
        }
        BootGuard g = boot();
        assertFalse(g.beginBoot(), "the runtime itself is not in safe mode");
        assertTrue(g.wrapLoopLatched());
        g.markHmiHealthy(); // a pass-through boot is healthy: that proves nothing about wrapping
        g.markCleanShutdown();
        assertEquals("3", props().getProperty("wrapBoots"));
        assertTrue(BootGuard.wrapLoopDetected(state, 3));

        boot().reset();
        assertEquals("0", props().getProperty("wrapBoots"));
        assertFalse(BootGuard.wrapLoopDetected(state, 3));
        assertTrue(configuratorBoot());
    }

    @Test
    void wrapBootsSurvivesTheRuntimeWrites() throws Exception {
        configuratorBoot();
        BootGuard g = boot();
        g.beginBoot();
        g.markPluginsLoading(false);
        g.markHealthy();
        assertEquals("1", props().getProperty("wrapBoots"), "only markHmiHealthy/markCleanShutdown/reset clear it");
    }

    @Test
    void oldTwoLineFileAndBadWrapBootsAreTolerated() throws Exception {
        FileOutputStream out = new FileOutputStream(state);
        out.write("unhealthyBoots=1\nlastShutdownClean=true\n".getBytes("ISO-8859-1"));
        out.close();
        assertFalse(BootGuard.wrapLoopDetected(state, 3));
        assertTrue(configuratorBoot());
        assertEquals("unhealthyBoots=1\nlastShutdownClean=true\nwrapBoots=1\n", text(state));

        out = new FileOutputStream(state);
        out.write("unhealthyBoots=1\nlastShutdownClean=true\nwrapBoots=x\n".getBytes("ISO-8859-1"));
        out.close();
        assertFalse(BootGuard.wrapLoopDetected(state, 3), "a bad value reads as 0");
        assertFalse(boot().beginBoot());
        assertEquals("1", props().getProperty("unhealthyBoots"));
    }

    private static String text(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] b = new byte[(int) f.length()];
            int n = 0;
            while (n < b.length) {
                int r = in.read(b, n, b.length - n);
                if (r < 0) {
                    break;
                }
                n += r;
            }
            return new String(b, 0, n, "ISO-8859-1");
        } finally {
            in.close();
        }
    }

    /** B1: boot.state on a read-only file system; the guard must report itself inert, not pretend to count. */
    @Test
    void inertWhileTheLastWriteFailed() throws IOException {
        File dir = new File(tmp, "ro");
        FileOutputStream f = new FileOutputStream(dir); // a file where the directory should be
        f.close();
        state = new File(dir, "boot.state");
        BootGuard g = boot();
        assertFalse(g.inert(), "nothing written yet");
        assertFalse(g.beginBoot());
        assertTrue(g.inert());

        assertTrue(dir.delete());
        g.markPluginsLoading(false);
        assertFalse(g.inert(), "writable again");
        assertEquals("false", props().getProperty("lastShutdownClean"));
    }

    /** Review fix: the configurator wraps only if it could record the wrap boot (fail safe). */
    @Test
    void countWrapBootSaysWhetherItRecordedTheBoot() throws IOException {
        assertTrue(BootGuard.countWrapBoot(state, null));
        assertEquals("1", props().getProperty("wrapBoots"));

        File dir = new File(tmp, "ro2");
        FileOutputStream f = new FileOutputStream(dir); // a file where the directory should be
        f.close();
        assertFalse(BootGuard.countWrapBoot(new File(dir, "boot.state"), null));
    }
}
