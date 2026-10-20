package mibsdk.build;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The check stamp written next to every checked jar. Canonical format (version 1; other plans read it):
 * ISO-8859-1, {@code \n} line ends, no timestamp (reproducible). Lines, in this order:
 * <pre>
 * format=1
 * kind=plugin                    (or: unit)
 * checks=classVersion48,linkCheck,permissionScan
 * plugin.id=bike-rack            (attributes, sorted by key; plugin only)
 * plugin.unrestricted=false
 * sha256.bike-rack.jar=&lt;64 lowercase hex&gt;   (one per jar, sorted by file name)
 * </pre>
 * The stamp is hashed, not signed: it guards against accident (a jar from {@code build/libs}, a hand-edited
 * jar, a jar rebuilt by a task that skips the checks), not against an adversary with repo access.
 */
public final class CheckStamp {
    private CheckStamp() {}

    /** Lowercase hex SHA-256 of the file's bytes. */
    public static String sha256(File f) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (InputStream in = Files.newInputStream(f.toPath())) {
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) {
                md.update(buf, 0, n);
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : md.digest()) {
            hex.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        }
        return hex.toString();
    }

    public static String format(String kind, List<String> checks, Map<String, String> attributes, List<File> jars)
            throws IOException {
        StringBuilder out = new StringBuilder();
        out.append("format=1\nkind=").append(kind).append("\nchecks=").append(String.join(",", checks)).append('\n');
        for (Map.Entry<String, String> e : new TreeMap<>(attributes).entrySet()) {
            out.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        List<File> sorted = new ArrayList<>(jars);
        Collections.sort(sorted, (x, y) -> x.getName().compareTo(y.getName()));
        for (File jar : sorted) {
            out.append("sha256.").append(jar.getName()).append('=').append(sha256(jar)).append('\n');
        }
        return out.toString();
    }

    public static void write(File stamp, String kind, List<String> checks, Map<String, String> attributes,
            List<File> jars) throws IOException {
        File dir = stamp.getAbsoluteFile().getParentFile();
        Files.createDirectories(dir.toPath());
        Files.write(stamp.toPath(), format(kind, checks, attributes, jars).getBytes(StandardCharsets.ISO_8859_1));
    }

    /**
     * Fails unless every task ran (or was up to date) in this build. Gradle's {@code -x} drops a task from the
     * graph without failing its dependents, so ordering alone does not prove a check ran.
     */
    public static void requireRan(org.gradle.api.Task stamping, List<? extends org.gradle.api.Task> checks) {
        for (org.gradle.api.Task c : checks) {
            org.gradle.api.tasks.TaskState st = c.getState();
            if (!st.getExecuted() || st.getFailure() != null) {
                throw new org.gradle.api.GradleException(stamping.getPath() + ": refusing to write a check stamp, "
                        + c.getPath() + " did not run in this build (excluded with -x?)");
            }
        }
    }
}
