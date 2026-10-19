package mibsdk.runtime;

import java.io.File;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Caps the plugins' {@link PluginStorage} files: each at {@code perPluginBytes}, all of {@code data/} together at
 * {@code totalBytes}. The persistent partition is small and shared with the stock persistence database, so a plugin
 * write that would go over is refused (the plugin's {@code put} throws) rather than ever filling it.
 *
 * <p>Sizes are each store's estimate of its written file ({@link PluginStorage#estimatedBytes()}, an upper bound);
 * {@code data/*.properties} files of plugins without a store in this run count with their length on disk, read
 * once here. Growing past either cap is refused; shrinking is always allowed. Thread-safe; never calls out while
 * holding its lock.
 */
final class StorageQuota {

    private final long perPluginBytes;
    private final long totalBytes;
    private final Map sizes = new HashMap(); // File (absolute) -> Long; guarded by this

    StorageQuota(File dataDir, long perPluginBytes, long totalBytes) {
        this.perPluginBytes = perPluginBytes;
        this.totalBytes = totalBytes;
        File[] files = dataDir.listFiles();
        if (files != null) {
            for (int i = 0; i < files.length; i++) {
                if (files[i].isFile() && files[i].getName().endsWith(".properties")) {
                    sizes.put(files[i].getAbsoluteFile(), new Long(files[i].length()));
                }
            }
        }
    }

    /** Records {@code bytes} for {@code file} without any check (a store that just loaded its file). */
    synchronized void set(File file, long bytes) {
        sizes.put(file.getAbsoluteFile(), new Long(bytes));
    }

    /**
     * Resizes {@code file}'s share to {@code bytes}; null if done, else why not (nothing changed). Shrinking always
     * succeeds.
     */
    synchronized String resize(File file, long bytes) {
        File key = file.getAbsoluteFile();
        Long old = (Long) sizes.get(key);
        long current = old == null ? 0 : old.longValue();
        if (bytes > current) {
            if (bytes > perPluginBytes) {
                return "storage full: about " + bytes + " bytes, the per-plugin limit is " + perPluginBytes;
            }
            long total = bytes - current;
            for (Iterator it = sizes.values().iterator(); it.hasNext();) {
                total += ((Long) it.next()).longValue();
            }
            if (total > totalBytes) {
                return "storage full: all plugins' data would be about " + total + " bytes, the limit is "
                        + totalBytes;
            }
        }
        sizes.put(key, new Long(bytes));
        return null;
    }
}
