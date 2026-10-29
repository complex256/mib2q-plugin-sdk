package mibsdk.build;

import groovy.json.JsonSlurper;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.gradle.api.GradleException;

/** The build's view of a plugin's {@code plugin.json}: what packaging and the scans need. */
final class PluginJson {
    final String id;
    final String name;
    final String version;
    final int api;
    final String main;
    final List<String> permissions;
    /** Icon names ({@code "icons"}, api 3), in manifest order; empty if none. */
    final List<String> icons;

    private PluginJson(String id, String name, String version, int api, String main, List<String> permissions,
            List<String> icons) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.api = api;
        this.main = main;
        this.permissions = Collections.unmodifiableList(permissions);
        this.icons = Collections.unmodifiableList(icons);
    }

    boolean unrestricted() {
        return permissions.contains(Toolchain.PLATFORM_UNRESTRICTED);
    }

    // Mirrors mibsdk.api.SdkInfo.MIN_API_VERSION / API_VERSION (build-logic cannot depend on :api); the runtime's
    // ManifestParser enforces the same range and wording.
    static final int MIN_API = 1;
    static final int MAX_API = 7;
    // Mirrors the runtime's ManifestParser: icons arrived with api 3; at most 8 per plugin.
    static final int ICONS_API = 3;
    static final int MAX_ICONS = 8;

    /** An icon name: it names {@code src/main/icons/<name>.png} in a plugin and a file in the SDK's built-in set. */
    static boolean isIconName(String s) {
        return s.matches("[a-z0-9-]{1,32}");
    }

    /**
     * The id rule's complaint for {@code id}, or null when the id is acceptable. {@link NewPluginTask} shares the
     * wording: a plugin scaffolded by hand and one scaffolded by {@code newPlugin} fail with the same sentence.
     *
     * <p>Stricter than the runtime's ManifestParser ({1,40}): mibpkg package names are at most 40 characters.
     */
    static String idProblem(String id) {
        if (!id.matches("[a-z][a-z0-9-]{1,39}")) {
            return "invalid id '" + id + "': must start with a letter, then a-z, 0-9 and '-' (2 to 40"
                    + " characters; it names plugins/<id>.jar and mibpkg package names are at most 40 characters)";
        }
        if (id.equals("mibsdk")) {
            return "id 'mibsdk' is reserved for the SDK package";
        }
        return null;
    }

    /**
     * Like the runtime, only an integral JSON number (no fraction, no exponent) is an api level. JsonSlurper gives
     * BigDecimal for {@code 2.0} or {@code 2e0}, Integer for a value that fits an int and Long beyond; its Long path
     * silently wraps past 2^63 ({@code 18446744073709551618} reads as 2), so a Long is never trusted as a level.
     */
    private static int checkApi(File file, Object api) {
        if (!(api instanceof Integer || api instanceof Long)) {
            throw new GradleException("mibsdk.plugin: " + file + ": api must be an integer");
        }
        if (api instanceof Long || (Integer) api < MIN_API || (Integer) api > MAX_API) {
            throw new GradleException("mibsdk.plugin: " + file + ": unsupported api "
                    + (api instanceof Long ? "(out of range)" : api.toString())
                    + ", this SDK implements api " + MIN_API + ".." + MAX_API);
        }
        return (Integer) api;
    }

    /** Parses and validates the fields the build relies on; fails the build naming the file otherwise. */
    static PluginJson read(File file) {
        if (!file.isFile()) {
            throw new GradleException("mibsdk.plugin: missing " + file + " (id, name, version, author, api, main;"
                    + " optional: permissions, icons)");
        }
        Object parsed;
        try {
            parsed = new JsonSlurper().parse(file, "UTF-8");
        } catch (RuntimeException e) {
            throw new GradleException("mibsdk.plugin: " + file + " is not valid JSON: " + e.getMessage(), e);
        }
        if (!(parsed instanceof Map)) {
            throw new GradleException("mibsdk.plugin: " + file + " must be a JSON object");
        }
        Map<?, ?> m = (Map<?, ?>) parsed;
        String id = string(file, m, "id");
        String main = string(file, m, "main");
        String name = string(file, m, "name");
        String version = string(file, m, "version");
        int api = checkApi(file, m.get("api"));
        String idProblem = idProblem(id);
        if (idProblem != null) {
            throw new GradleException("mibsdk.plugin: " + file + ": " + idProblem);
        }
        if (!version.matches("\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.-]+)?")) {
            throw new GradleException("mibsdk.plugin: " + file
                    + ": \"version\" must be semver like 1.0.0 (mibpkg compares versions)");
        }
        List<String> permissions = new ArrayList<String>();
        Object perms = m.get("permissions");
        if (perms != null) {
            if (!(perms instanceof List)) {
                throw new GradleException("mibsdk.plugin: " + file + ": \"permissions\" must be an array");
            }
            Map<String, String> catalog = SdkCoordinates.permissionCatalog();
            for (Object p : (List<?>) perms) {
                String perm = String.valueOf(p);
                if (!catalog.containsKey(perm)) {
                    throw new GradleException("mibsdk.plugin: " + file + ": unknown permission \"" + perm + "\""
                            + nearest(perm, catalog.keySet()) + "; known: " + String.join(", ", catalog.keySet()));
                }
                permissions.add(perm);
            }
        }
        return new PluginJson(id, name, version, api, main, permissions, icons(file, m.get("icons"), api));
    }

    /** {@code (did you mean "x"?)} for the first of {@code known} within edit distance 3 of {@code id}, else empty. */
    private static String nearest(String id, Iterable<String> known) {
        String best = null;
        int bestDistance = 4;
        for (String k : known) {
            int d = distance(id, k);
            if (d < bestDistance) {
                best = k;
                bestDistance = d;
            }
        }
        return best == null ? "" : " (did you mean \"" + best + "\"?)";
    }

    /** Levenshtein distance. */
    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            int[] cur = new int[b.length() + 1];
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int subst = prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                cur[j] = Math.min(subst, Math.min(prev[j] + 1, cur[j - 1] + 1));
            }
            prev = cur;
        }
        return prev[b.length()];
    }

    private static List<String> icons(File file, Object v, int api) {
        List<String> icons = new ArrayList<String>();
        if (v == null) {
            return icons;
        }
        if (!(v instanceof List)) {
            throw new GradleException("mibsdk.plugin: " + file + ": \"icons\" must be an array of icon names");
        }
        for (Object o : (List<?>) v) {
            if (!(o instanceof String) || !isIconName((String) o)) {
                throw new GradleException("mibsdk.plugin: " + file + ": invalid icon name " + o
                        + ": must match [a-z0-9-]{1,32} (it names icons/<name>.png)");
            }
            if (icons.contains(o)) {
                throw new GradleException("mibsdk.plugin: " + file + ": duplicate icon '" + o + "'");
            }
            icons.add((String) o);
        }
        if (!icons.isEmpty() && api < ICONS_API) {
            throw new GradleException("mibsdk.plugin: " + file + ": \"icons\" needs \"api\": " + ICONS_API);
        }
        if (icons.size() > MAX_ICONS) {
            throw new GradleException("mibsdk.plugin: " + file + ": at most " + MAX_ICONS + " icons");
        }
        return icons;
    }

    private static String string(File file, Map<?, ?> m, String key) {
        Object v = m.get(key);
        if (!(v instanceof String) || ((String) v).isEmpty()) {
            throw new GradleException("mibsdk.plugin: " + file + ": \"" + key + "\" must be a non-empty string");
        }
        return (String) v;
    }
}
