package mibsdk.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import mibsdk.adapter.fake.FakePlatform;
import mibsdk.platform.SdkRoots;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeConfigTest {

    @TempDir
    File tmp;

    private File base;

    @BeforeEach
    void setUp() {
        base = new File(tmp, "mibsdk");
        base.mkdirs();
    }

    @Test
    void defaultsWhenFileMissing() {
        RuntimeConfig c = RuntimeConfig.load(FakePlatform.roots(tmp));
        assertEquals(base, c.roots().install());
        assertEquals(262144, c.storagePluginMaxBytes());
        assertEquals(1048576, c.storageTotalMaxBytes());
        assertEquals(new File("/tmp/mibsdk.log"), c.logFile());
        assertEquals(30000, c.pluginStartDelayMs());
        assertEquals(1000, c.tickMs());
        assertEquals(5000, c.slowMs());
        assertEquals(30000, c.hungMs());
        assertEquals(3, c.quarantineFailures());
        assertEquals(60000, c.quarantineWindowMs());
        assertEquals(3, c.safeModeAfterUnhealthyBoots());
        assertEquals(60000, c.healthyAfterMs());
        assertNull(c.chainConfigurator());
        assertEquals(Collections.singletonList("org.dsi.ifc.carparkingsystem.DSICarParkingSystem"), c.hookServices());
        assertEquals(2000, c.devPollMs());
        assertEquals(8388608, c.heapLowBytes());
        assertEquals(3, c.heapLowChecks());
        assertEquals(5000, c.heapCheckMs());
        assertEquals(100, c.uiPublishMinMs());
    }

    @Test
    void filesLiveInTheRootMatchingWhoWritesThem() {
        File persist = new File(tmp, "persist");
        File ram = new File(tmp, "ram");
        RuntimeConfig c = RuntimeConfig.load(new SdkRoots(base, persist, ram));
        // read-only at runtime: written by the installer only
        assertEquals(new File(base, "plugins"), c.pluginsDir());
        assertEquals(new File(base, "grants.properties"), c.grantsFile());
        assertEquals(new File(base, "SAFE_MODE"), c.safeModeMarker());
        // persistent and writable
        assertEquals(new File(persist, "boot.state"), c.bootStateFile());
        assertEquals(new File(persist, "ledger.properties"), c.ledgerFile());
        assertEquals(new File(persist, "data"), c.dataDir());
        // RAM
        assertEquals(new File(ram, "cache"), c.cacheDir());
    }

    @Test
    void readsOverridesAndHookServicesList() throws Exception {
        Files.write(new File(base, "sdk.properties").toPath(), (""
                + "plugin.start.delay.ms=0\n"
                + "callback.hung.ms = 1234 \n"
                + "quarantine.failures=5\n"
                + "chain.configurator=de.audi.tghu.dsitrace.TraceConfigurator\n"
                + "hook.services=a, b,\n"
                + "heap.low.bytes=not-a-number\n"
                + "tick.ms=-5\n"
                + "log.file=/tmp/other.log\n"
                + "storage.plugin.max.bytes=1000\n"
                + "ui.publish.min.ms=0\n"
                + "storage.total.max.bytes=0\n").getBytes(StandardCharsets.ISO_8859_1));
        RuntimeConfig c = RuntimeConfig.load(FakePlatform.roots(tmp));
        assertEquals(1000, c.storagePluginMaxBytes());
        assertEquals(1048576, c.storageTotalMaxBytes(), "0 is out of range");
        assertEquals(0, c.pluginStartDelayMs());
        assertEquals(1234, c.hungMs());
        assertEquals(5, c.quarantineFailures());
        assertEquals("de.audi.tghu.dsitrace.TraceConfigurator", c.chainConfigurator());
        assertEquals(Arrays.asList("a", "b"), c.hookServices());
        assertEquals(8388608, c.heapLowBytes(), "unparsable value falls back to the default");
        assertEquals(1000, c.tickMs(), "out-of-range value falls back to the default");
        assertEquals(new File("/tmp/other.log"), c.logFile());
        assertEquals(5000, c.slowMs(), "absent key keeps its default");
        assertEquals(0, c.uiPublishMinMs(), "0 publishes every change");
    }

    @Test
    void uiEnabledDefaultsOnAndOnlyAnExactFalseTurnsItOff() throws Exception {
        assertTrue(RuntimeConfig.load(FakePlatform.roots(tmp)).uiEnabled());
        String[][] cases = {{"false", "false"}, {" FALSE ", "false"}, {"true", "true"}, {"no", "true"}, {"", "true"}};
        for (String[] c : cases) {
            Files.write(new File(base, "sdk.properties").toPath(),
                    ("ui.enabled=" + c[0] + "\n").getBytes(StandardCharsets.ISO_8859_1));
            assertEquals(Boolean.parseBoolean(c[1]), RuntimeConfig.load(FakePlatform.roots(tmp)).uiEnabled(),
                    "ui.enabled=" + c[0]);
        }
    }
}
