package mibsdk.build;

import groovy.json.JsonSlurper;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import org.gradle.api.GradleException;

/**
 * Where this build plugin's SDK artifacts live: the Maven group, the version (both stamped into the plugin jar at
 * build time from {@code gradle.properties}) and the permission catalog bundled from {@code api/permissions.json}.
 */
public final class SdkCoordinates {
    public static final String GROUP = "dev.complex256.mibsdk";

    private static final String PROPERTIES = "/mibsdk/build/sdk.properties";
    private static final String PERMISSIONS = "/mibsdk/build/permissions.json";

    private SdkCoordinates() {
    }

    /** The SDK version, the same for every artifact and for this plugin. */
    public static String version() {
        Properties p = new Properties();
        try (InputStream in = SdkCoordinates.class.getResourceAsStream(PROPERTIES)) {
            if (in == null) {
                throw new GradleException("mibsdk: the bundled " + PROPERTIES + " is missing from the build plugin");
            }
            p.load(in);
        } catch (IOException e) {
            throw new GradleException("mibsdk: cannot read the bundled " + PROPERTIES + ": " + e.getMessage(), e);
        }
        String v = p.getProperty("version");
        if (v == null || v.isEmpty()) {
            throw new GradleException("mibsdk: the bundled " + PROPERTIES + " has no version");
        }
        return v;
    }

    /** {@code group:artifact:version} of one SDK artifact. */
    public static String coordinate(String artifact) {
        return GROUP + ":" + artifact + ":" + version();
    }

    /**
     * True when the build contains the SDK's own {@code :api} project, false for a plugin built in its own repository
     * against the published artifacts.
     */
    public static boolean inSdkRepo(org.gradle.api.Project project) {
        return project.getRootProject().findProject(":api") != null;
    }

    /** Permission id to the text shown to the user, from the bundled {@code permissions.json}. */
    public static Map<String, String> permissionCatalog() {
        Object parsed;
        try (InputStream in = SdkCoordinates.class.getResourceAsStream(PERMISSIONS)) {
            if (in == null) {
                throw new GradleException("mibsdk: the bundled " + PERMISSIONS + " is missing from the build plugin");
            }
            parsed = new JsonSlurper().parse(in, "UTF-8");
        } catch (IOException | RuntimeException e) {
            throw new GradleException("mibsdk: cannot read the bundled " + PERMISSIONS + ": " + e.getMessage(), e);
        }
        Object list = parsed instanceof Map ? ((Map<?, ?>) parsed).get("permissions") : null;
        if (!(list instanceof Iterable)) {
            throw new GradleException("mibsdk: the bundled " + PERMISSIONS + " has no permissions list");
        }
        Map<String, String> catalog = new LinkedHashMap<>();
        for (Object entry : (Iterable<?>) list) {
            Map<?, ?> m = entry instanceof Map ? (Map<?, ?>) entry : null;
            Object id = m == null ? null : m.get("id");
            Object text = m == null ? null : m.get("text");
            if (!(id instanceof String) || !(text instanceof String)) {
                throw new GradleException("mibsdk: the bundled " + PERMISSIONS + " has an entry without id and text");
            }
            catalog.put((String) id, (String) text);
        }
        return catalog;
    }
}
