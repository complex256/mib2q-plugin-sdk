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
    void threeUncleanBootsEnterSafeMode() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertFalse(boot().beginBoot(), "boot " + (i + 1)); // each crashes before healthy, no ignition off
        }
        assertTrue(boot().beginBoot(), "the boot after three unhealthy ones is in safe mode");
        assertEquals("3", props().getProperty("unhealthyBoots"));
        assertEquals("false", props().getProperty("lastShutdownClean"));
        assertTrue(boot().beginBoot(), "safe mode stays until a healthy boot or a reset");
    }

    @Test
    void healthyResetsCounter() throws Exception {
        assertFalse(boot().beginBoot());
        assertFalse(boot().beginBoot());
        BootGuard g = boot();
        assertFalse(g.beginBoot());
        assertEquals("2", props().getProperty("unhealthyBoots"));
        g.markHealthy(); // ran healthyAfterMs with plugins enabled, then crashed
        assertEquals("0", props().getProperty("unhealthyBoots"));

        assertFalse(boot().beginBoot()); // counts the crash of the healthy boot (it never shut down clean): 1
        assertFalse(boot().beginBoot()); // 2
        assertTrue(boot().beginBoot(), "three in a row since the healthy boot"); // 3
    }

    /** Review Focus 3: three drives under 60 s each (ignition off before healthy) must not trip safe mode. */
    @Test
    void ignitionOffCountsAsCleanShutdown() throws Exception {
        for (int i = 0; i < 3; i++) {
            BootGuard g = boot();
            assertFalse(g.beginBoot(), "boot " + (i + 1));
            g.markCleanShutdown(); // IgnitionChanged(false) before healthyAfterMs
            assertEquals("true", props().getProperty("lastShutdownClean"));
        }
        assertFalse(boot().beginBoot(), "the fourth boot is normal");
        assertEquals("0", props().getProperty("unhealthyBoots"));
    }

    @Test
    void cleanShutdownDoesNotResetEarlierUnhealthyBoots() throws Exception {
        assertFalse(boot().beginBoot());
        assertFalse(boot().beginBoot()); // previous crashed: 1
        BootGuard g = boot();
        assertFalse(g.beginBoot()); // 2
        g.markCleanShutdown();
        assertFalse(boot().beginBoot()); // clean: still 2
        assertEquals("2", props().getProperty("unhealthyBoots"));
        assertTrue(boot().beginBoot(), "the previous boot crashed: 3");
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
        assertEquals("false", props().getProperty("lastShutdownClean"));
    }

    @Test
    void stateFileFormatIsExact() throws Exception {
        BootGuard g = boot();
        g.beginBoot();
        g.markCleanShutdown();
        assertEquals("unhealthyBoots=0\nlastShutdownClean=true\n", text(state));
        assertFalse(new File(tmp, "boot.state.tmp").exists());
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
}
