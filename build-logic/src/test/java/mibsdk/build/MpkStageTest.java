package mibsdk.build;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import groovy.json.JsonSlurper;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MpkStageTest {

    @TempDir
    Path dir;

    private PluginJson plugin(String nameJson, int api, String permsJson) throws IOException {
        Path f = dir.resolve("plugin.json");
        Files.write(f, ("{\"id\":\"bike-rack\",\"name\":" + nameJson + ",\"version\":\"1.2.3\",\"author\":\"a\","
                + "\"api\":" + api + ",\"main\":\"x.Y\",\"permissions\":" + permsJson + ",\"dependencies\":[]}")
                .getBytes(StandardCharsets.UTF_8));
        return PluginJson.read(f.toFile());
    }

    @Test
    void pluginManifestIsExactAndEscaped() throws IOException {
        PluginJson p = plugin("\"Bike \\\"Rack\\\" \\\\ v2\"", 2, "[\"vehicle.read\",\"network\"]");
        assertEquals("[package]\n"
                + "name = \"bike-rack\"\n"
                + "version = \"1.2.3\"\n"
                + "description = \"Bike \\\"Rack\\\" \\\\ v2 (mibsdk plugin)\"\n"
                + "\n"
                + "[compat]\n"
                + "units = [\"MHI2Q\"]\n"
                + "\n"
                + "[plugin]\n"
                + "jar = \"bike-rack.jar\"\n"
                + "api = 2\n"
                + "permissions = [\"vehicle.read\", \"network\"]\n", MpkStage.pluginManifest(p));
    }

    @Test
    void pluginManifestWithNoPermissionsHasAnEmptyList() throws IOException {
        String m = MpkStage.pluginManifest(plugin("\"Hello\"", 1, "[]"));
        assertTrue(m.contains("api = 1\npermissions = []\n"), m);
    }

    /** The SDK's built-in set: dist/icons/*.png, by name, sorted. */
    private static final List<String> SDK_ICONS = List.of(
            "check-20", "check-72", "check-72r", "circle-20", "circle-72", "circle-72r",
            "cross-20", "cross-72", "cross-72r", "dot-20", "dot-72", "dot-72r",
            "plug-20", "plug-72", "plug-72r", "square-20", "square-72", "square-72r",
            "star-20", "star-72", "star-72r", "triangle-20", "triangle-72", "triangle-72r");

    @Test
    void sdkManifestManagesHMISystemAndShipsIcons() {
        String icons = "icons = [";
        for (int i = 0; i < SDK_ICONS.size(); i++) {
            icons += (i == 0 ? "" : ", ") + "\"" + SDK_ICONS.get(i) + "\"";
        }
        icons += "]\n";
        assertEquals("[package]\n"
                + "name = \"mibsdk\"\n"
                + "version = \"0.1.0\"\n"
                + "description = \"mibsdk runtime for the MIB2 head unit\"\n"
                + "\n"
                + "[compat]\n"
                + "units = [\"MHI2Q\"]\n"
                + "\n"
                + "[sdk]\n"
                + "api = 6\n"
                + "min_api = 1\n"
                + "hmi = [\"HMI_AU_MIB2_HQC_NAR_G22_C10_180803\"]\n"
                + "jars = [\"mibsdk-runtime.jar\", \"mibsdk-adapter-hqc.jar\", \"mibsdk-rt-retrotranslator.jar\"]\n"
                + "stamp = \"mibsdk-unit.stamp\"\n"
                + "configurator = \"mibsdk.adapter.hqc.ChainingConfigurator\"\n"
                + "bundle = \"MibSdk=mibsdk.adapter.hqc.SdkBundleActivator\"\n"
                + "tracer_configurator = \"de.audi.tghu.dsitrace.TraceConfigurator\"\n"
                + "hmicar_stock = \"de.audi.tghu.car.hmi.Activator\"\n"
                + "hmicar = \"mibsdk.adapter.hqc.ui.SdkCarActivator\"\n"
                + "hmisystem_stock = \"de.audi.tghu.system.hmi.Activator\"\n"
                + "hmisystem = \"mibsdk.adapter.hqc.ui.SdkSystemActivator\"\n"
                + "owned = [\"de/audi/tghu/car/hmi/\", \"de/audi/atip/model/CarModelBank\", \"de/audi/tghu/system/hmi/\"]\n"
                + "properties = [\"hook.services=org.dsi.ifc.carparkingsystem.DSICarParkingSystem\", \"ui.enabled=true\"]\n"
                + "permissions = \"permissions.json\"\n"
                + icons, MpkStage.sdkManifest("0.1.0", SDK_ICONS));
    }

    @Test
    void sdkIconListIsTheBuiltInSetOnDisk() {
        File dist = new File(System.getProperty("mibsdk.repoRoot"), "dist/icons");
        assertEquals(SDK_ICONS, MpkStage.iconNames(dist));
    }

    @Test
    void pluginPackageStagesDeclaredIcons() throws IOException {
        Path f = dir.resolve("plugin.json");
        Files.write(f, ("{\"id\":\"bike-rack\",\"name\":\"Bike\",\"version\":\"1.2.3\",\"author\":\"a\","
                + "\"api\":3,\"main\":\"x.Y\",\"permissions\":[],\"icons\":[\"row\",\"status\"]}")
                .getBytes(StandardCharsets.UTF_8));
        PluginJson p = PluginJson.read(f.toFile());
        assertTrue(MpkStage.pluginManifest(p).endsWith("api = 3\npermissions = []\nicons = [\"row\", \"status\"]\n"),
                MpkStage.pluginManifest(p));

        File src = dir.resolve("src").toFile();
        Files.createDirectories(src.toPath());
        Files.write(new File(src, "row.png").toPath(), fixture("ok-72.png"));
        Files.write(new File(src, "status.png").toPath(), fixture("ok-20.png"));
        File stage = dir.resolve("stage").toFile();
        MpkStage.stageIcons(p.icons, src, stage, true);
        assertArrayEquals(fixture("ok-72.png"), Files.readAllBytes(stage.toPath().resolve("icons/row.png")));
        assertArrayEquals(fixture("ok-20.png"), Files.readAllBytes(stage.toPath().resolve("icons/status.png")));

        // A bad PNG fails staging, and nothing of the set is staged.
        Files.write(new File(src, "status.png").toPath(), fixture("interlaced.png"));
        File stage2 = dir.resolve("stage2").toFile();
        GradleException e = assertThrows(GradleException.class, () -> MpkStage.stageIcons(p.icons, src, stage2, true));
        assertTrue(e.getMessage().contains("status.png: icon PNG refused: interlace 1"), e.getMessage());
        assertFalse(new File(stage2, "icons").exists());

        // So does a declared icon without its file.
        Files.delete(new File(src, "status.png").toPath());
        e = assertThrows(GradleException.class, () -> MpkStage.stageIcons(p.icons, src, stage2, true));
        assertTrue(e.getMessage().contains("status.png"), e.getMessage());
        assertFalse(new File(stage2, "icons").exists());
    }

    @Test
    void pluginStageWritesReflectionsForDrawerSizedIconsOnly() throws IOException {
        Path f = dir.resolve("plugin.json");
        Files.write(f, ("{\"id\":\"bike-rack\",\"name\":\"Bike\",\"version\":\"1.2.3\",\"author\":\"a\","
                + "\"api\":3,\"main\":\"x.Y\",\"permissions\":[],\"icons\":[\"hello\",\"hello-20\"]}")
                .getBytes(StandardCharsets.UTF_8));
        PluginJson p = PluginJson.read(f.toFile());
        String manifest = MpkStage.pluginManifest(p);
        File src = dir.resolve("src").toFile();
        Files.createDirectories(src.toPath());
        Files.write(new File(src, "hello.png").toPath(), fixture("ok-72.png"));
        Files.write(new File(src, "hello-20.png").toPath(), fixture("ok-20.png"));
        File stage = dir.resolve("stage").toFile();
        MpkStage.stageIcons(p.icons, src, stage, true);

        byte[] r = Files.readAllBytes(stage.toPath().resolve("icons/hello.r.png"));
        PngCheck.Info info = PngCheck.check(r, 72);
        assertEquals(72, info.width);
        assertEquals(68, info.height);
        assertArrayEquals(Reflection.encode(Reflection.of(PngCheck.rgba(fixture("ok-72.png")))), r);
        assertFalse(stage.toPath().resolve("icons/hello-20.r.png").toFile().exists());
        assertEquals(List.of("hello-20.png", "hello.png", "hello.r.png"),
                List.of(new File(stage, "icons").list()).stream().sorted().collect(java.util.stream.Collectors.toList()));
        assertEquals(manifest, MpkStage.pluginManifest(p));
        assertTrue(manifest.endsWith("icons = [\"hello\", \"hello-20\"]\n"), manifest);
    }

    @Test
    void sdkStageWritesNoReflections() throws IOException {
        File src = dir.resolve("src").toFile();
        Files.createDirectories(src.toPath());
        Files.write(new File(src, "dot-72.png").toPath(), fixture("ok-72.png"));
        Files.write(new File(src, "dot-20.png").toPath(), fixture("ok-20.png"));
        File stage = dir.resolve("stage").toFile();
        MpkStage.stageIcons(MpkStage.iconNames(src), src, stage, false);
        String[] staged = new File(stage, "icons").list();
        java.util.Arrays.sort(staged);
        assertArrayEquals(new String[] {"dot-20.png", "dot-72.png"}, staged);
    }

    @Test
    void pluginManifestWithoutIconsHasNoIconsKey() throws IOException {
        assertFalse(MpkStage.pluginManifest(plugin("\"Hello\"", 3, "[]")).contains("icons"));
    }

    private static byte[] fixture(String name) throws IOException {
        try (java.io.InputStream in = MpkStageTest.class.getResourceAsStream("/png/" + name)) {
            return in.readAllBytes();
        }
    }

    @Test
    void sdkApiRangeMatchesThePermissionCatalog() throws IOException {
        File catalog = new File(System.getProperty("mibsdk.repoRoot"), "api/permissions.json");
        Map<?, ?> json = (Map<?, ?>) new JsonSlurper().parse(catalog, "UTF-8");
        String m = MpkStage.sdkManifest("0.1.0", List.of());
        assertTrue(m.contains("\napi = " + json.get("api") + "\n"), m);
        assertTrue(m.contains("\nmin_api = " + json.get("min_api") + "\n"), m);
    }
}
