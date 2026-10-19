package mibsdk.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RingLogTest {

    @TempDir
    Path tmp;

    @Test
    void keepsNewestCapacityLines() {
        RingLog log = new RingLog(3);
        for (int i = 1; i <= 5; i++) {
            log.append("INFO", "src", "m" + i);
        }
        List<String> lines = log.snapshot();
        assertEquals(3, lines.size());
        assertEquals("0 INFO src m3", lines.get(0));
        assertEquals("0 INFO src m5", lines.get(2));
    }

    @Test
    void lineUsesClockUptime() {
        FakeClock clock = new FakeClock();
        RingLog log = new RingLog(10);
        log.append("WARN", "a", "before");
        log.setClock(clock);
        clock.advance(1234);
        log.append("WARN", "a", "after");
        List<String> lines = log.snapshot();
        assertEquals("0 WARN a before", lines.get(0));
        assertEquals("1234 WARN a after", lines.get(1));
    }

    @Test
    void newlinesInMessageStayOnOneLine() {
        RingLog log = new RingLog(10);
        log.append("INFO", "s", "a\nb\r\nc");
        assertEquals(1, log.snapshot().size());
        assertFalse(log.snapshot().get(0).contains("\n"));
    }

    @Test
    void sequenceCountsEveryAppendEvenPastCapacity() {
        RingLog log = new RingLog(2);
        assertEquals(0, log.sequence());
        for (int i = 0; i < 5; i++) {
            log.append("INFO", "s", "x");
        }
        assertEquals(5, log.sequence(), "monotonic, not the retained size");
        log.snapshot();
        assertEquals(5, log.sequence(), "reading does not change it");
    }

    @Test
    void snapshotIsACopy() {
        RingLog log = new RingLog(2);
        log.append("INFO", "s", "x");
        List<String> snap = log.snapshot();
        log.append("INFO", "s", "y");
        assertEquals(1, snap.size());
    }

    @Test
    void writeToReplacesFileAtomicallyAndLeavesNoTemp() throws Exception {
        RingLog log = new RingLog(10);
        log.append("INFO", "s", "one");
        log.append("ERROR", "s", "two");
        File f = tmp.resolve("mibsdk.log").toFile();
        Files.write(f.toPath(), "stale".getBytes(StandardCharsets.UTF_8));
        log.writeTo(f);
        assertEquals("0 INFO s one\n0 ERROR s two\n", new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        assertEquals(1, tmp.toFile().list().length);
    }
}
