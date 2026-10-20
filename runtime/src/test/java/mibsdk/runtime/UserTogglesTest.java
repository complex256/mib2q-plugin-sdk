package mibsdk.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UserTogglesTest {

    @TempDir
    File tmp;

    private static void write(File f, byte[] b) throws IOException {
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(b);
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
    void persistsAcrossInstancesAndSurvivesCorruptFile() throws Exception {
        File f = new File(tmp, "persist/plugins.properties");
        RingLog log = new RingLog(50);
        UserToggles t = new UserToggles(f, log);
        assertFalse(t.isOff("rack"), "no file: every plugin is on");
        assertTrue(t.setOff("rack", true));
        assertTrue(t.setOff("mqtt", true));
        assertTrue(t.setOff("mqtt", false));
        assertEquals("rack.off=true\n", read(f));
        assertFalse(DurableFiles.tmpFile(f).exists(), "written through DurableFiles.replace");

        UserToggles again = new UserToggles(f, log);
        assertTrue(again.isOff("rack"));
        assertFalse(again.isOff("mqtt"));
        assertFalse(again.isOff("gone"));

        write(f, "rack.off=tr\\u12zz\n".getBytes("ISO-8859-1")); // malformed escape: Properties.load throws
        UserToggles corrupt = new UserToggles(f, log);
        assertFalse(corrupt.isOff("rack"), "a corrupt file reads as empty");
        assertTrue(corrupt.setOff("tail", true), "and is replaced on the next write");
        assertEquals("tail.off=true\n", read(f));

        write(f, "rack.off=false\nbad id!.off=true\nx=y\nok.off=true\n".getBytes("ISO-8859-1"));
        UserToggles mixed = new UserToggles(f, log);
        assertFalse(mixed.isOff("rack"));
        assertTrue(mixed.isOff("ok"));
        assertFalse(mixed.setOff("bad id!", true), "invalid ids are never written");
    }

    @Test
    void recoversFromTmpWhenTargetMissing() throws Exception {
        File f = new File(tmp, "plugins.properties");
        write(DurableFiles.tmpFile(f), "rack.off=true\n".getBytes("ISO-8859-1")); // died between delete and rename
        assertFalse(f.exists());
        UserToggles t = new UserToggles(f, new RingLog(50));
        assertTrue(t.isOff("rack"));
        assertTrue(t.setOff("tail", true));
        assertEquals("rack.off=true\ntail.off=true\n", read(f));
    }

    @Test
    void unwritableFileKeepsTheChoiceForThisRun() throws Exception {
        File blocker = new File(tmp, "not-a-dir");
        write(blocker, new byte[0]);
        RingLog log = new RingLog(50);
        UserToggles t = new UserToggles(new File(blocker, "plugins.properties"), log);
        assertFalse(t.setOff("rack", true));
        assertTrue(t.isOff("rack"));
        boolean logged = false;
        for (String line : log.snapshot()) {
            logged |= line.indexOf("cannot write plugins.properties") >= 0;
        }
        assertTrue(logged, log.snapshot().toString());
    }
}
