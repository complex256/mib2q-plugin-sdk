package mibsdk.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import groovy.json.JsonSlurper;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
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

    @Test
    void sdkManifestCarriesTheValuesGenBundlesAndMibsdkDevUsed() {
        assertEquals("[package]\n"
                + "name = \"mibsdk\"\n"
                + "version = \"0.1.0\"\n"
                + "description = \"mibsdk runtime for the MIB2 head unit\"\n"
                + "\n"
                + "[compat]\n"
                + "units = [\"MHI2Q\"]\n"
                + "\n"
                + "[sdk]\n"
                + "api = 2\n"
                + "min_api = 1\n"
                + "hmi = [\"HMI_AU_MIB2_HQC_NAR_G22_C10_180803\"]\n"
                + "jars = [\"mibsdk-runtime.jar\", \"mibsdk-adapter-hqc.jar\", \"mibsdk-rt-retrotranslator.jar\"]\n"
                + "stamp = \"mibsdk-unit.stamp\"\n"
                + "configurator = \"mibsdk.adapter.hqc.ChainingConfigurator\"\n"
                + "bundle = \"MibSdk=mibsdk.adapter.hqc.SdkBundleActivator\"\n"
                + "tracer_configurator = \"de.audi.tghu.dsitrace.TraceConfigurator\"\n"
                + "hmicar_stock = \"de.audi.tghu.car.hmi.Activator\"\n"
                + "hmicar = \"mibsdk.adapter.hqc.ui.SdkCarActivator\"\n"
                + "owned = [\"de/audi/tghu/car/hmi/\", \"de/audi/atip/model/CarModelBank\"]\n"
                + "properties = [\"hook.services=org.dsi.ifc.carparkingsystem.DSICarParkingSystem\", \"ui.enabled=true\"]\n"
                + "permissions = \"permissions.json\"\n", MpkStage.sdkManifest("0.1.0"));
    }

    @Test
    void sdkApiRangeMatchesThePermissionCatalog() throws IOException {
        File catalog = new File(System.getProperty("mibsdk.repoRoot"), "api/permissions.json");
        Map<?, ?> json = (Map<?, ?>) new JsonSlurper().parse(catalog, "UTF-8");
        String m = MpkStage.sdkManifest("0.1.0");
        assertTrue(m.contains("\napi = " + json.get("api") + "\n"), m);
        assertTrue(m.contains("\nmin_api = " + json.get("min_api") + "\n"), m);
    }
}
