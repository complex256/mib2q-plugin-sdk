package mibsdk.runtime;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * The one durable-write recipe for files under the persist root: write {@code <target>.tmp}, fsync it, rename it over
 * the target, then commit the rename by syncing the parent directory (best effort) and the target. A reader that finds
 * the target missing recovers from {@link #tmpFile}: the temp file is complete (fsynced) before any rename is tried.
 */
final class DurableFiles {

    /** Test seam over the platform calls, in the order {@link #replace} makes them. */
    interface Ops {
        /** {@code fd.sync()}. */
        void syncFile(FileDescriptor fd) throws IOException;

        /** {@code new RandomAccessFile(f, directory ? "r" : "rw").getFD().sync()}. */
        void syncPath(File f, boolean directory) throws IOException;

        /** {@link File#renameTo}. */
        boolean rename(File from, File to);

        boolean delete(File f);
    }

    static final Ops SYSTEM = new Ops() {
        public void syncFile(FileDescriptor fd) throws IOException {
            fd.sync();
        }

        public void syncPath(File f, boolean directory) throws IOException {
            RandomAccessFile raf = new RandomAccessFile(f, directory ? "r" : "rw");
            try {
                raf.getFD().sync();
            } finally {
                raf.close();
            }
        }

        public boolean rename(File from, File to) {
            return from.renameTo(to);
        }

        public boolean delete(File f) {
            return f.delete();
        }
    };

    private static final String PROBE = ".durable-probe";

    private DurableFiles() {
    }

    /** {@code <target>.tmp}: where {@link #replace} stages the content, and where readers recover from. */
    static File tmpFile(File target) {
        return new File(target.getPath() + ".tmp");
    }

    static boolean replace(File target, byte[] content) throws IOException {
        return replace(target, content, SYSTEM);
    }

    /**
     * Writes {@code content} to {@link #tmpFile}, syncs and closes it, renames it over {@code target} (delete, then
     * rename, where rename cannot replace), then commits: syncs the parent directory (failure ignored) and the target.
     * Creates the parent directory when missing.
     *
     * @return true iff the final sync of the target succeeded; false means the content is in place but may not
     *         survive a power cut
     * @throws IOException if the content is not in place (the old target, if any, is untouched unless the second
     *         rename failed after the delete; then {@link #tmpFile} holds the complete content)
     */
    static boolean replace(File target, byte[] content, Ops ops) throws IOException {
        File dir = target.getAbsoluteFile().getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("cannot create " + dir);
        }
        File tmp = tmpFile(target);
        FileOutputStream out = new FileOutputStream(tmp);
        boolean staged = false;
        try {
            out.write(content);
            ops.syncFile(out.getFD());
            out.close();
            out = null;
            staged = true;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // the write already failed
                }
            }
            if (!staged) {
                ops.delete(tmp);
            }
        }
        if (!ops.rename(tmp, target)) {
            ops.delete(target); // non-POSIX rename cannot replace; readers recover from the temp file if we die here
            if (!ops.rename(tmp, target)) {
                throw new IOException("cannot rename " + tmp.getName() + " to " + target.getName());
            }
        }
        if (dir != null) {
            try {
                ops.syncPath(dir, true);
            } catch (Throwable ignored) {
                // not every platform can sync a directory; the target sync below is the commit that matters
            }
        }
        try {
            ops.syncPath(target, false);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * {@code fileSync=ok dirSync=ok fsyncMs=<n>}, {@code fileSync=ok dirSync=unsupported (<why>) fsyncMs=<n>}, or
     * {@code fileSync=FAILED (<why>)}, measured with a scratch file {@code <dir>/.durable-probe} that is deleted
     * afterwards. {@code fsyncMs} is the time the scratch file's sync took. Never throws.
     */
    static String probe(File dir, Clock clock) {
        File f = new File(dir, PROBE);
        try {
            long fsyncMs;
            FileOutputStream out = new FileOutputStream(f);
            try {
                out.write('\n');
                long t0 = clock.uptimeMs();
                SYSTEM.syncFile(out.getFD());
                fsyncMs = clock.uptimeMs() - t0;
            } finally {
                out.close();
            }
            String dirSync;
            try {
                SYSTEM.syncPath(dir, true);
                dirSync = "ok";
            } catch (Throwable t) {
                dirSync = "unsupported (" + SdkRuntime.describe(t) + ")";
            }
            return "fileSync=ok dirSync=" + dirSync + " fsyncMs=" + fsyncMs;
        } catch (Throwable t) {
            return "fileSync=FAILED (" + SdkRuntime.describe(t) + ")";
        } finally {
            try {
                f.delete();
            } catch (Throwable ignored) {
                // nothing to do
            }
        }
    }
}
