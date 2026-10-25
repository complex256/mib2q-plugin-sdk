package mibsdk.adapter.hqc.ui;

import java.util.HashMap;
import java.util.Map;

/**
 * Image ids for installed icons, one per {@code <owner>/<sha16>} key per HMI session: the owner is the plugin id, or
 * {@code mibsdk} for the built-in set, so identical bytes in two packages get two ids. An id is never reused and
 * never re-pointed: new content under the same name gets a new id, and the old id keeps its file and path (the
 * drawer may still hold its texture). The session's one instance is {@code SdkGlobals.ICON_IDS}.
 *
 * <p>Only {@code mibsdk-ui} allocates ({@link #idFor}, synchronized). Stock threads ({@code getImagePath} on
 * HMIEvent) read {@link #relPathFor} from a volatile copy-on-write array, without a lock. No I/O.
 */
public final class IconIds {

    private final int first;
    private final int last;
    private final Map<String, Integer> byKey = new HashMap<String, Integer>(); // guarded by this
    private int next; // guarded by this
    private volatile String[] files;
    private volatile String[] relPaths;

    /** Ids {@code first..last}, inclusive. */
    public IconIds(int first, int last) {
        this.first = first;
        this.last = last;
        this.next = first;
        int n = Math.max(0, last - first + 1);
        this.files = new String[n];
        this.relPaths = new String[n];
    }

    /**
     * The id given to {@code owner + "/" + sha16}, or the next unused one (recording {@code absFile} and
     * {@code relPath}, the path from the HMI's ImageRoot); -1 when the range is spent.
     */
    public synchronized int idFor(String owner, String sha16, String absFile, String relPath) {
        String key = owner + "/" + sha16;
        Integer known = byKey.get(key);
        if (known != null) {
            return known.intValue();
        }
        if (next > last) {
            return -1;
        }
        int id = next++;
        int i = id - first;
        String[] f = (String[]) files.clone();
        String[] r = (String[]) relPaths.clone();
        f[i] = absFile;
        r[i] = relPath;
        files = f;
        relPaths = r;
        byKey.put(key, new Integer(id));
        return id;
    }

    /** The absolute file of {@code id}, or null if it is not given. */
    public String fileFor(int id) {
        String[] f = files;
        int i = id - first;
        return i >= 0 && i < f.length ? f[i] : null;
    }

    /** The path from ImageRoot of {@code id}, or null if it is not given. Lock-free: called on HMIEvent. */
    public String relPathFor(int id) {
        String[] r = relPaths;
        int i = id - first;
        return i >= 0 && i < r.length ? r[i] : null;
    }

    /** True once every id is given. */
    public synchronized boolean exhausted() {
        return next > last;
    }
}
