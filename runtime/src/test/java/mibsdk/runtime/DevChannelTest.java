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
        rt = new SdkRuntime(RuntimeConfig.load(base),
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

    private static void awaitGone(File f) throws InterruptedException {
        for (int i = 0; i < 500 && f.exists(); i++) {
            Thread.sleep(10);
        }
        assertFalse(f.exists(), f + " still exists");
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

    @Test
    void reloadMarkerTriggersReloadAndIsDeleted() throws Exception {
        File marker = new File(plugins, "hello.reload");
        assertTrue(marker.createNewFile());

        clock.advance(1999);
        Thread.sleep(300);
        assertTrue(marker.exists(), "no poll before pollMs on the Clock");
        assertEquals(1, count("hello", "hi"));

        clock.advance(1);
        awaitGone(marker);
        awaitCount("hello", "hi", 2); // enabled again from a fresh snapshot
        assertEquals(1, count("hello", "DISABLED reloading"));
        assertEquals(PluginState.ENABLED, rt.statuses().get(0).state());

        clock.advance(2000); // the marker is gone: no second reload
        Thread.sleep(300);
        assertEquals(2, count("hello", "hi"));
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
        File invalid = new File(plugins, "Not_An_Id.reload");
        assertTrue(invalid.createNewFile());
        File unknown = new File(plugins, "nobody.reload");
        assertTrue(unknown.createNewFile());
        File stuck = new File(plugins, "hello.reload");
        File inner = new File(stuck, "inner");
        assertTrue(inner.mkdirs()); // a non-empty directory: the marker cannot be deleted

        clock.advance(2000);
        awaitGone(invalid);
        awaitGone(unknown);
        clock.advance(2000);
        Thread.sleep(300);
        assertTrue(stuck.exists());
        assertEquals(1, count("hello", "hi"), "an undeletable marker never reloads (no reload every poll)");

        assertTrue(inner.delete() && stuck.delete());
        assertTrue(stuck.createNewFile());
        clock.advance(2000);
        awaitGone(stuck);
        awaitCount("hello", "hi", 2); // still polling
        assertEquals(PluginState.ENABLED, rt.statuses().get(0).state());
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
