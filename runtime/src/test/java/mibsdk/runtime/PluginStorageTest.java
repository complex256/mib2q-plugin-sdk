package mibsdk.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    // ---- quota: /mnt/persist is shared with the stock persistence database, data/ must never fill it ----

    private static String chars(int n, char c) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) {
            b.append(c);
        }
        return b.toString();
    }

    @Test
    void putBeyondThePluginCapFailsAndKeepsTheOldValue() {
        File data = new File(dir, "data");
        StorageQuota q = new StorageQuota(data, 1000, 100000);
        PluginStorage s = new PluginStorage(new File(data, "p.properties"), clock, 2000, q);

        s.put("k", chars(500, 'a'));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> s.put("k", chars(1000, 'b')));
        assertTrue(e.getMessage().contains("storage full"), e.getMessage());
        assertEquals(chars(500, 'a'), s.get("k", null), "the refused write changed nothing");
        s.put("k", "small"); // shrinking is always allowed
        s.put("j", chars(500, 'c')); // ... and frees room
        assertEquals("small", s.get("k", null));
    }

    @Test
    void totalCapCountsEveryPluginIncludingFilesOnDisk() throws IOException {
        File data = new File(dir, "data");
        data.mkdirs();
        Files.write(new File(data, "absent.properties").toPath(), new byte[600]); // a plugin not loaded now
        StorageQuota q = new StorageQuota(data, 100000, 2000);
        PluginStorage a = new PluginStorage(new File(data, "a.properties"), clock, 2000, q);
        PluginStorage b = new PluginStorage(new File(data, "b.properties"), clock, 2000, q);

        a.put("k", chars(600, 'a'));
        assertThrows(IllegalStateException.class, () -> b.put("k", chars(900, 'b')));
        b.put("k", chars(500, 'b'));
        a.remove("k");
        b.put("k", chars(900, 'b')); // a's room is free again
        assertEquals(900, b.get("k", "").length());
    }

    /** Review fix: a file that fails to load still takes its size on disk until a write replaces it. */
    @Test
    void corruptFileKeepsItsSizeOnDiskCountedUntilReplaced() throws IOException {
        File data = new File(dir, "data");
        data.mkdirs();
        File corrupt = new File(data, "c.properties");
        Files.write(corrupt.toPath(), ("bad=\\uZZZZ" + chars(1000, 'x')).getBytes("ISO-8859-1")); // a malformed escape: load throws
        StorageQuota q = new StorageQuota(data, 100000, 1500);
        PluginStorage c = new PluginStorage(corrupt, clock, 2000, q);
        assertEquals(null, c.get("bad", null), "reads as empty");
        PluginStorage b = new PluginStorage(new File(data, "b.properties"), clock, 2000, q);

        assertThrows(IllegalStateException.class, () -> b.put("k", chars(600, 'b')),
                "the corrupt file is still on disk: about 1010 bytes counted");
        c.put("k", "v"); // within the space the file on disk already takes: allowed
        b.put("k", chars(300, 'b'));
        c.flushNow(); // the corrupt file is replaced: only the new content counts
        b.put("k", chars(1000, 'b'));
        assertEquals(1000, b.get("k", "").length());
    }

    @Test
    void reloadingAStoreReplacesItsShareInsteadOfAddingToIt() {
        File data = new File(dir, "data");
        StorageQuota q = new StorageQuota(data, 100000, 1500);
        File file = new File(data, "p.properties");
        PluginStorage s = new PluginStorage(file, clock, 2000, q);
        s.put("k", chars(1000, 'a'));
        s.flushNow();
        for (int i = 0; i < 3; i++) { // a plugin reloaded three times
            PluginStorage again = new PluginStorage(file, clock, 2000, q);
            again.put("k", chars(1100, 'a'));
        }
    }

    /** The estimate the quota uses never undercounts what Properties.store writes (escapes, \\uXXXX). */
    @Test
    void estimateIsAnUpperBoundOfTheWrittenFile() {
        File file = new File(dir, "p.properties");
        PluginStorage s = new PluginStorage(file, clock, 2000, new StorageQuota(dir, 1 << 20, 1 << 20));
        s.put("plain", "value");
        s.put(" key with=special:chars#!", "\t\n\r\f\\ value\u00e9\u20ac\u0001 trailing ");
        s.put("\u00fcnicode-key", chars(100, '\u4e2d'));
        s.flushNow();
        assertTrue(file.length() <= s.estimatedBytes(), file.length() + " > " + s.estimatedBytes());
        assertTrue(s.estimatedBytes() < 2 * file.length() + 64, "not absurdly pessimistic: " + s.estimatedBytes());
    }
}
