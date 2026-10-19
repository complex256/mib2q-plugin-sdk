package mibsdk.runtime;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/**
 * Fixed-capacity in-memory log keeping the newest lines, formatted
 * {@code "<uptimeMs> <LEVEL> <source> <msg>"}. The uptime is 0 until a {@link Clock} is attached
 * with {@link #setClock}, because the log is created before the clock exists.
 */
public final class RingLog {

    private final Object writeLock = new Object();
    private final String[] lines;
    private int head;
    private int size;
    private long sequence; // guarded by this; appends ever made
    private volatile Clock clock;

    public RingLog(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
        this.lines = new String[capacity];
    }

    public void setClock(Clock clock) {
        this.clock = clock;
    }

    public void append(String level, String source, String msg) {
        Clock c = clock;
        long up = c == null ? 0 : c.uptimeMs();
        String line = up + " " + level + " " + source + " " + oneLine(msg);
        synchronized (this) {
            sequence++;
            lines[(head + size) % lines.length] = line;
            if (size < lines.length) {
                size++;
            } else {
                head = (head + 1) % lines.length;
            }
        }
    }

    /** Number of lines ever appended (monotonic; unchanged means the snapshot is unchanged). */
    public synchronized long sequence() {
        return sequence;
    }

    /** Oldest-first copy of the retained lines. */
    public synchronized List<String> snapshot() {
        List<String> out = new ArrayList<String>(size);
        for (int i = 0; i < size; i++) {
            out.add(lines[(head + i) % lines.length]);
        }
        return out;
    }

    /** Renames a file; a seam for filesystems whose rename is unsupported. */
    interface Renamer {
        boolean rename(File from, File to);
    }

    private static final Renamer FILE_RENAMER = new Renamer() {
        public boolean rename(File from, File to) {
            return from.renameTo(to);
        }
    };

    /**
     * Writes the snapshot to a sibling temp file, then renames it over {@code f}. Where rename is unsupported
     * (QNX {@code /dev/shmem}, the unit's {@code /tmp}: J9's {@code renameTo} always fails there) it writes
     * {@code f} in place instead, so a reader may briefly see a partial file.
     */
    public void writeTo(File f) throws IOException {
        writeTo(f, FILE_RENAMER);
    }

    void writeTo(File f, Renamer renamer) throws IOException {
        List<String> snap = snapshot();
        File dir = f.getAbsoluteFile().getParentFile();
        File tmp = new File(dir, f.getName() + ".tmp");
        synchronized (writeLock) { // serializes writers on the shared temp name; appenders are unaffected
            boolean renamed = false;
            try {
                write(tmp, snap);
                if (!renamer.rename(tmp, f)) {
                    f.delete(); // some filesystems refuse to rename over an existing file
                    renamed = renamer.rename(tmp, f);
                } else {
                    renamed = true;
                }
            } finally {
                if (!renamed) {
                    tmp.delete();
                }
            }
            if (!renamed) {
                write(f, snap);
            }
        }
    }

    private static void write(File f, List<String> snap) throws IOException {
        Writer w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
        try {
            for (int i = 0; i < snap.size(); i++) {
                w.write(snap.get(i));
                w.write('\n');
            }
        } finally {
            w.close();
        }
    }

    private static String oneLine(String s) {
        if (s == null) {
            return "null";
        }
        if (s.indexOf('\n') < 0 && s.indexOf('\r') < 0) {
            return s;
        }
        return s.replace('\r', ' ').replace('\n', ' ');
    }
}
