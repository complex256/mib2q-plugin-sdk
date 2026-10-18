package mibsdk.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginStorageTest {

    @TempDir
    File dir;

    private final FakeClock clock = new FakeClock();

    @Test
    void putIsVisibleImmediatelyAndPersistedAfterFlush() {
        File file = new File(dir, "data/p.properties");
        PluginStorage s = new PluginStorage(file, clock, 2000);

        s.put("k", "v");
        assertEquals("v", s.get("k", "d"));
        assertFalse(file.exists());

        s.flushNow();
        assertTrue(file.exists());
        assertEquals("v", new PluginStorage(file, clock, 2000).get("k", "d"));
        assertFalse(new File(dir, "data/p.properties.tmp").exists());

        s.remove("k");
        assertEquals("d", s.get("k", "d"));
        s.flushNow();
        assertEquals("d", new PluginStorage(file, clock, 2000).get("k", "d"));
    }

    @Test
    void debouncedWriteHappensOnlyOnceDue() {
        File file = new File(dir, "p.properties");
        PluginStorage s = new PluginStorage(file, clock, 2000);

        s.put("k", "v");
        clock.advance(1999);
        s.flushIfDue();
        assertFalse(file.exists());
        clock.advance(1);
        s.flushIfDue();
        assertTrue(file.exists());
    }

    @Test
    void survivesCorruptFile() throws IOException {
        File file = new File(dir, "p.properties");
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(new byte[] {0, (byte) 0xff, '\\', 'u', 'Z', 'Z', 'Z', 'Z', '=', (byte) 0x80, '\n', 0x1b});
        } finally {
            out.close();
        }

        PluginStorage s = new PluginStorage(file, clock, 2000);

        assertEquals("def", s.get("k", "def"));
        s.put("k", "v");
        assertEquals("v", s.get("k", "def"));
        s.flushNow();
        assertEquals("v", new PluginStorage(file, clock, 2000).get("k", "def"));
        assertTrue(Files.size(file.toPath()) > 0);
    }

    @Test
    void missingFileGivesDefaults() {
        PluginStorage s = new PluginStorage(new File(dir, "nope.properties"), clock, 2000);
        assertEquals("d", s.get("k", "d"));
    }

    @Test
    void pluginHoldingStorageMonitorDoesNotBlockSdk() throws Exception {
        File file = new File(dir, "data/p.properties");
        final PluginStorage s = new PluginStorage(file, clock, 2000);
        final java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1);
        Thread hog = new Thread() {
            public void run() {
                synchronized (s) { // what a hostile plugin can do with ctx.storage()
                    held.countDown();
                    try {
                        Thread.sleep(600000);
                    } catch (InterruptedException ignored) {
                        // test over
                    }
                }
            }
        };
        hog.setDaemon(true);
        hog.start();
        assertTrue(held.await(5, java.util.concurrent.TimeUnit.SECONDS));
        try {
            Thread sdk = new Thread() {
                public void run() {
                    s.put("k", "v");
                    s.get("k", null);
                    s.flushNow();
                    s.flushIfDue();
                }
            };
            sdk.setDaemon(true);
            sdk.start();
            sdk.join(5000);
            assertFalse(sdk.isAlive(), "SDK-side storage calls blocked on the plugin-held monitor");
            assertTrue(file.exists());
        } finally {
            hog.interrupt();
        }
    }
}
