package mibsdk.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import mibsdk.adapter.fake.FakePlatform;
import mibsdk.api.Features;
import mibsdk.platform.HookTable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DevChannelTest {

    @TempDir
    File tmp;

    private File base;
    private File plugins;
    private File logFile;
    private final FakeClock clock = new FakeClock();
    private SdkRuntime rt;
    private DevChannel dev;

    @BeforeEach
    void setUp() throws IOException {
        base = new File(tmp, "mibsdk");
        plugins = new File(base, "plugins");
        plugins.mkdirs();
        logFile = new File(tmp, "dev.log");
        SerialExecutor.watchdogPeriodMs = 10;
        new PluginJars(new File(tmp, "fixtures")).write(plugins, "hello", "1", null, null,
                "ctx.log().info(\"hi\");", null);
        // The runtime's own dev channel polls a different log file, and effectively never (we drive ours).
        write(new File(base, "sdk.properties"), "plugin.start.delay.ms=0\nheap.low.bytes=0\n"
                + "dev.poll.ms=2147483647\nlog.file=" + new File(tmp, "runtime.log").getPath() + "\n");
        rt = new SdkRuntime(RuntimeConfig.load(FakePlatform.roots(tmp)),
                new FakePlatform("HMI_TEST", new HashSet<String>(Arrays.asList(Features.IGNITION))), clock,
                new HookTable(16), new RingLog(500));
        rt.start();
        assertEquals(1, count("hello", "hi"));
        dev = new DevChannel(rt, plugins, logFile, 2000, clock);
        dev.start();
    }

    @AfterEach
    void tearDown() {
        dev.stop();
        rt.stop();
        SerialExecutor.watchdogPeriodMs = 1000;
    }

    private static void write(File f, String text) throws IOException {
        f.getParentFile().mkdirs();
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } finally {
            out.close();
        }
    }

    private int count(String source, String msg) {
        int n = 0;
        for (String line : rt.log().snapshot()) {
            if (line.endsWith(" " + source + " " + msg)) {
                n++;
            }
        }
        return n;
    }

    private void awaitCount(String source, String msg, int n) throws InterruptedException {
        for (int i = 0; i < 500 && count(source, msg) < n; i++) {
            Thread.sleep(10);
        }
        assertEquals(n, count(source, msg), rt.log().snapshot().toString());
    }

    private String logText() throws IOException {
        return logFile.isFile() ? new String(Files.readAllBytes(logFile.toPath()), StandardCharsets.UTF_8) : "";
    }

    private void awaitLogFileContains(String fragment) throws Exception {
        for (int i = 0; i < 500; i++) {
            if (logText().contains(fragment)) {
                return;
            }
            Thread.sleep(10);
        }
        fail("log file lacks '" + fragment + "':\n" + logText());
    }

    /**
     * /mnt/app (the plugins dir) is read-only while the HMI runs: a marker is never deleted, and each one triggers
     * exactly one reload (its consumption is tracked in memory).
     */
    @Test
    void reloadMarkerTriggersExactlyOneReloadAndIsLeftInPlace() throws Exception {
        File marker = new File(plugins, "hello.reload");
        write(marker, "1\n");

        clock.advance(1999);
        Thread.sleep(300);
        assertEquals(1, count("hello", "hi"), "no poll before pollMs on the Clock");

        clock.advance(1);
        awaitCount("hello", "hi", 2); // enabled again from a fresh snapshot
        assertEquals(1, count("hello", "DISABLED reloading"));
        assertEquals(PluginState.ENABLED, rt.statuses().get(0).state());
        assertTrue(marker.exists(), "the runtime never deletes a marker");

        for (int i = 0; i < 3; i++) { // the same marker is consumed: no second reload
            dev.poll();
        }
        assertEquals(2, count("hello", "hi"));
    }

    @Test
    void rewrittenMarkerTriggersAnotherReload() throws Exception {
        File marker = new File(plugins, "hello.reload");
        write(marker, "push-1\n");
        dev.poll();
        assertEquals(2, count("hello", "hi"));

        write(marker, "push-2\n"); // mibsdk-dev push writes a fresh token each time
        dev.poll();
        assertEquals(3, count("hello", "hi"));
        dev.poll();
        assertEquals(3, count("hello", "hi"));
    }

    @Test
    void markerRemovedThenDroppedAgainTriggersAnotherReload() throws Exception {
        File marker = new File(plugins, "hello.reload");
        write(marker, "x\n");
        long mtime = marker.lastModified();
        dev.poll();
        assertEquals(2, count("hello", "hi"));

        assertTrue(marker.delete()); // mibsdk-dev cleans up while it has the file system read-write
        dev.poll();
        write(marker, "x\n");
        assertTrue(marker.setLastModified(mtime), "identical to the consumed one");
        dev.poll();
        assertEquals(3, count("hello", "hi"), "a marker that went away is forgotten");
    }

    /** A new run loads every plugin from its current jar: markers left from before it are already satisfied. */
    @Test
    void markersPresentAtStartAreTakenAsConsumed() throws Exception {
        write(new File(plugins, "hello.reload"), "old\n");
        DevChannel fresh = new DevChannel(rt, plugins, new File(tmp, "fresh.log"), 2000, clock);
        fresh.start();
        try {
            fresh.poll();
            fresh.poll();
            assertEquals(1, count("hello", "hi"));
        } finally {
            fresh.stop();
        }
    }

    @Test
    void reloadsWithAReadOnlyPluginsDir() throws Exception {
        File marker = new File(plugins, "hello.reload");
        write(marker, "1\n");
        assertTrue(plugins.setWritable(false));
        try {
            dev.poll();
            assertEquals(2, count("hello", "hi"));
            assertEquals(PluginState.ENABLED, rt.statuses().get(0).state());
            for (String line : rt.log().snapshot()) {
                assertFalse(line.contains("cannot delete"), line);
            }
        } finally {
            plugins.setWritable(true);
        }
    }

    @Test
    void logFileRewritten() throws Exception {
        clock.advance(2000);
        awaitLogFileContains(" STATUS mibsdk runtime started adapter=fake safeMode=false");
        assertFalse(logText().contains("after-first-poll"));

        rt.log().append("INFO", "test", "after-first-poll");
        clock.advance(2000);
        awaitLogFileContains(" INFO test after-first-poll");
    }

    @Test
    void logFileRewrittenOnlyWhenTheLogChanged() throws Exception {
        dev.poll();
        assertTrue(logText().contains(" STATUS mibsdk runtime started"), logText());
        write(logFile, "sentinel\n"); // stands in for "the file was not rewritten"
        dev.poll();
        assertEquals("sentinel\n", logText(), "nothing new in the RingLog: no rewrite");

        rt.log().append("INFO", "test", "new-line");
        dev.poll();
        assertTrue(logText().contains(" INFO test new-line"), logText());

        assertTrue(logFile.delete());
        dev.poll();
        assertTrue(logText().contains(" INFO test new-line"), "a deleted file is written again: " + logText());
    }

    @Test
    void badMarkersNeverStopThePolling() throws Exception {
        write(new File(plugins, "Not_An_Id.reload"), "");
        write(new File(plugins, "nobody.reload"), "");
        File dir = new File(plugins, "dir.reload");
        assertTrue(new File(dir, "inner").mkdirs()); // not a file: never a marker

        dev.poll();
        dev.poll();
        assertEquals(1, countContaining("ignored a reload marker whose name is not a plugin id"),
                "each marker is looked at once, not every poll");
        assertEquals(1, countContaining("reload requested for nobody"));
        assertEquals(0, countContaining("reload requested for dir"));

        write(new File(plugins, "hello.reload"), "");
        dev.poll();
        assertEquals(2, count("hello", "hi")); // still polling
        assertEquals(PluginState.ENABLED, rt.statuses().get(0).state());
    }

    private int countContaining(String fragment) {
        int n = 0;
        for (String line : rt.log().snapshot()) {
            if (line.contains(fragment)) {
                n++;
            }
        }
        return n;
    }

    @Test
    void stoppedChannelDoesNothing() throws Exception {
        dev.stop();
        File marker = new File(plugins, "hello.reload");
        assertTrue(marker.createNewFile());
        clock.advance(4000);
        Thread.sleep(300);
        assertTrue(marker.exists());
        assertFalse(logFile.exists());
    }
}
