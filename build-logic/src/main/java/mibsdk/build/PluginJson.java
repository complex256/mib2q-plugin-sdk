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
    final String main;
    final List<String> permissions;

    private PluginJson(String id, String main, List<String> permissions) {
        this.id = id;
        this.main = main;
        this.permissions = Collections.unmodifiableList(permissions);
    }

    boolean unrestricted() {
        return permissions.contains(Toolchain.PLATFORM_UNRESTRICTED);
    }

    /** Parses and validates the fields the build relies on; fails the build naming the file otherwise. */
    static PluginJson read(File file) {
        if (!file.isFile()) {
            throw new GradleException("mibsdk.plugin: missing " + file + " (id, name, version, author, api, main,"
                    + " permissions, dependencies)");
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
        if (!(m.get("api") instanceof Number) || ((Number) m.get("api")).intValue() < 1
                || ((Number) m.get("api")).intValue() > 2) {
            throw new GradleException("mibsdk.plugin: " + file + ": \"api\" must be 1 or 2");
        }
        if (!id.matches("[a-z0-9][a-z0-9-]{1,40}")) { // same rule as the runtime's ManifestParser
            throw new GradleException("mibsdk.plugin: " + file + ": invalid id '" + id
                    + "': must match [a-z0-9][a-z0-9-]{1,40} (it names plugins/<id>.jar)");
        }
        List<String> permissions = new ArrayList<String>();
        Object perms = m.get("permissions");
        if (perms != null) {
            if (!(perms instanceof List)) {
                throw new GradleException("mibsdk.plugin: " + file + ": \"permissions\" must be an array");
            }
            for (Object p : (List<?>) perms) {
                permissions.add(String.valueOf(p));
            }
        }
        return new PluginJson(id, main, permissions);
    }

    private static String string(File file, Map<?, ?> m, String key) {
        Object v = m.get(key);
        if (!(v instanceof String) || ((String) v).isEmpty()) {
            throw new GradleException("mibsdk.plugin: " + file + ": \"" + key + "\" must be a non-empty string");
        }
        return (String) v;
    }
}
