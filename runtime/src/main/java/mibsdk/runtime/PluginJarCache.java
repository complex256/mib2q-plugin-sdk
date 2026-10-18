package mibsdk.runtime;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Set;

/**
 * Plugin jars are loaded from private snapshots so an in-place overwrite of the original never
 * changes bytes under a live class loader (and a reload always sees the new bytes).
 */
public final class PluginJarCache {

    private static int counter;

    private final File cacheDir;

    public PluginJarCache(File cacheDir) {
        this.cacheDir = cacheDir;
    }

    /** Copies {@code pluginJar} to {@code cache/<name>-<n>.jar} and returns the copy. */
    public File snapshot(File pluginJar) throws IOException {
        if (!cacheDir.isDirectory() && !cacheDir.mkdirs() && !cacheDir.isDirectory()) {
            throw new IOException("cannot create cache dir " + cacheDir);
        }
        String base = pluginJar.getName();
        if (base.endsWith(".jar")) {
            base = base.substring(0, base.length() - 4);
        }
        File copy;
        do {
            copy = new File(cacheDir, base + "-" + nextId() + ".jar");
        } while (copy.exists());
        try {
            copyFile(pluginJar, copy);
        } catch (IOException e) {
            copy.delete();
            throw e;
        }
        return copy;
    }

    /** Deletes every cached jar not in {@code keep}. */
    public void purgeExcept(Set<File> keep) {
        File[] files = cacheDir.listFiles();
        if (files == null) {
            return;
        }
        for (int i = 0; i < files.length; i++) {
            if (files[i].getName().endsWith(".jar") && !keep.contains(files[i])) {
                files[i].delete();
            }
        }
    }

    private static synchronized int nextId() {
        return ++counter;
    }

    private static void copyFile(File from, File to) throws IOException {
        InputStream in = new FileInputStream(from);
        try {
            OutputStream out = new FileOutputStream(to);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }
}
