package mibsdk.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class SdkCoordinatesTest {

    private static final File REPO_ROOT = new File(System.getProperty("mibsdk.repoRoot"));

    @Test
    void versionMatchesGradleProperties() throws Exception {
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(new File(REPO_ROOT, "gradle.properties"))) {
            p.load(in);
        }
        assertEquals(p.getProperty("mibsdk.version"), SdkCoordinates.version());
        assertEquals("dev.complex256.mibsdk", SdkCoordinates.GROUP);
        assertEquals("dev.complex256.mibsdk:api:" + SdkCoordinates.version(), SdkCoordinates.coordinate("api"));
    }

    @Test
    void permissionCatalogHasEveryIdOfApiPermissionsJson() {
        Map<String, String> catalog = SdkCoordinates.permissionCatalog();
        assertEquals(7, catalog.size());
        for (String id : new String[] {"vehicle.read", "vehicle.parking.write", "hooks.parking", "ui.slot.carmenu",
                "ui.slot.statusbar"}) {
            assertTrue(catalog.containsKey(id), id);
        }
        assertEquals("Read vehicle state: ignition, gear, parking sensors", catalog.get("vehicle.read"));
    }
}
