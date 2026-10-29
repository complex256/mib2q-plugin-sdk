package mibsdk.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginJsonTest {
    @TempDir Path dir;

    private File manifest(String id, String version, int api) throws IOException {
        return manifest(id, version, String.valueOf(api));
    }

    private File manifest(String id, String version, String apiJson) throws IOException {
        File f = dir.resolve("plugin.json").toFile();
        Files.write(f.toPath(), ("{\"id\":\"" + id + "\",\"name\":\"Hello Name\",\"version\":\"" + version
                + "\",\"author\":\"a\",\"api\":" + apiJson + ",\"main\":\"x.Y\",\"permissions\":[]}")
                .getBytes(StandardCharsets.UTF_8));
        return f;
    }

    private String fail(String id, String version) throws IOException {
        File f = manifest(id, version, 2);
        return assertThrows(GradleException.class, () -> PluginJson.read(f)).getMessage();
    }

    @Test
    void missingManifestNamesTheRequiredAndOptionalFields() {
        File absent = dir.resolve("absent").resolve("plugin.json").toFile();
        String msg = assertThrows(GradleException.class, () -> PluginJson.read(absent)).getMessage();
        assertTrue(msg.contains("(id, name, version, author, api, main; optional: permissions, icons)"), msg);
    }

    @Test
    void rejectsIdAndVersionMibpkgCannotPackage() throws IOException {
        String longId = "a" + "b".repeat(40); // 41 characters
        assertTrue(fail(longId, "1.0.0").contains("mibpkg package names are at most 40 characters"));
        assertTrue(fail("hello", "1.0").contains("\"version\" must be semver like 1.0.0 (mibpkg compares versions)"));
        assertTrue(fail("mibsdk", "1.0.0").contains("reserved for the SDK package"));
        PluginJson.read(manifest("a" + "b".repeat(39), "1.0.0", 2)); // 40 characters
        PluginJson.read(manifest("hello", "1.0.0", 1));
        PluginJson.read(manifest("hello", "1.2.3-rc.1", 2));
    }

    @Test
    void apiMustBeAnIntegralNumberWithoutFractionOrExponent() throws IOException {
        // the runtime's ManifestParser rejects these; the build must not package what the unit refuses
        for (String api : new String[] {"2.0", "2e0", "2E0", "1.5", "\"2\"", "true", "null"}) {
            File f = manifest("hello", "1.0.0", api);
            String msg = assertThrows(GradleException.class, () -> PluginJson.read(f), api).getMessage();
            assertTrue(msg.contains("api must be an integer"), api + ": " + msg);
        }
        File huge = manifest("hello", "1.0.0", "18446744073709551618"); // 2^64 + 2: must not wrap to 2
        assertTrue(assertThrows(GradleException.class, () -> PluginJson.read(huge)).getMessage()
                .contains("unsupported api"));
        assertEquals(2, PluginJson.read(manifest("hello", "1.0.0", "2")).api);
    }

    @Test
    void exposesNameVersionApi() throws IOException {
        PluginJson p = PluginJson.read(manifest("hello", "1.0.0", 1));
        assertEquals("Hello Name", p.name);
        assertEquals("1.0.0", p.version);
        assertEquals(1, p.api);
        assertEquals(1, PluginJson.MIN_API);
        assertEquals(7, PluginJson.MAX_API);
        assertEquals(java.util.Collections.emptyList(), p.icons);
    }

    private File withIcons(int api, String iconsJson) throws IOException {
        File f = dir.resolve("plugin.json").toFile();
        Files.write(f.toPath(), ("{\"id\":\"hello\",\"name\":\"Hello\",\"version\":\"1.0.0\",\"author\":\"a\","
                + "\"api\":" + api + ",\"main\":\"x.Y\",\"permissions\":[],\"icons\":" + iconsJson + "}")
                .getBytes(StandardCharsets.UTF_8));
        return f;
    }

    private String iconFail(int api, String iconsJson) throws IOException {
        File f = withIcons(api, iconsJson);
        return assertThrows(GradleException.class, () -> PluginJson.read(f), iconsJson).getMessage();
    }

    @Test
    void maxApiIs6AndIconNamesAreChecked() throws IOException {
        assertEquals(7, PluginJson.MAX_API);
        assertEquals(3, PluginJson.read(manifest("hello", "1.0.0", 3)).api);
        assertEquals(4, PluginJson.read(manifest("hello", "1.0.0", 4)).api);
        assertEquals(5, PluginJson.read(manifest("hello", "1.0.0", 5)).api);
        assertEquals(6, PluginJson.read(manifest("hello", "1.0.0", 6)).api);
        assertTrue(fail7().contains("this SDK implements api 1..7"));

        assertEquals(java.util.Arrays.asList("hello", "hello-20"),
                PluginJson.read(withIcons(3, "[\"hello\",\"hello-20\"]")).icons);
        String max = "abcdefghijabcdefghijabcdefghij-2"; // 32 characters
        assertEquals(java.util.Arrays.asList(max), PluginJson.read(withIcons(3, "[\"" + max + "\"]")).icons);
        assertEquals(8, PluginJson.read(withIcons(3, "[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\",\"g\",\"h\"]")).icons.size());

        assertTrue(iconFail(2, "[\"hello\"]").contains("\"icons\" needs \"api\": 3"));
        assertTrue(iconFail(3, "[\"hello\",\"hello\"]").contains("duplicate icon 'hello'"));
        assertTrue(iconFail(3, "[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\",\"g\",\"h\",\"i\"]").contains("at most 8 icons"));
        assertTrue(iconFail(3, "\"hello\"").contains("\"icons\" must be an array"));
        for (String bad : new String[] {"\"\"", "\"Hello\"", "\"a_b\"", "\"a.png\"", "\"" + max + "x\"", "1",
                "null"}) {
            String msg = iconFail(3, "[" + bad + "]");
            assertTrue(msg.contains("must match [a-z0-9-]{1,32}"), bad + ": " + msg);
        }
    }

    private String fail7() throws IOException {
        File f = manifest("hello", "1.0.0", 8);
        return assertThrows(GradleException.class, () -> PluginJson.read(f)).getMessage();
    }

    private String permissionFail(String permissionsJson) throws IOException {
        File f = dir.resolve("plugin.json").toFile();
        Files.write(f.toPath(), ("{\"id\":\"hello\",\"name\":\"Hello\",\"version\":\"1.0.0\",\"author\":\"a\","
                + "\"api\":1,\"main\":\"x.Y\",\"permissions\":" + permissionsJson + "}")
                .getBytes(StandardCharsets.UTF_8));
        return assertThrows(GradleException.class, () -> PluginJson.read(f), permissionsJson).getMessage();
    }

    @Test
    void unknownPermissionSuggestsTheNearest() throws IOException {
        String m = permissionFail("[\"vehicle.read\",\"ui.slot.statusbr\"]");
        assertTrue(m.contains("unknown permission \"ui.slot.statusbr\" (did you mean \"ui.slot.statusbar\"?); known: "), m);
        assertTrue(m.contains("known: vehicle.read, vehicle.parking.write, hooks.parking, ui.slot.carmenu,"
                + " ui.slot.statusbar, network, network.outbound, platform.unrestricted"), m);
    }

    @Test
    void unknownPermissionWithoutNearMatchListsKnown() throws IOException {
        String m = permissionFail("[\"gps\"]");
        assertTrue(m.contains("unknown permission \"gps\"; known: vehicle.read,"), m);
        assertFalse(m.contains("did you mean"), m);
    }
}
