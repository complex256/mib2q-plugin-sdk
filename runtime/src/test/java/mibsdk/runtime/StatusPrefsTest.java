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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StatusPrefsTest {

    @TempDir
    File tmp;

    private static final List<String> ABC = Arrays.asList("aa", "bb", "cc");

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
    void hiddenAndOrderPersistAcrossInstances() throws Exception {
        File f = new File(tmp, "persist/statusline.properties");
        RingLog log = new RingLog(50);
        StatusPrefs p = new StatusPrefs(f, log);
        assertTrue(p.order().isEmpty(), "no file: no saved order");
        assertFalse(p.isHidden("bb"));
        assertTrue(p.setHidden("bb", true));
        assertTrue(p.setHidden("cc", true));
        assertTrue(p.setHidden("cc", false));
        assertTrue(p.move("cc", -1, ABC)); // a b c -> a c b
        assertEquals(Arrays.asList("aa", "cc", "bb"), p.order());
        assertEquals("order=aa,cc,bb\nbb.hidden=true\n", read(f));
        assertFalse(DurableFiles.tmpFile(f).exists(), "written through DurableFiles.replace");

        StatusPrefs again = new StatusPrefs(f, log);
        assertEquals(Arrays.asList("aa", "cc", "bb"), again.order());
        assertEquals(new HashSet<String>(Arrays.asList("bb")), again.hiddenIds());
        assertFalse(again.isHidden("cc"));
    }

    @Test
    void moveSwapsWithTheNearestEligibleNeighbourAndStopsAtTheEnds() throws Exception {
        StatusPrefs p = new StatusPrefs(new File(tmp, "statusline.properties"), new RingLog(50));
        assertTrue(p.move("aa", -1, ABC), "already first: nothing to do");
        assertTrue(p.order().isEmpty(), "and nothing written");
        assertTrue(p.move("aa", 1, ABC));
        assertEquals(Arrays.asList("bb", "aa", "cc"), p.order());
        assertTrue(p.move("aa", 1, ABC));
        assertTrue(p.move("aa", 1, ABC), "already last");
        assertEquals(Arrays.asList("bb", "cc", "aa"), p.order());

        // "xx" was saved earlier but is not eligible now (no item, not installed): it keeps its place, moves skip it
        StatusPrefs q = new StatusPrefs(new File(tmp, "q.properties"), new RingLog(50));
        assertTrue(q.move("xx", -1, Arrays.asList("xx", "aa", "bb"))); // aa bb xx -> aa xx bb
        assertTrue(q.move("bb", -1, Arrays.asList("aa", "bb")));
        assertEquals(Arrays.asList("bb", "xx", "aa"), q.order(), "bb swapped with aa over xx");
        assertFalse(q.move("bad id!", 1, Arrays.asList("bad id!")), "invalid ids are never written");
        assertTrue(q.move("zzz", 1, Arrays.asList("aa")), "not eligible: nothing to do");
        assertEquals(Arrays.asList("bb", "xx", "aa"), q.order());
    }

    @Test
    void corruptOrMixedFileReadsWhatIsValid() throws Exception {
        File f = new File(tmp, "statusline.properties");
        write(f, "order=tr\\u12zz\n"); // malformed escape: Properties.load throws
        StatusPrefs corrupt = new StatusPrefs(f, new RingLog(50));
        assertTrue(corrupt.order().isEmpty(), "a corrupt file reads as empty");
        assertTrue(corrupt.setHidden("aa", true), "and is replaced on the next write");
        assertEquals("aa.hidden=true\n", read(f));

        write(f, "order= bb , bad id!,,bb,aa\ncc.hidden=false\nbad id!.hidden=true\ndd.hidden=true\nxx=y\n");
        StatusPrefs mixed = new StatusPrefs(f, new RingLog(50));
        assertEquals(Arrays.asList("bb", "aa"), mixed.order());
        assertEquals(Collections.singleton("dd"), mixed.hiddenIds());
        assertFalse(mixed.setHidden("bad id!", true));
    }

    @Test
    void recoversFromTmpWhenTargetMissing() throws Exception {
        File f = new File(tmp, "statusline.properties");
        write(DurableFiles.tmpFile(f), "order=cc,aa\n"); // died between delete and rename
        StatusPrefs p = new StatusPrefs(f, new RingLog(50));
        assertEquals(Arrays.asList("cc", "aa"), p.order());
        assertTrue(p.setHidden("aa", true));
        assertEquals("order=cc,aa\naa.hidden=true\n", read(f));
    }

    @Test
    void unwritableFileKeepsTheChoicesForThisRun() throws Exception {
        File blocker = new File(tmp, "not-a-dir");
        write(blocker, "");
        RingLog log = new RingLog(50);
        StatusPrefs p = new StatusPrefs(new File(blocker, "statusline.properties"), log);
        assertFalse(p.setHidden("aa", true));
        assertFalse(p.move("cc", -1, ABC));
        assertTrue(p.isHidden("aa"));
        assertEquals(Arrays.asList("aa", "cc", "bb"), p.order());
        p.refresh();
        assertTrue(p.isHidden("aa"), "a re-read keeps the unsaved choices");
        assertEquals(Arrays.asList("aa", "cc", "bb"), p.order());
        assertFalse(p.setHidden("aa", false));
        assertFalse(p.isHidden("aa"));
        boolean logged = false;
        for (String line : log.snapshot()) {
            logged |= line.indexOf("cannot write statusline.properties") >= 0;
        }
        assertTrue(logged, log.snapshot().toString());
    }

    @Test
    void aLaterWriteSavesEarlierUnsavedChoices() throws Exception {
        File dir = new File(tmp, "later");
        write(dir, ""); // a file where the directory should be: writes fail
        File f = new File(dir, "statusline.properties");
        StatusPrefs p = new StatusPrefs(f, new RingLog(50));
        assertFalse(p.setHidden("aa", true));
        assertTrue(dir.delete());
        assertTrue(p.setHidden("bb", true));
        assertEquals("aa.hidden=true\nbb.hidden=true\n", read(f));
    }

    /** Review fix: an unsaved move must not bring back ids another writer (mibpkg remove) dropped from the file. */
    @Test
    void unsavedOrderDoesNotResurrectRemovedIds() throws Exception {
        File dir = new File(tmp, "ro");
        assertTrue(dir.mkdirs());
        File f = new File(dir, "statusline.properties");
        write(f, "order=aa,xx,bb\n");
        assertTrue(dir.setWritable(false));
        try {
            StatusPrefs p = new StatusPrefs(f, new RingLog(50));
            assertFalse(p.move("bb", -1, Arrays.asList("aa", "bb")), "read-only: not written");
            assertEquals(Arrays.asList("bb", "xx", "aa"), p.order());

            assertTrue(dir.setWritable(true));
            write(f, "order=aa,bb\n"); // mibpkg removed xx
            assertTrue(dir.setWritable(false));
            p.refresh(Arrays.asList("aa", "bb"));
            assertEquals(Arrays.asList("bb", "aa"), p.order(), "the unsaved move holds, xx stays gone");

            assertTrue(dir.setWritable(true));
            write(f, "order=aa,bb,cc\n"); // another writer added cc
            p.refresh(Arrays.asList("aa", "bb", "cc"));
            assertEquals(Arrays.asList("bb", "aa", "cc"), p.order(), "ids new in the file follow, in file order");
            assertTrue(p.setHidden("cc", true), "writable again: everything goes out");
            assertEquals("order=bb,aa,cc\ncc.hidden=true\n", read(f));
        } finally {
            dir.setWritable(true);
        }
    }
}
