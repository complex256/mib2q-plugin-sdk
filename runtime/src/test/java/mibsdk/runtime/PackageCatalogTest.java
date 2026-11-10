package mibsdk.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The snapshot mibpkg writes for the Plugins menu. */
class PackageCatalogTest {

    @TempDir
    File dir;

    private PackageCatalog load(String text) throws Exception {
        File f = new File(dir, "mibpkg.catalog");
        Files.write(f.toPath(), text.getBytes(Charset.forName("UTF-8")));
        return PackageCatalog.load(f);
    }

    @Test
    void parsesInstalledUpdatesAndAvailable() throws Exception {
        PackageCatalog c = load("# comment\n"
                + "format=1\n"
                + "updated=1794200000\n"
                + "count=3\n"
                + "pkg.bike-rack.desc=Bike Rack (mibsdk plugin)\n"
                + "pkg.bike-rack.kind=plugin\n"
                + "pkg.bike-rack.installed=1.1.0\n"
                + "pkg.bike-rack.granted=vehicle.read,vehicle.parking.write\n"
                + "pkg.bike-rack.update=1.2.0\n"
                + "pkg.bike-rack.updatefrom=complex256-mibpkg\n"
                + "pkg.bike-rack.avail=1.2.0\n"
                + "pkg.bike-rack.availfrom=complex256-mibpkg\n"
                + "pkg.bike-rack.availname=mibpkg\n"
                + "pkg.bike-rack.compatible=true\n"
                + "pkg.bike-rack.perms=vehicle.read,vehicle.parking.write\n"
                + "pkg.bike-rack.perm.vehicle.read=Read vehicle state\n"
                + "pkg.bike-rack.perm.vehicle.parking.write=Change parking settings\n"
                + "pkg.hello.kind=plugin\n"
                + "pkg.hello.avail=1.0.0\n"
                + "pkg.hello.availfrom=complex256-mibpkg\n"
                + "pkg.hello.availname=mibpkg\n"
                + "pkg.hello.compatible=false\n"
                + "pkg.mibsdk.kind=sdk\n"
                + "pkg.mibsdk.installed=0.7.1\n");

        assertEquals(1794200000L, c.updated);
        assertEquals(3, c.packages.size());
        assertNull(c.error);

        PackageCatalog.Pkg rack = c.find("bike-rack");
        assertNotNull(rack);
        assertTrue(rack.installedPkg());
        assertTrue(rack.hasUpdate());
        assertEquals("1.1.0", rack.installed);
        assertEquals("1.2.0", rack.update);
        assertEquals("complex256-mibpkg", rack.availFrom);
        assertEquals("mibpkg", rack.availName);
        assertEquals("Bike Rack (mibsdk plugin)", rack.desc);
        assertEquals(2, rack.permList().size());
        assertEquals("Change parking settings", rack.permText.get("vehicle.parking.write"));

        List<PackageCatalog.Pkg> updates = c.updates();
        assertEquals(1, updates.size());
        assertEquals("bike-rack", updates.get(0).name);

        // hello is not installed and its only offer does not fit this unit
        PackageCatalog.Pkg hello = c.find("hello");
        assertFalse(hello.installedPkg());
        assertFalse(hello.compatible);
        assertTrue(hello.hasUpdate() == false);
        assertEquals(1, c.available().size());
        assertEquals("mibsdk", c.packages.get(2).name, "sorted by name");

        assertTrue(PackageCatalog.EMPTY.packages.isEmpty(), "EMPTY has no packages");
    }

    @Test
    void missingOrUnreadableFileIsEmpty() {
        assertTrue(PackageCatalog.load(new File(dir, "nope")).packages.isEmpty());
        assertTrue(PackageCatalog.load(null).packages.isEmpty());
    }

    @Test
    void escapesAndSplitOnTheFirstEqualsAreReadBack() throws Exception {
        PackageCatalog c = load("pkg.x.desc=a=b \\\\ c\\nd\npkg.x.perm.net=Query a=b\n");
        PackageCatalog.Pkg x = c.find("x");
        assertEquals("a=b \\ c\nd", x.desc);
        assertEquals("Query a=b", x.permText.get("net"));
        assertEquals(2, PackageCatalog.split("net, ui.slot.statusbar").size());
        assertTrue(PackageCatalog.split("").isEmpty());
    }
}
