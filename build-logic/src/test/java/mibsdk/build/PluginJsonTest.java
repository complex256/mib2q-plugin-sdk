package mibsdk.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertEquals(2, PluginJson.MAX_API);
    }
}
