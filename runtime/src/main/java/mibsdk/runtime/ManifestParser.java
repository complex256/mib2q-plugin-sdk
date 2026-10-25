package mibsdk.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import mibsdk.api.SdkInfo;
import mibsdk.platform.IconIndex;

/** Parses and validates plugin.json. No java.util.regex: the car has none. */
public final class ManifestParser {

    /** Runtime-owned UI pages use this owner, so no plugin may take it. */
    static final String RESERVED_ID = "mibsdk";
    /** The api level that introduced {@code "icons"}. */
    static final int ICONS_API = 3;
    static final int MAX_ICONS = 8;

    private ManifestParser() {
    }

    public static PluginManifest parse(String json) throws ManifestException {
        Object root = MiniJson.parse(json);
        if (!(root instanceof Map)) {
            throw new ManifestException("manifest must be a JSON object");
        }
        Map<?, ?> m = (Map<?, ?>) root;

        String id = requireString(m, "id");
        if (!isValidId(id)) {
            throw new ManifestException("invalid id '" + id + "': must match [a-z0-9][a-z0-9-]{1,40}");
        }
        if (RESERVED_ID.equals(id)) {
            throw new ManifestException("reserved plugin id '" + RESERVED_ID + "'");
        }
        String name = optString(m, "name", id);
        String version = optString(m, "version", "0");
        String author = optString(m, "author", "");

        Object apiVal = m.get("api");
        if (!(apiVal instanceof Long)) {
            throw new ManifestException("api must be an integer");
        }
        long api = ((Long) apiVal).longValue();
        if (api < SdkInfo.MIN_API_VERSION || api > SdkInfo.API_VERSION) {
            throw new ManifestException("unsupported api " + api + ", this SDK implements api "
                    + SdkInfo.MIN_API_VERSION + ".." + SdkInfo.API_VERSION);
        }

        String main = requireString(m, "main");
        if (main.length() == 0) {
            throw new ManifestException("main must not be empty");
        }

        List<String> icons = stringList(m, "icons");
        checkIcons(icons, (int) api);

        return new PluginManifest(id, name, version, author, (int) api, main,
                stringList(m, "permissions"), stringList(m, "dependencies"), icons);
    }

    /** The build's PluginJson checks the same rules with the same limits. */
    private static void checkIcons(List<String> icons, int api) throws ManifestException {
        if (icons.isEmpty()) {
            return;
        }
        if (api < ICONS_API) {
            throw new ManifestException("icons need api " + ICONS_API);
        }
        if (icons.size() > MAX_ICONS) {
            throw new ManifestException("at most " + MAX_ICONS + " icons");
        }
        for (int i = 0; i < icons.size(); i++) {
            String n = (String) icons.get(i);
            if (!IconIndex.isName(n)) {
                throw new ManifestException("invalid icon name '" + n + "': must match [a-z0-9-]{1,32}");
            }
            if (icons.indexOf(n) < i) {
                throw new ManifestException("duplicate icon '" + n + "'");
            }
        }
    }

    /** Equivalent of ^[a-z0-9][a-z0-9-]{1,40}$ (total length 2..41). */
    static boolean isValidId(String id) {
        int n = id.length();
        if (n < 2 || n > 41) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            char c = id.charAt(i);
            boolean alnum = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            if (!(alnum || (i > 0 && c == '-'))) {
                return false;
            }
        }
        return true;
    }

    private static String requireString(Map<?, ?> m, String key) throws ManifestException {
        Object v = m.get(key);
        if (!(v instanceof String)) {
            throw new ManifestException(key + " is required and must be a string");
        }
        return (String) v;
    }

    private static String optString(Map<?, ?> m, String key, String dflt) throws ManifestException {
        Object v = m.get(key);
        if (v == null) {
            return dflt;
        }
        if (!(v instanceof String)) {
            throw new ManifestException(key + " must be a string");
        }
        return (String) v;
    }

    private static List<String> stringList(Map<?, ?> m, String key) throws ManifestException {
        Object v = m.get(key);
        if (v == null) {
            return Collections.emptyList();
        }
        if (!(v instanceof List)) {
            throw new ManifestException(key + " must be an array of strings");
        }
        List<String> out = new ArrayList<String>();
        for (Object o : (List<?>) v) {
            if (!(o instanceof String)) {
                throw new ManifestException(key + " must be an array of strings");
            }
            out.add((String) o);
        }
        return Collections.unmodifiableList(out);
    }
}
