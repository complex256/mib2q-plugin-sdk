package mibsdk.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DurableFilesTest {

    @TempDir
    File dir;

    /** Delegates the file work to SYSTEM and logs each call; individual calls can be made to fail. */
    private static final class RecordingOps implements DurableFiles.Ops {
        final List<String> log = new ArrayList<String>();
        boolean failFileSync;
        boolean failDirSync;
        int renameFailures;

        public void syncFile(FileDescriptor fd) throws IOException {
            log.add("syncFile");
            if (failFileSync) {
                throw new IOException("syncFile refused");
            }
            DurableFiles.SYSTEM.syncFile(fd);
        }

        public void syncPath(File f, boolean directory) throws IOException {
            log.add("syncPath " + f.getName() + (directory ? " dir" : " file"));
            if (directory && failDirSync) {
                throw new IOException("dir sync unsupported");
            }
            DurableFiles.SYSTEM.syncPath(f, directory);
        }

        public boolean rename(File from, File to) {
            log.add("rename " + from.getName() + "->" + to.getName());
            if (renameFailures > 0) {
                renameFailures--;
                return false;
            }
            return DurableFiles.SYSTEM.rename(from, to);
        }

        public boolean delete(File f) {
            log.add("delete " + f.getName());
            return DurableFiles.SYSTEM.delete(f);
        }
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    @Test
    void replaceSyncsTheTempFileRenamesThenCommitsDirectoryAndTarget() throws Exception {
        File x = new File(dir, "x");
        RecordingOps ops = new RecordingOps();
        assertTrue(DurableFiles.replace(x, bytes("hello\n"), ops));
        assertEquals(Arrays.asList("syncFile", "rename x.tmp->x", "syncPath " + dir.getName() + " dir",
                "syncPath x file"), ops.log);
        assertArrayEquals(bytes("hello\n"), Files.readAllBytes(x.toPath()));
        assertFalse(DurableFiles.tmpFile(x).exists());
    }

    @Test
    void directorySyncFailureDoesNotFailTheWrite() throws Exception {
        File x = new File(dir, "x");
        RecordingOps ops = new RecordingOps();
        ops.failDirSync = true;
        assertTrue(DurableFiles.replace(x, bytes("v"), ops));
        assertArrayEquals(bytes("v"), Files.readAllBytes(x.toPath()));
    }

    @Test
    void renameThatCannotReplaceDeletesThenRenames() throws Exception {
        File x = new File(dir, "x");
        Files.write(x.toPath(), bytes("old"));
        RecordingOps ops = new RecordingOps();
        ops.renameFailures = 1;
        assertTrue(DurableFiles.replace(x, bytes("new"), ops));
        assertEquals(Arrays.asList("syncFile", "rename x.tmp->x", "delete x", "rename x.tmp->x",
                "syncPath " + dir.getName() + " dir", "syncPath x file"), ops.log);
        assertArrayEquals(bytes("new"), Files.readAllBytes(x.toPath()));
    }

    @Test
    void failedFileSyncLeavesTheOldTargetUntouched() throws Exception {
        File x = new File(dir, "x");
        Files.write(x.toPath(), bytes("old"));
        RecordingOps ops = new RecordingOps();
        ops.failFileSync = true;
        assertThrows(IOException.class, () -> DurableFiles.replace(x, bytes("new"), ops));
        assertArrayEquals(bytes("old"), Files.readAllBytes(x.toPath()));
        for (String call : ops.log) {
            assertFalse(call.startsWith("rename"), ops.log.toString());
        }
    }

    @Test
    void tmpFileIsTheTargetWithATmpSuffix() {
        assertEquals(new File(dir, "ledger.properties.tmp"), DurableFiles.tmpFile(new File(dir, "ledger.properties")));
    }

    @Test
    void probeReportsFileAndDirectorySync() {
        String r = DurableFiles.probe(dir, new FakeClock());
        assertTrue(r.startsWith("fileSync=ok dirSync="), r);
        assertTrue(r.matches("fileSync=ok dirSync=(ok|unsupported \\(.*\\)) fsyncMs=\\d+"), r);
        assertFalse(new File(dir, ".durable-probe").exists());
    }

    @Test
    void probeOfAMissingDirectoryReportsAFailedFileSync() {
        String r = DurableFiles.probe(new File(dir, "absent/deeper"), new FakeClock());
        assertTrue(r.startsWith("fileSync=FAILED ("), r);
    }
}
